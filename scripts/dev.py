#!/usr/bin/env python3
"""Run one private PostgreSQL instance and the API; stop both on exit."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--build', action='store_true', help='verify/build before starting any services')
arguments = parser.parse_args()
project = Path(__file__).resolve().parent.parent
os.chdir(project)
if not (project / '.env').exists():
    raise SystemExit('Run ./scripts/setup.sh first.')
settings = dict(line.split('=', 1) for line in (project / '.env').read_text().splitlines()
                if line and not line.startswith('#') and '=' in line)
postgres_candidates = sorted(Path('/usr/lib/postgresql').glob('*/bin/initdb'))
postgres_override = os.environ.get('VAULTCORE_POSTGRES_BIN')
if postgres_override:
    postgres_bin = Path(postgres_override)
elif postgres_candidates:
    postgres_bin = postgres_candidates[-1].parent
else:
    raise SystemExit('Native PostgreSQL server tools are missing; use the Compose instructions in README.md.')
if arguments.build or not list((project / 'target').glob('vaultcore-*.jar')):
    subprocess.run([str(project / 'scripts/mvn.sh'), '--batch-mode', 'verify'], check=True)

default_data = Path(os.environ.get('XDG_DATA_HOME', str(Path.home() / '.local/share'))) / 'vaultcore/postgres'
data = Path(os.environ.get('VAULTCORE_DEV_DATA_DIR', str(default_data)))
data.mkdir(parents=True, exist_ok=True, mode=0o700)
database = settings.get('DB_NAME', 'vaultcore')
username = settings.get('DB_USER', 'vaultcore')
port = int(os.environ.get('VAULTCORE_DEV_DB_PORT', settings.get('DB_PORT', '55432')))
if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]{0,62}', database) or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]{0,62}', username):
    raise SystemExit('Use simple PostgreSQL database/user names in .env.')
if not 1024 <= port <= 65535:
    raise SystemExit('DB_PORT must be between 1024 and 65535.')
password = settings.get('DB_PASSWORD', '')
if not password:
    raise SystemExit('Set DB_PASSWORD in .env before running.')
if subprocess.run([str(postgres_bin / 'pg_ctl'), '-D', str(data), 'status'],
                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
    raise SystemExit('This private development database is already running; finish its other dev.py session first.')
new_database = not (data / 'PG_VERSION').exists()
if new_database:
    with tempfile.NamedTemporaryFile(mode='w', prefix='vaultcore-password-') as secret:
        secret.write(password + '\n')
        secret.flush()
        subprocess.run([str(postgres_bin / 'initdb'), '-D', str(data), '-U', username,
                        '--pwfile=' + secret.name, '--auth-local=trust', '--auth-host=scram-sha-256',
                        '--no-locale', '--encoding=UTF8'], check=True, stdout=subprocess.DEVNULL)

started = False
try:
    subprocess.run([str(postgres_bin / 'pg_ctl'), '-D', str(data), '-l', str(data / 'server.log'), '-w', 'start',
                    '-o', f'-h 127.0.0.1 -p {port} -k {data} -c shared_buffers=32MB -c max_connections=20'], check=True)
    started = True
    environment = os.environ | settings | {
        'DB_URL': f'jdbc:postgresql://127.0.0.1:{port}/{database}',
        'PGPASSWORD': password,
    }
    exists = subprocess.run([str(postgres_bin / 'psql'), '-h', str(data), '-p', str(port), '-U', username,
                             '-d', 'postgres', '-tAc', f"SELECT 1 FROM pg_database WHERE datname='{database}'"],
                            check=True, capture_output=True, text=True, env=environment).stdout.strip()
    if exists != '1':
        subprocess.run([str(postgres_bin / 'createdb'), '-h', str(data), '-p', str(port), '-U', username, database],
                       check=True, env=environment)
    print(f"Local practice API: http://127.0.0.1:{environment.get('SERVER_PORT', '8080')}. "
          'Ctrl+C stops the API and its private database.', flush=True)
    subprocess.run([str(project / 'scripts/run.sh'), '--spring.profiles.active=dev'], check=True, env=environment)
except KeyboardInterrupt:
    pass
finally:
    if started:
        subprocess.run([str(postgres_bin / 'pg_ctl'), '-D', str(data), '-m', 'fast', '-w', 'stop'],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
