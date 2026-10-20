CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    owner_subject VARCHAR(255) NOT NULL,
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    balance NUMERIC(19, 2) NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    account_type VARCHAR(16) NOT NULL DEFAULT 'CUSTOMER'
        CHECK (account_type IN ('CUSTOMER', 'CLEARING')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT customer_balance_nonnegative
        CHECK (account_type <> 'CUSTOMER' OR balance >= 0)
);

CREATE INDEX accounts_owner_subject_idx ON accounts (owner_subject);
