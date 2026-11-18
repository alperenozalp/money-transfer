ALTER TABLE transfers
    ADD COLUMN source_account_id UUID REFERENCES accounts(id),
    ADD COLUMN destination_account_id UUID REFERENCES accounts(id),
    ADD COLUMN currency VARCHAR(3),
    ADD COLUMN description VARCHAR(500),
    ADD COLUMN idempotency_key VARCHAR(255),
    ADD COLUMN request_fingerprint VARCHAR(64);

ALTER TABLE transfers
    ADD CONSTRAINT chk_transfers_details_complete
        CHECK (
            (source_account_id IS NULL
                AND destination_account_id IS NULL
                AND currency IS NULL
                AND idempotency_key IS NULL
                AND request_fingerprint IS NULL)
            OR
            (source_account_id IS NOT NULL
                AND destination_account_id IS NOT NULL
                AND currency = 'TRY'
                AND idempotency_key IS NOT NULL
                AND request_fingerprint IS NOT NULL
                AND source_account_id <> destination_account_id)
        ),
    ADD CONSTRAINT chk_transfers_fingerprint_length
        CHECK (request_fingerprint IS NULL OR length(request_fingerprint) = 64);

CREATE UNIQUE INDEX uk_transfers_sender_idempotency_key
    ON transfers (sender_customer_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY,
    transfer_id UUID NOT NULL REFERENCES transfers(id),
    account_id UUID NOT NULL REFERENCES accounts(id),
    direction VARCHAR(8) NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_ledger_entries_transfer_direction UNIQUE (transfer_id, direction)
);

CREATE INDEX idx_transfers_sender_created_at
    ON transfers (sender_customer_id, created_at DESC, id DESC);
