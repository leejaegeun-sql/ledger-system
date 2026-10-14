-- One line per account checked by a reconciliation run (DESIGN.md §12.2), so a stored report can be
-- returned exactly as it was created.

CREATE TABLE reconciliation_accounts (
    id                 BIGSERIAL     PRIMARY KEY,
    reconciliation_id  UUID          NOT NULL REFERENCES reconciliations (id),
    account_id         UUID          NOT NULL REFERENCES accounts (id),
    opening_balance    NUMERIC(38,4) NOT NULL,
    opening_source     VARCHAR(16)   NOT NULL CHECK (opening_source IN ('SNAPSHOT','COMPUTED')),
    movement           NUMERIC(38,4) NOT NULL,     -- SUM(entries) with periodStart <= created_at < periodEnd
    closing_balance    NUMERIC(38,4) NOT NULL,     -- SUM(entries) with created_at < periodEnd
    snapshot           VARCHAR(16)   NOT NULL CHECK (snapshot IN ('CREATED','MATCHED','MISMATCHED')),
    status             VARCHAR(32)   NOT NULL CHECK (status IN ('MATCHED','DISCREPANCIES_FOUND')),
    CONSTRAINT reconciliation_accounts_uq UNIQUE (reconciliation_id, account_id)
);
