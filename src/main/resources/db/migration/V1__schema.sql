-- Ledger schema (DESIGN.md §4)

CREATE TABLE accounts (
    id          UUID          PRIMARY KEY,
    name        VARCHAR(200)  NOT NULL,
    currency    CHAR(3)       NOT NULL CHECK (currency IN ('SGD','USD','EUR','JPY')),
    type        VARCHAR(16)   NOT NULL CHECK (type IN ('CUSTOMER','SYSTEM')),
    balance     NUMERIC(38,4) NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ   NOT NULL,
    CONSTRAINT accounts_id_currency_uq UNIQUE (id, currency),            -- target of the entries FK
    CONSTRAINT accounts_customer_non_negative CHECK (type = 'SYSTEM' OR balance >= 0)
);
CREATE UNIQUE INDEX accounts_system_name_uq ON accounts (name) WHERE type = 'SYSTEM';

CREATE TABLE transactions (
    id                       UUID           PRIMARY KEY,
    type                     VARCHAR(16)    NOT NULL CHECK (type IN ('DEPOSIT','TRANSFER','REVERSAL')),
    idempotency_key          VARCHAR(255)   NOT NULL,
    request_hash             CHAR(64)       NOT NULL,            -- hex SHA-256, see §7.3
    reverses_transaction_id  UUID           NULL REFERENCES transactions (id),
    fx_rate                  NUMERIC(20,10) NULL CHECK (fx_rate > 0),
    created_at               TIMESTAMPTZ    NOT NULL,
    CONSTRAINT transactions_idempotency_key_uq UNIQUE (idempotency_key),
    CONSTRAINT transactions_reverses_uq        UNIQUE (reverses_transaction_id),
    CONSTRAINT transactions_reversal_link CHECK ((type = 'REVERSAL') = (reverses_transaction_id IS NOT NULL)),
    CONSTRAINT transactions_fx_rate_type  CHECK (fx_rate IS NULL OR type IN ('TRANSFER','REVERSAL'))
);
CREATE INDEX transactions_created_at_idx ON transactions (created_at, id);

CREATE TABLE entries (
    id              BIGSERIAL     PRIMARY KEY,
    transaction_id  UUID          NOT NULL REFERENCES transactions (id),
    account_id      UUID          NOT NULL,
    amount          NUMERIC(38,4) NOT NULL CHECK (amount <> 0),
    currency        CHAR(3)       NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL,
    -- an entry's currency must be its account's currency
    CONSTRAINT entries_account_currency_fk FOREIGN KEY (account_id, currency)
        REFERENCES accounts (id, currency)
);
CREATE INDEX entries_account_history_idx ON entries (account_id, created_at, id);
CREATE INDEX entries_transaction_idx     ON entries (transaction_id);

CREATE TABLE exchange_rates (
    id              BIGSERIAL      PRIMARY KEY,
    base            CHAR(3)        NOT NULL CHECK (base  IN ('SGD','USD','EUR','JPY')),
    quote           CHAR(3)        NOT NULL CHECK (quote IN ('SGD','USD','EUR','JPY')),
    rate            NUMERIC(20,10) NOT NULL CHECK (rate > 0),     -- 1 base = rate quote
    effective_from  TIMESTAMPTZ    NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL,
    CHECK (base <> quote)
);
CREATE INDEX exchange_rates_lookup_idx ON exchange_rates (base, quote, effective_from DESC, id DESC);

CREATE TABLE reconciliations (
    id                   UUID        PRIMARY KEY,
    period               DATE        NOT NULL CHECK (EXTRACT(DAY FROM period) = 1),  -- first day of the month
    trigger              VARCHAR(16) NOT NULL CHECK (trigger IN ('MANUAL','SCHEDULED')),
    status               VARCHAR(32) NOT NULL CHECK (status IN ('BALANCED','DISCREPANCIES_FOUND')),
    accounts_checked     INT         NOT NULL,
    snapshots_created    INT         NOT NULL,
    snapshots_verified   INT         NOT NULL,
    discrepancy_count    INT         NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL
);
CREATE INDEX reconciliations_period_idx ON reconciliations (period, created_at);

CREATE TABLE balance_snapshots (
    id                 BIGSERIAL     PRIMARY KEY,
    account_id         UUID          NOT NULL REFERENCES accounts (id),
    period             DATE          NOT NULL CHECK (EXTRACT(DAY FROM period) = 1),
    closing_balance    NUMERIC(38,4) NOT NULL,     -- SUM(entries) with created_at < period end
    entry_count        BIGINT        NOT NULL,     -- COUNT(entries) with created_at < period end
    reconciliation_id  UUID          NOT NULL REFERENCES reconciliations (id),
    created_at         TIMESTAMPTZ   NOT NULL,
    CONSTRAINT balance_snapshots_account_period_uq UNIQUE (account_id, period)
);

CREATE TABLE reconciliation_discrepancies (
    id                 BIGSERIAL     PRIMARY KEY,
    reconciliation_id  UUID          NOT NULL REFERENCES reconciliations (id),
    account_id         UUID          NOT NULL REFERENCES accounts (id),
    kind               VARCHAR(40)   NOT NULL,
    expected           NUMERIC(38,4) NULL,
    actual             NUMERIC(38,4) NULL,
    detail             TEXT          NULL
);
CREATE INDEX reconciliation_discrepancies_rec_idx ON reconciliation_discrepancies (reconciliation_id);

-- Append-only enforcement
CREATE FUNCTION forbid_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END $$;

CREATE TRIGGER entries_append_only           BEFORE UPDATE OR DELETE ON entries           FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER transactions_append_only      BEFORE UPDATE OR DELETE ON transactions      FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER balance_snapshots_append_only BEFORE UPDATE OR DELETE ON balance_snapshots FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER exchange_rates_append_only    BEFORE UPDATE OR DELETE ON exchange_rates    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
