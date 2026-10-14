package com.symphony.ledger.reconciliation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

/** One reconciliation run and what it found (DESIGN.md §12.2). Stored, never changed. */
public record ReconciliationReport(
        UUID id,
        ReconciliationPeriod period,
        Trigger trigger,
        Status status,
        int accountsChecked,
        int snapshotsCreated,
        int snapshotsVerified,
        Instant createdAt,
        List<AccountLine> accounts,
        List<Discrepancy> discrepancies) {

    public int accountsMatched() {
        return (int) accounts.stream().filter(a -> a.status() == AccountStatus.MATCHED).count();
    }

    public int accountsWithDiscrepancies() {
        return accounts.size() - accountsMatched();
    }

    public enum Status {
        BALANCED,
        DISCREPANCIES_FOUND
    }

    /** COMPUTED when there is no previous snapshot: the opening is then Σ entries before the period. */
    public enum OpeningSource {
        SNAPSHOT,
        COMPUTED
    }

    /** CREATED by this run, or an existing snapshot that this run compared (R2). */
    public enum SnapshotResult {
        CREATED,
        MATCHED,
        MISMATCHED
    }

    public enum AccountStatus {
        MATCHED,
        DISCREPANCIES_FOUND
    }

    /**
     * One account's figures for the period. {@code movement} is Σ entries inside the period, and
     * {@code closingBalance} Σ entries before its end; opening + movement = closing unless R1 failed.
     */
    public record AccountLine(UUID accountId, Currency currency, BigDecimal openingBalance,
            OpeningSource openingSource, BigDecimal movement, BigDecimal closingBalance, SnapshotResult snapshot,
            AccountStatus status) {
    }

    /** R1–R4. */
    public enum Kind {
        ROLLFORWARD_MISMATCH,
        SNAPSHOT_MISMATCH,
        CACHED_BALANCE_MISMATCH,
        NEGATIVE_CLOSING_BALANCE
    }

    /** {@code currency} is the account's, for formatting the amounts. {@code expected} may be null. */
    public record Discrepancy(Kind kind, UUID accountId, Currency currency, BigDecimal expected, BigDecimal actual,
            String detail) {
    }
}
