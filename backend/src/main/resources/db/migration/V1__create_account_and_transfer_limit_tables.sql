CREATE TABLE customers (
    id UUID PRIMARY KEY,
    identity_subject VARCHAR(255) NOT NULL UNIQUE
);

CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL REFERENCES customers(id),
    iban VARCHAR(34) NOT NULL UNIQUE,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE', 'CLOSED')),
    balance NUMERIC(19, 2) NOT NULL CHECK (balance >= 0)
);

CREATE TABLE transfers (
    id UUID PRIMARY KEY,
    sender_customer_id UUID NOT NULL REFERENCES customers(id),
    amount NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    status VARCHAR(16) NOT NULL CHECK (status = 'COMPLETED'),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_transfers_sender_status_created_at
    ON transfers (sender_customer_id, status, created_at);
