#!/usr/bin/env python3
"""Exercise the dev API, including retries, concurrency, access control, and restart persistence."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from decimal import Decimal
import json
import os
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--base-url', default='http://127.0.0.1:8080')
parser.add_argument('--state', type=Path, help='save demo state for a restart check; no tokens are saved')
parser.add_argument('--verify-state', type=Path, help='verify balances and replay after a restart')
args = parser.parse_args()

def request(path, token=None, body=None, key=None, expected=200):
    headers = {}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if key:
        headers['Idempotency-Key'] = key
    payload = None
    if body is not None:
        payload = json.dumps(body).encode()
        headers['Content-Type'] = 'application/json'
    operation = Request(args.base_url.rstrip('/') + path, data=payload, headers=headers)
    try:
        response = urlopen(operation, timeout=30)
    except HTTPError as error:
        response = error
    with response:
        content = response.read().decode()
        assert response.code == expected, f'{path}: expected {expected}, got {response.code}: {content}'
        return json.loads(content, parse_float=Decimal) if content else {}

def token(subject, role):
    return request('/dev/token', body={'subject': subject, 'roles': [role]})['accessToken']

if args.verify_state:
    state = json.loads(args.verify_state.read_text())
    alice = token(state['alice'], 'CUSTOMER')
    bob = token(state['bob'], 'CUSTOMER')
    for account, owner, expected in [(state['source'], alice, '15'), (state['destination'], bob, '25')]:
        assert Decimal(str(request('/api/v1/accounts/' + account, owner)['balance'])) == Decimal(expected)
    replay = request('/api/v1/transfers', alice, state['transfer'], state['key'], 201)
    assert replay['id'] == state['transactionId']
    print('PASS: balances and idempotency survived restart/redeployment.')
else:
    prefix = 'demo-' + uuid.uuid4().hex[:12]
    alice, bob = token(prefix + '-alice', 'CUSTOMER'), token(prefix + '-bob', 'CUSTOMER')
    teller, auditor = token(prefix + '-teller', 'TELLER'), token(prefix + '-auditor', 'AUDITOR')
    source = request('/api/v1/accounts', alice, {}, expected=201)['id']
    destination = request('/api/v1/accounts', bob, {}, expected=201)['id']
    request(f'/api/v1/accounts/{source}/deposits', teller, {'amount': '100', 'currency': 'UGX'}, prefix + '-fund', 201)
    transfer = {'sourceAccountId': source, 'destinationAccountId': destination, 'amount': '25', 'currency': 'UGX'}
    key = prefix + '-transfer'
    first = request('/api/v1/transfers', alice, transfer, key, 201)
    assert request('/api/v1/transfers', alice, transfer, key, 201) == first

    def withdrawal(index):
        try:
            request(f'/api/v1/accounts/{source}/withdrawals', teller,
                    {'amount': '30', 'currency': 'UGX'}, prefix + f'-withdraw-{index}', 201)
            return True
        except AssertionError as error:
            if 'got 409' not in str(error) or 'INSUFFICIENT_FUNDS' not in str(error):
                raise
            return False

    with ThreadPoolExecutor(max_workers=2) as pool:
        assert sum(pool.map(withdrawal, range(4))) == 2
    assert Decimal(str(request('/api/v1/accounts/' + source, alice)['balance'])) == Decimal('15')
    assert Decimal(str(request('/api/v1/accounts/' + destination, bob)['balance'])) == Decimal('25')
    request('/api/v1/accounts/' + source, bob, expected=403)
    assert request('/api/v1/ledger/reconciliation', auditor)['balanced'] is True
    if args.state:
        state = {'alice': prefix + '-alice', 'bob': prefix + '-bob', 'source': source, 'destination': destination,
                 'transfer': transfer, 'key': key, 'transactionId': first['id']}
        descriptor = os.open(args.state, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, 'w') as output:
            json.dump(state, output)
    print('PASS: accounts, cash, transfer replay, concurrent withdrawals, permissions, and reconciliation.')
