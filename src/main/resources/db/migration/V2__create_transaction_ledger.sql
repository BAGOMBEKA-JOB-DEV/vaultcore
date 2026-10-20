INSERT INTO accounts (id, owner_subject, currency, account_type)
VALUES ('00000000-0000-0000-0000-000000000001', 'SYSTEM:cash', 'UGX', 'CLEARING');

CREATE TABLE bank_transactions (
    id UUID PRIMARY KEY,
    operation VARCHAR(16) NOT NULL CHECK (operation IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER')),
    source_account_id UUID NOT NULL REFERENCES accounts(id),
    destination_account_id UUID NOT NULL REFERENCES accounts(id),
    amount NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency = 'UGX'),
    actor_subject VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (source_account_id <> destination_account_id)
);

CREATE INDEX bank_transactions_source_history_idx ON bank_transactions (source_account_id, created_at DESC, id DESC);
CREATE INDEX bank_transactions_destination_history_idx ON bank_transactions (destination_account_id, created_at DESC, id DESC);

CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL REFERENCES bank_transactions(id),
    account_id UUID NOT NULL REFERENCES accounts(id),
    amount NUMERIC(19, 2) NOT NULL CHECK (amount <> 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (transaction_id, account_id)
);
CREATE INDEX ledger_entries_account_idx ON ledger_entries (account_id);

CREATE TABLE idempotency_records (
    actor_subject VARCHAR(255) NOT NULL,
    operation VARCHAR(16) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    transaction_id UUID REFERENCES bank_transactions(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (actor_subject, operation, idempotency_key)
);

CREATE FUNCTION reject_posting_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Committed transactions and ledger entries are append-only' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER ledger_append_only BEFORE UPDATE OR DELETE ON ledger_entries
FOR EACH ROW EXECUTE FUNCTION reject_posting_mutation();
CREATE TRIGGER transactions_append_only BEFORE UPDATE OR DELETE ON bank_transactions
FOR EACH ROW EXECUTE FUNCTION reject_posting_mutation();

CREATE FUNCTION verify_balanced_transaction() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    transaction_key UUID;
    tx bank_transactions%ROWTYPE;
    entry_count BIGINT;
    correct_count BIGINT;
    net NUMERIC;
BEGIN
    IF TG_TABLE_NAME = 'bank_transactions' THEN
        transaction_key := NEW.id;
    ELSE
        transaction_key := NEW.transaction_id;
    END IF;
    SELECT * INTO STRICT tx FROM bank_transactions WHERE id = transaction_key;
    SELECT count(*), sum(e.amount), count(*) FILTER (WHERE a.currency = tx.currency AND
            ((e.account_id = tx.source_account_id AND e.amount = -tx.amount) OR
             (e.account_id = tx.destination_account_id AND e.amount = tx.amount)))
    INTO entry_count, net, correct_count
    FROM ledger_entries e JOIN accounts a ON a.id = e.account_id
    WHERE e.transaction_id = transaction_key;
    IF entry_count <> 2 OR net <> 0 OR correct_count <> 2 THEN
        RAISE EXCEPTION 'Transaction must have matching, balanced ledger entries' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER transactions_balance_check AFTER INSERT ON bank_transactions
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_balanced_transaction();
CREATE CONSTRAINT TRIGGER entries_balance_check AFTER INSERT ON ledger_entries
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_balanced_transaction();

CREATE FUNCTION verify_completed_idempotency() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM idempotency_records WHERE actor_subject = NEW.actor_subject
            AND operation = NEW.operation AND idempotency_key = NEW.idempotency_key
            AND transaction_id IS NULL) THEN
        RAISE EXCEPTION 'Idempotency claim must be completed before commit' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER completed_idempotency_check AFTER INSERT ON idempotency_records
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_completed_idempotency();
