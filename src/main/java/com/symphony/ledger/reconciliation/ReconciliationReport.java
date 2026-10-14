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
        List<Discrepancy> discrepancies) {

    public enum Status {
        BALANCED,
        DISCREPANCIES_FOUND
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
