-- Append-only, completed (DESIGN.md §4, §13 I5, I12).
-- The V1 row triggers don't fire on TRUNCATE, so each append-only table also gets a statement trigger for it.
-- The reconciliation report tables become append-only too: GET /reconciliations/{id} returns a run exactly as
-- it was produced (§8.13).
-- forbid_mutation() (V1) raises '<table> is append-only' for row and statement triggers alike.

CREATE TRIGGER entries_no_truncate           BEFORE TRUNCATE ON entries           FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER transactions_no_truncate      BEFORE TRUNCATE ON transactions      FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER balance_snapshots_no_truncate BEFORE TRUNCATE ON balance_snapshots FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER exchange_rates_no_truncate    BEFORE TRUNCATE ON exchange_rates    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

CREATE TRIGGER reconciliations_append_only              BEFORE UPDATE OR DELETE ON reconciliations              FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER reconciliation_accounts_append_only      BEFORE UPDATE OR DELETE ON reconciliation_accounts      FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER reconciliation_discrepancies_append_only BEFORE UPDATE OR DELETE ON reconciliation_discrepancies FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE TRIGGER reconciliations_no_truncate              BEFORE TRUNCATE ON reconciliations              FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER reconciliation_accounts_no_truncate      BEFORE TRUNCATE ON reconciliation_accounts      FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER reconciliation_discrepancies_no_truncate BEFORE TRUNCATE ON reconciliation_discrepancies FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
