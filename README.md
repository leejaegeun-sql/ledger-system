# Ledger Service

A double-entry ledger service. It supports customer accounts in SGD, USD, EUR and JPY, deposits, same- and
cross-currency transfers, reversals, idempotent money-moving requests, an on-demand integrity check and
month-end reconciliation.

The full specification is [docs/DESIGN.md](docs/DESIGN.md). Section references (§) below point into it.
The original brief is [docs/ledger-system-assignment.md](docs/ledger-system-assignment.md).

## Contents

- [Stack](#stack)
- [Prerequisites](#prerequisites)
- [Running](#running)
- [Running the tests](#running-the-tests)
- [API walkthrough](#api-walkthrough)
- [Design](#design)
- [Concurrency](#concurrency)
- [Idempotency](#idempotency)
- [Exchange rates](#exchange-rates)
- [Testing](#testing)
- [Assumptions](#assumptions)
- [Known limitations](#known-limitations)
- [With more time](#with-more-time)
- [How I used AI](#how-i-used-ai)

## Stack

**Spring Boot 4.1.1 (the 4.x line).** §2 says to use the latest Spring Boot 4.x GA, and to fall back to 3.5.x
only if a GA release of springdoc-openapi or Testcontainers doesn't work with it. 4.1.1 was the latest GA
release when the project was scaffolded. Both libraries have GA releases that work with it:

- springdoc-openapi 3.1.1, built against Boot 4.1;
- Testcontainers 2.0.5, whose version Boot 4.1.1 manages.

So the fallback wasn't needed, and the project uses no snapshots, milestones or overridden transitive versions.

The rest of the stack: Java 21, PostgreSQL 17, Flyway, `JdbcClient` (plain JDBC, not JPA: see §2.1),
springdoc-openapi, and JUnit 5 with Testcontainers.

## Prerequisites

- **JDK 21.** A full JDK, not just a JRE: the build needs `javac`.
- **Docker.** It runs PostgreSQL for the app, and the integration tests start their own PostgreSQL container
  with it.
- **curl** and **jq**, for the API walkthrough below.

## Running

```sh
docker compose up -d          # PostgreSQL 17 on localhost:5432 (database, user and password: ledger)
./mvnw spring-boot:run        # API on http://localhost:8080
```

Flyway creates the schema on startup and seeds the 8 system accounts and 12 exchange rates.

- Swagger UI: <http://localhost:8080/swagger-ui.html>
- OpenAPI spec: <http://localhost:8080/v3/api-docs>

The month-end reconciliation job runs at 00:06 UTC on the 1st of each month
(`ledger.reconciliation.scheduling-enabled`, `ledger.reconciliation.cron`).

To start over with an empty ledger, run `docker compose down -v`.

## Running the tests

```sh
./mvnw verify                             # compile, unit tests and all integration tests (*IT)
./mvnw test                               # unit tests only, no Docker needed
./mvnw verify -Dit.test=TransferConcurrencyIT   # one integration test class
```

The integration tests start their own PostgreSQL 17 container through Testcontainers. They don't use the
`docker compose` database, so they can run while the app is running.

## API walkthrough

> **Every request that moves money needs an `Idempotency-Key` header.**
>
> This applies to `POST /accounts/{id}/deposits`, `POST /transactions` and `POST /transactions/{id}/reversals`.
>
> - Send a new unique value (for example a UUID) for each new request.
> - Send the **same** value when you retry that request.
>
> Without the header the request is rejected with **400** `missing-idempotency-key`, and the response explains
> why. This is how the ledger guarantees that a repeated request never causes a second transfer.
>
> A retry with the same key and body gets the original **201** response again, with `Idempotent-Replayed: true`
> added. Reusing a key with a different body gets **409**.

The examples run in order in one shell, against the app started as above. Each step saves the IDs that later
steps need in shell variables. Responses are shortened, and IDs and timestamps will differ.

### 1. Create accounts

Two SGD accounts (Alice and Carol) and one USD account (Bob). Creating an account moves no money, so it takes
no idempotency key.

```sh
ALICE=$(curl -s -X POST localhost:8080/accounts \
  -H 'Content-Type: application/json' \
  -d '{"name": "Alice", "currency": "SGD"}' | jq -r .id)

CAROL=$(curl -s -X POST localhost:8080/accounts \
  -H 'Content-Type: application/json' \
  -d '{"name": "Carol", "currency": "SGD"}' | jq -r .id)

BOB=$(curl -s -X POST localhost:8080/accounts \
  -H 'Content-Type: application/json' \
  -d '{"name": "Bob", "currency": "USD"}' | jq -r .id)

curl -s localhost:8080/accounts/$ALICE | jq
```

```json
{
  "id": "1e06bef0-ed7b-4bc6-891f-013f3b34fbdb",
  "name": "Alice",
  "currency": "SGD",
  "type": "CUSTOMER",
  "balance": "0.00",
  "createdAt": "2026-10-14T13:27:43.345793Z"
}
```

### 2. Deposit

Money enters the ledger from the `EXTERNAL_SGD` system account
(`00000000-0000-0000-0000-000000000101`).

```sh
curl -s -X POST localhost:8080/accounts/$ALICE/deposits \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"amount": "500.00"}' | jq
```

```json
{
  "id": "e5f8cd62-a5c8-4543-930a-d919d14e516e",
  "type": "DEPOSIT",
  "sourceAccountId": "00000000-0000-0000-0000-000000000101",
  "destinationAccountId": "1e06bef0-ed7b-4bc6-891f-013f3b34fbdb",
  "sourceAmount": "500.00",
  "sourceCurrency": "SGD",
  "destinationAmount": "500.00",
  "destinationCurrency": "SGD",
  "fxRate": null,
  "reversesTransactionId": null,
  "reversedByTransactionId": null,
  "createdAt": "2026-10-14T13:27:43.481456Z",
  "entries": [
    { "id": 1, "accountId": "00000000-0000-0000-0000-000000000101", "amount": "-500.00", "currency": "SGD" },
    { "id": 2, "accountId": "1e06bef0-ed7b-4bc6-891f-013f3b34fbdb", "amount": "500.00", "currency": "SGD" }
  ]
}
```

### 3. Transfer

A same-currency transfer from Alice to Carol. The key is kept in `$KEY` so the next step can retry with it.

```sh
KEY=$(uuidgen)

TRANSFER=$(curl -s -X POST localhost:8080/transactions \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" \
  -d "{\"sourceAccountId\": \"$ALICE\", \"destinationAccountId\": \"$CAROL\", \"amount\": \"100.05\"}" \
  | jq -r .id)

curl -s localhost:8080/transactions/$TRANSFER | jq
```

```json
{
  "id": "ad114fca-6c47-42f0-b7d4-e8266cc3873b",
  "type": "TRANSFER",
  "sourceAmount": "100.05",
  "sourceCurrency": "SGD",
  "destinationAmount": "100.05",
  "destinationCurrency": "SGD",
  "fxRate": null,
  "reversedByTransactionId": null,
  "entries": [
    { "id": 3, "accountId": "<Alice>", "amount": "-100.05", "currency": "SGD" },
    { "id": 4, "accountId": "<Carol>", "amount": "100.05", "currency": "SGD" }
  ]
}
```

### 4. Retry with the same key: the replay

Sending the same request with the same key returns the original 201, `Location` and body, plus
`Idempotent-Replayed: true`. No second transfer is posted.

```sh
curl -s -i -X POST localhost:8080/transactions \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" \
  -d "{\"sourceAccountId\": \"$ALICE\", \"destinationAccountId\": \"$CAROL\", \"amount\": \"100.05\"}"
```

```http
HTTP/1.1 201
Location: /transactions/ad114fca-6c47-42f0-b7d4-e8266cc3873b
Idempotent-Replayed: true
Content-Type: application/json

{"id":"ad114fca-6c47-42f0-b7d4-e8266cc3873b","type":"TRANSFER", ... }
```

Alice's history still shows exactly one transfer:

```sh
curl -s "localhost:8080/accounts/$ALICE/transactions?page=0&size=20" | jq '.items[] | {type, amount}'
```

```json
{ "type": "DEPOSIT", "amount": "500.00" }
{ "type": "TRANSFER", "amount": "-100.05" }
```

The same key with a different body (for example `"amount": "1.00"`) gets 409 `idempotency-key-reused`.

### 5. Missing key: 400

```sh
curl -s -i -X POST localhost:8080/transactions \
  -H 'Content-Type: application/json' \
  -d "{\"sourceAccountId\": \"$ALICE\", \"destinationAccountId\": \"$CAROL\", \"amount\": \"100.05\"}"
```

```http
HTTP/1.1 400
Content-Type: application/problem+json

{
  "type": "/problems/missing-idempotency-key",
  "title": "Idempotency-Key header required",
  "status": 400,
  "detail": "POST /transactions moves money, so it requires an Idempotency-Key header. Send a unique value (a UUID is fine) for each new request, and send the same value again when you retry it. The ledger uses the key to make sure a request is never applied twice.",
  "instance": "/transactions",
  "header": "Idempotency-Key"
}
```

Nothing was written: no transaction and no stored key.

### 6. Reverse

Reversing the transfer posts a new `REVERSAL` transaction whose entries are the original's negated. A reversal
has no body but still needs a key.

```sh
curl -s -X POST localhost:8080/transactions/$TRANSFER/reversals \
  -H "Idempotency-Key: $(uuidgen)" | jq
```

```json
{
  "id": "0c2f1e7a-…",
  "type": "REVERSAL",
  "sourceAccountId": "<Carol>",
  "destinationAccountId": "<Alice>",
  "sourceAmount": "100.05",
  "destinationAmount": "100.05",
  "reversesTransactionId": "ad114fca-6c47-42f0-b7d4-e8266cc3873b",
  "entries": [
    { "id": 5, "accountId": "<Alice>", "amount": "100.05", "currency": "SGD" },
    { "id": 6, "accountId": "<Carol>", "amount": "-100.05", "currency": "SGD" }
  ]
}
```

`GET /transactions/$TRANSFER` now shows `reversedByTransactionId`. Reversing it again with a new key gets
409 `transaction-already-reversed`, and reversing the reversal gets 422 `reversal-not-allowed`.

### 7. Cross-currency transfer

Alice (SGD) sends 100.05 SGD to Bob (USD). The amount is always in the source currency. At the seeded
SGD→USD rate of 0.7412, 100.05 × 0.7412 = 74.157060, which rounds HALF_EVEN to **74.16 USD**. The two FX pool
system accounts carry the other side of each currency.

```sh
curl -s -X POST localhost:8080/transactions \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d "{\"sourceAccountId\": \"$ALICE\", \"destinationAccountId\": \"$BOB\", \"amount\": \"100.05\"}" | jq
```

```json
{
  "type": "TRANSFER",
  "sourceAmount": "100.05",
  "sourceCurrency": "SGD",
  "destinationAmount": "74.16",
  "destinationCurrency": "USD",
  "fxRate": "0.7412",
  "entries": [
    { "accountId": "<Alice>", "amount": "-100.05", "currency": "SGD" },
    { "accountId": "00000000-0000-0000-0000-000000000201", "amount": "100.05", "currency": "SGD" },
    { "accountId": "00000000-0000-0000-0000-000000000202", "amount": "-74.16", "currency": "USD" },
    { "accountId": "<Bob>", "amount": "74.16", "currency": "USD" }
  ]
}
```

### 8. Integrity check

Checks the whole ledger in one snapshot (§11). It returns 200 whether or not it finds discrepancies; the result
is in `status`.

```sh
curl -s localhost:8080/ledger/integrity | jq
```

```json
{
  "checkedAt": "2026-10-14T13:31:02.118374Z",
  "status": "OK",
  "currencies": [
    { "currency": "SGD", "customerTotal": "399.95", "externalTotal": "-500.00", "fxPoolTotal": "100.05", "net": "0.00" },
    { "currency": "USD", "customerTotal": "74.16", "externalTotal": "0.00", "fxPoolTotal": "-74.16", "net": "0.00" },
    { "currency": "EUR", "customerTotal": "0.00", "externalTotal": "0.00", "fxPoolTotal": "0.00", "net": "0.00" },
    { "currency": "JPY", "customerTotal": "0", "externalTotal": "0", "fxPoolTotal": "0", "net": "0" }
  ],
  "discrepancies": []
}
```

### 9. Reconciliation

Reconciles a closed UTC month (§12). A month can be reconciled from 5 minutes after it ends; the current month
gets 422 `period-not-closed`.

```sh
RECON=$(curl -s -X POST localhost:8080/reconciliations \
  -H 'Content-Type: application/json' \
  -d '{"period": "2026-09"}' | jq -r .id)

curl -s localhost:8080/reconciliations/$RECON | jq
```

```json
{
  "id": "6b0d9f3e-…",
  "period": "2026-09",
  "periodStart": "2026-09-01T00:00:00Z",
  "periodEnd": "2026-10-01T00:00:00Z",
  "trigger": "MANUAL",
  "status": "BALANCED",
  "accountsChecked": 8,
  "accountsMatched": 8,
  "accountsWithDiscrepancies": 0,
  "snapshotsCreated": 8,
  "snapshotsVerified": 0,
  "accounts": [
    {
      "accountId": "00000000-0000-0000-0000-000000000101",
      "currency": "SGD",
      "openingBalance": "0.00",
      "openingSource": "COMPUTED",
      "movement": "0.00",
      "closingBalance": "0.00",
      "snapshot": "CREATED",
      "status": "MATCHED"
    }
  ],
  "discrepancies": []
}
```

On a ledger started today, September has no activity, so the report covers only the system accounts (customer
accounts created after the period's end aren't in it). Running the same period again verifies the stored
snapshots instead of creating them (`snapshotsVerified`).

### Exchange rates

`GET /exchange-rates` returns the rate in effect now for every directed pair. `PUT /exchange-rates` sets new
rates, effective now; earlier rates are kept as history. It moves no money, so it takes no key.

```sh
curl -s localhost:8080/exchange-rates | jq '.rates[0:2]'

curl -s -X PUT localhost:8080/exchange-rates \
  -H 'Content-Type: application/json' \
  -d '{"rates": [{"base": "SGD", "quote": "EUR", "rate": "0.6820"}]}' | jq
```

```json
{
  "rates": [
    { "base": "SGD", "quote": "EUR", "rate": "0.682", "effectiveFrom": "2026-10-14T13:31:05.442153Z" }
  ]
}
```

### All endpoints

| Method and path | Purpose | Key |
|---|---|---|
| `POST /accounts` | Create a customer account | — |
| `GET /accounts/{id}` | Account and cached balance (system accounts too) | — |
| `POST /accounts/{id}/deposits` | Deposit from `EXTERNAL_<CCY>` | required |
| `GET /accounts/{id}/transactions` | Account history, oldest first, `?page=&size=` | — |
| `POST /transactions` | Transfer, same or cross currency | required |
| `GET /transactions/{id}` | Transaction with its entries | — |
| `POST /transactions/{id}/reversals` | Reverse a transaction | required |
| `GET /exchange-rates` / `PUT /exchange-rates` | Current rates / set rates | — |
| `GET /ledger/integrity` | Integrity check | — |
| `POST /reconciliations` / `GET /reconciliations/{id}` | Run / read a month-end reconciliation | — |

Errors are `application/problem+json` (RFC 9457), with `type` set to `/problems/<code>`. The codes are in §9.

## Design

### Double entry

Every movement of money is a **transaction** made of two or more **entries**. An entry is a signed amount on
one account: positive is money in, negative is money out. The entries of a transaction **sum to zero per
currency**, so money is never created or destroyed, only moved.

Money comes in from outside through system accounts, which are seeded with fixed UUIDs (§3.3):

- `EXTERNAL_<CCY>` is the source of deposits. It goes negative by exactly the amount deposited.
- `FX_POOL_<CCY>` holds the house's position in each currency. A cross-currency transfer debits the customer
  and credits the pool in the source currency, then debits the pool and credits the customer in the
  destination currency. Rounding differences stay in the pools.

As a result, every currency sums to zero across all accounts, and
`customer total = −(external total + FX pool total)`. The integrity check reports exactly those totals.

`accounts.balance` is a cached `SUM(entries.amount)`, updated in the same database transaction as the entry
insert. The integrity check and reconciliation verify it against the entries.

### Why entries are append-only

Entries, transactions, balance snapshots, exchange rates and reconciliation reports are never updated or
deleted. A mistake is corrected with a reversal, a new transaction with the original's entries negated.

- **Auditability.** The history is the record. Every balance can be explained, and recomputed, from entries
  that were never changed after they were written.
- **Reconciliation depends on it.** A month-end snapshot is only meaningful if the entries it summarises can't
  change later. If they do (for example through a backdated insert outside the app), the next reconciliation
  reports a `SNAPSHOT_MISMATCH` and the month after a `ROLLFORWARD_MISMATCH`.
- **Closed periods stay closed.** A reversal is dated when it is posted, not backdated, so a reconciled month
  never changes.
- **Simpler reads.** Account history uses offset pages that stay stable while new activity arrives, because
  rows are only ever added at the end.

The database enforces this too. Triggers reject `UPDATE`, `DELETE` and `TRUNCATE` on every append-only table
(migrations V1 and V5). A CHECK keeps customer balances non-negative, and a composite foreign key makes an
entry's currency equal its account's. These are a backstop for the application's rules, not a replacement.

The write path uses `JdbcClient` rather than JPA, so the locking SQL and the moment a unique-constraint
violation is raised are explicit (§2.1).

## Concurrency

Each money-moving operation is one `@Transactional` method on `PostingService`, at READ COMMITTED. It:

1. reads the accounts without locks and works out every account the posting touches, system accounts included;
2. locks them all in **one** statement, in a single global order ([AccountLocker.java](src/main/java/com/symphony/ledger/posting/AccountLocker.java)):

   ```sql
   SELECT id, currency, type, balance FROM accounts
   WHERE id IN (:ids)
   ORDER BY id
   FOR NO KEY UPDATE;
   ```

3. re-checks the idempotency key, checks funds and other rules against the balances it just locked, inserts the
   transaction and entries, updates the cached balances, asserts that the entries sum to zero and no customer
   balance went negative, and commits.

**Why one ordered query.** Every write path locks only through this query, so all transactions take their
locks in the same order. Wait cycles can't form, so there are no deadlocks, and concurrent transfers out of one
account are serialized with no lost updates. The order has to come from PostgreSQL: Java's `UUID.compareTo`
compares signed 64-bit halves, PostgreSQL compares unsigned bytes, and mixing the two orders can deadlock.

**Why `FOR NO KEY UPDATE`, not `FOR UPDATE`.** The first version used `FOR UPDATE`. A review found a conflict
with foreign keys:

- Inserting a row that references an account (a reconciliation's snapshot or report line) takes `FOR KEY SHARE`
  on that account for the foreign-key check.
- `FOR UPDATE` conflicts with `FOR KEY SHARE`, so the insert had to wait for any posting holding the account.
- The reconciliation runs at REPEATABLE READ. Once that posting committed, the account row had changed after
  the run's snapshot, and the insert failed with `40001 could not serialize access due to concurrent update`.

A posting only changes `balance`, which is in no key. `FOR NO KEY UPDATE` is the lock its own `UPDATE` takes
anyway. It still conflicts with itself, so postings serialize exactly as before, but it doesn't conflict with
`FOR KEY SHARE`, so reconciliation and postings no longer block each other.
[ReconciliationUnderLoadIT](src/test/java/com/symphony/ledger/reconciliation/ReconciliationUnderLoadIT.java)
reproduces that interleaving deterministically.

**Alternatives considered.**

- **Optimistic locking** (a version column, retry on conflict). There are no deadlocks to manage, but under
  contention on one account most attempts fail and retry, and the hottest accounts (the system accounts) are
  in every deposit and FX transfer. Each retry also has to re-run the idempotency check and funds check. It
  moves the hard part into retry loops without removing it.
- **Conditional UPDATE** (`UPDATE accounts SET balance = balance - :amt WHERE id = :id AND balance >= :amt`).
  This is atomic for a single debit and needs no explicit lock. But a posting touches two or four accounts and
  must check the idempotency key after waiting, so it would still need a consistent lock order across the
  `UPDATE`s, and a failed condition doesn't say why (insufficient funds or a missing row). Locking first and
  checking in Java gives one place for every rule and clear 422s.

## Idempotency

`Idempotency-Key` is **required** on every money-moving POST (§7). Without it the service can't tell a retry
from a new request.

- **Storage.** The key and a SHA-256 hash of the request are stored on the `transactions` row, under a unique
  constraint, so one key maps to at most one transaction. A rejected request stores nothing, so its key can be
  reused.
- **Request hash.** It covers the operation, the path IDs and the normalized amount (`100`, `100.0` and
  `100.00` hash the same). The same key with a different body, or reused across operations, is 409.
- **Fast path.** Before posting, the key is looked up. Same hash: replay the original 201, `Location` and body
  plus `Idempotent-Replayed: true`. The body is rebuilt from the immutable transaction and its entries, as it
  was when created.
- **Re-check after locking.** Two identical requests arriving together both miss the fast path. The second
  waits on the first's account locks, then re-checks the key and finds the committed transaction, so it
  replays. Without this re-check it would run the funds check first, and if the first request had used up the
  funds, it would get a wrong 422 instead of a replay.
- **Constraint as backstop.** If two requests with the same key touch different accounts, they don't share a
  lock; the unique index makes the second wait and then fail. `IdempotentExecutor`, which sits outside the
  `@Transactional` posting, catches the violation, reads which constraint it was from the driver, and reloads
  in a new transaction to replay or return 409.

## Exchange rates

- Rates are stored per **directed pair** (1 base = rate quote). S→D never falls back to `1 / rate(D→S)`. All 12
  pairs are seeded with illustrative rates.
- `PUT /exchange-rates` inserts new rows effective now. Old rates are kept as history.
- A cross-currency transfer reads the latest rate **inside** its transaction, after taking its locks, and
  stores it on the transaction as `fx_rate`. That stored rate is the record of what was used.
- The converted amount is `amount × rate` at full precision, rounded once with HALF_EVEN to the destination
  currency's decimal places (JPY 0, others 2). If it rounds to zero, the transfer is rejected with 422
  `amount-too-small`.
- Each cross-currency transfer has four entries, through `FX_POOL_<source>` and `FX_POOL_<destination>`, so
  each currency still sums to zero on its own.
- A reversal negates the original entries exactly, at the original amounts. It never looks up a current rate.

## Testing

`./mvnw verify` runs unit tests and Testcontainers integration tests against real PostgreSQL 17 (no H2), with
an injected mutable `Clock` so tests can cross month ends.

**What the suite proves.**

- **Posting rules.** Deposits, same-currency and FX transfers and reversals produce exactly the entries in §5,
  including the 74.16 FX example and a HALF_EVEN tie. Every 400/404/409/422 path is checked to write nothing:
  the `transactions` and `entries` row counts and every account balance, system accounts included, are the
  same before and after. JSON number amounts that a round trip through `double` would change are rejected.
- **Concurrency, with integrity OK afterwards.** Every concurrency test ends with `GET /ledger/integrity`
  returning `OK` with no discrepancies:
  - 200 random concurrent transfers between two accounts in both directions: nothing lost;
  - 10 concurrent transfers of 30.00 out of 100.00: exactly 3 succeed, the rest get 422;
  - 50 threads making same-currency and FX transfers among a few accounts: no deadlock, exact final balances;
  - 20 concurrent identical transfers: one transaction, and every caller gets the same 201;
  - the "uses up the funds" race: concurrent duplicates all replay, none get 422;
  - same key with a different body waiting on the unique index: 409 when the first commits, and the key is kept
    when the first rolls back;
  - concurrent reversals: exactly one wins.
- **Reconciliation under load.** `ReconciliationUnderLoadIT` holds a table lock so that transfers, deposits and
  integrity checks are certain to commit on the accounts a run is reporting on, after it has taken its snapshot.
  The run still returns 201 `BALANCED`, and every integrity check alongside it returns `OK`. This is the test
  that found the `FOR UPDATE` problem above.
- **Reconciliation.** Snapshots created on the first run and verified on re-runs; concurrent runs (one gets 409);
  the 5-minute close (one microsecond before 00:05 → 422, exactly 00:05 → 201); and a backdated-transaction tamper test,
  which shows a balanced, backdated insert passes the integrity check but is caught by the snapshot check, and
  by the roll-forward check the next month.
- **Integrity checks.** Each check (C1–C7) is triggered by tampering that only inserts rows or changes
  `accounts.balance`, never by disabling the append-only triggers.
- **Database guards.** `UPDATE`, `DELETE` and `TRUNCATE … CASCADE` are rejected on every append-only table, and
  every row survives. An entry whose currency differs from its account's fails the foreign key.

**Fresh databases for whole-ledger tests.** Most integration tests share one database and assert only on rows
they created. Tests that tamper with data or assert on the whole ledger (integrity, reconciliation, database
guards and all the concurrency tests) extend `FreshDatabaseTestBase`. Before each test it creates a new
database in the same container, runs the Flyway migrations, and points the app at it. A whole-ledger assertion
therefore can't be broken by another test's data, and a missing trigger could only wipe that test's own
database.

**Known test gaps.** These paths are covered by code review and by the integrity check, but have no dedicated
test (§14.2):

- **Reconciliation `entry_count` on its own.** The tamper test changes both the sum and the entry count, so a
  check that ignored the count would still pass.
- **Integrity C6 for FX transfers and reversals.** Only the deposit case (a wrong number of entries) is
  triggered.
- **Integrity C7 when an account appears on one side only.** Only differing amounts on the same accounts are
  tested.
- **A reversal racing the destination's spending.** The funds check under the lock is tested sequentially, not
  against a concurrent transfer out of the destination.
- **Same key, different body, overlapping accounts, concurrently.** The re-check after locking returns 409
  here, but only a mocked unit test (`IdempotentExecutorTest`) covers it.

## Assumptions

From §15 of the spec:

1. The ledger is an internal service. There is no authentication or authorization, and any caller may create
   accounts, move money, set rates and run reconciliations.
2. A single application instance runs. All correctness guarantees come from PostgreSQL (row locks, unique
   constraints, isolation), so several instances would still be correct for postings. The scheduled job,
   however, would run on each of them; §12.3 makes that safe, though noisy.
3. Supported currencies are fixed at SGD, USD, EUR and JPY. Adding one needs a migration (CHECK constraints,
   system accounts, rates).
4. An account's currency is set when it is created and never changes. Each account holds one currency.
5. Money enters the ledger only through deposits from `EXTERNAL_<CCY>`. Withdrawals are out of scope; the
   posting rule would be the mirror of a deposit.
6. A customer account starts at zero. There is no "opening balance" field; use a deposit.
7. "Transfer" in the assignment and `POST /transactions` are the same operation.
8. A transfer amount is always given in the source currency. The destination receives the converted, rounded
   amount.
9. The rate used is the one the transfer reads when it is posted, and it is stored on the transaction. It is
   quoted as 1 base = rate quote. There is no spread or fee, and the FX pools absorb rounding.
10. Rates can only take effect "now". Future-dated or backdated rates are not supported through the API.
11. Reversals undo the whole transaction. There are no partial reversals or edits. A "correction" is a reversal
    followed by a new transaction.
12. A reversal can fail (422) if the money has since been spent. Customer balances never go negative, including
    through a reversal.
13. A reversal is dated when it is posted, never backdated into the original transaction's period.
14. Idempotency keys are global, never expire, and are stored only for successful postings.
15. A replay returns the original status (201), `Location` and body, rendered as the transaction was when it was
    created (`reversedByTransactionId: null`). The current state is available from `GET /transactions/{id}`.
16. Account history lists entries oldest first and supports offset pagination.
17. "Timestamp" for ordering and periods means the transaction's `created_at` from the application `Clock`, not
    the database commit time. Under concurrency, history order may differ slightly from commit order.
18. Month-end means the UTC calendar month, whatever the customer's local time zone.
19. If the app is down at 00:06 UTC on the 1st, that month's scheduled reconciliation is missed. It can be run
    manually. There is no catch-up.
20. The first reconciliation of an account (no previous snapshot) uses an opening balance computed from
    entries. That is reported as `openingSource = COMPUTED` on the account's line in the report, not as a
    discrepancy.
21. The integrity check and reconciliation report discrepancies but never repair them.
22. Both run synchronously and scan everything, which is acceptable at this project's data sizes.
23. Request bodies may contain unknown JSON fields. They are ignored and are not part of the request hash.
24. The largest amount per request is 10¹² in currency units.
25. The exchange rates seeded in `V3` are illustrative, not market data.

## Known limitations

- **Hot system accounts.** System accounts are locked like any other, so all deposits in one currency
  serialize on `EXTERNAL_<CCY>`, and all FX transfers touching a currency serialize on `FX_POOL_<CCY>`. That
  is fine at this scale. The usual fix is to shard them (`EXTERNAL_SGD_0..n`) or to keep system balances off
  the hot path.
- **Shared database role.** The app connects as the role that owns the schema, because the same datasource runs
  Flyway. That role can `ALTER`, `DROP` or `DISABLE` the append-only triggers and constraints, so they guard
  against mistakes, not against that role. In production, migrations would run as a separate owner role, and
  the app's role would get only `SELECT` and `INSERT` on the ledger tables and `UPDATE (balance)` on
  `accounts`.
- **`POST /accounts` is not idempotent.** A retried create can make a duplicate account.
- **Sum-to-zero is enforced in the application only**, with the integrity check as the detector.
- **No `GET /accounts` list.** The fixed system-account UUIDs are in §3.3 instead.
- **Integrity check and reconciliation scan everything**, synchronously.
- **No catch-up** for a missed scheduled reconciliation.

## With more time

- Split the database roles as described above, so the append-only triggers protect against the app itself.
- Enforce sum-to-zero in the database too, with a `DEFERRABLE INITIALLY DEFERRED` constraint trigger that checks
  each transaction's entries at commit.
- Close the known test gaps above, starting with `entry_count` on its own and the reversal racing the
  destination's spending.
- Shard the hot system accounts, and add a load test that measures deposit and FX throughput before and after.
- Add a `balance_after` column on entries, written under the lock, for statement-style running balances and one
  more integrity check.
- An optional `Idempotency-Key` on `POST /accounts`, a `reason` on reversals, and a `GET /accounts` list.
- Catch-up for missed scheduled reconciliations, and a lock (for example a PostgreSQL advisory lock) so that
  only one instance runs the job.
- Incremental integrity checks and reconciliation (from the last snapshot onwards) instead of full scans.

## How I used AI

I used Claude Code (Claude Opus) throughout, as a pair programmer working against a written spec.

- **Spec as the source of truth.** The work is driven by [docs/DESIGN.md](docs/DESIGN.md). I made the open
  decisions myself at review (the required `Idempotency-Key`, the 201 replay, the reversal restrictions), and
  they are recorded in §16. The ledger invariants and project rules are in `CLAUDE.md`, so every session
  started from them.
- **Tests first, in small steps.** Each feature is a separate commit: failing tests, then the code, then
  `./mvnw verify`. Every commit is marked as co-authored with Claude.
- **Review.** A review of the implementation against the spec found the `FOR UPDATE` foreign-key conflict, the
  missing `TRUNCATE` triggers and several test gaps. They were fixed test-first, or recorded as known gaps.
- **What I checked myself.** The concurrency and idempotency reasoning (§6, §7.5), the posting rules, and the
  curl examples in this README, which were run against a live instance.
