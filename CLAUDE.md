# Ledger service — see docs/DESIGN.md (the spec; keep code and spec in agreement)

## Commands
- `./mvnw verify` — compile + all tests (`*IT` = Testcontainers integration tests; needs Docker; JDK 21 with javac)
- `./mvnw test` — unit tests only · `./mvnw verify -Dit.test=DepositApiIT` — one IT class
- `docker compose up -d && ./mvnw spring-boot:run` — run locally (Swagger at /swagger-ui.html)

## Ledger invariants (DESIGN.md §13)
- Entries of each transaction sum to 0 per currency; per currency, all balances sum to 0.
- `accounts.balance` = Σ entries for that account, updated in the same DB transaction as the entry insert.
- Customer balances never go negative (only SYSTEM accounts may).
- Entries, transactions, snapshots, rates are append-only: fix mistakes with a reversal, never UPDATE/DELETE.
- An entry's currency is its account's currency; amounts never exceed the currency's decimal places.
- One idempotency key → at most one transaction; a transaction is reversed at most once; reversals aren't reversed.
- A reversal's entries are exactly the original's negated. Only posting rules touch system accounts.
- A rejected request leaves no trace (no rows, no stored key).
- Lock accounts only via `AccountLocker` (one `ORDER BY id FOR UPDATE` query) — never sort UUIDs in Java.

## Rules
- Never edit an existing Flyway migration, add a new one.
- Write failing tests first; run ./mvnw verify before saying a task is done.
