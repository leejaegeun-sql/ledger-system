package com.symphony.ledger.reconciliation;

import com.symphony.ledger.common.LedgerException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.regex.Pattern;

/**
 * A UTC calendar month {@code [start, end)}: {@code 2026-09} is {@code [2026-09-01T00:00Z, 2026-10-01T00:00Z)}
 * (DESIGN.md §12.1). Stored as its first day.
 */
public record ReconciliationPeriod(YearMonth month) {

    /**
     * How long after its end a period stays open: postings that took their {@code created_at} just before
     * midnight may still be committing (DESIGN.md §12.1). The scheduled job runs at the end of it (§12.4).
     */
    public static final Duration GRACE = Duration.ofMinutes(5);

    private static final Pattern FORMAT = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");

    /** {@code YYYY-MM} with a month from 01 to 12. Anything else, or null, is 400 {@code invalid-period}. */
    public static ReconciliationPeriod parse(String value) {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw LedgerException.invalidPeriod(value);
        }
        return new ReconciliationPeriod(YearMonth.parse(value));
    }

    /** The UTC month {@code instant} falls in. */
    public static ReconciliationPeriod containing(Instant instant) {
        return new ReconciliationPeriod(YearMonth.from(instant.atOffset(ZoneOffset.UTC)));
    }

    public Instant start() {
        return firstDay().atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** Exclusive. */
    public Instant end() {
        return month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public LocalDate firstDay() {
        return month.atDay(1);
    }

    public ReconciliationPeriod previous() {
        return new ReconciliationPeriod(month.minusMonths(1));
    }

    /** When the period can first be reconciled: {@link #GRACE} after its end. */
    public Instant closesAt() {
        return end().plus(GRACE);
    }

    /** Manual and scheduled runs alike are accepted only from {@link #closesAt()}. */
    public boolean isClosedAt(Instant now) {
        return !now.isBefore(closesAt());
    }

    /** {@code YYYY-MM}. */
    @Override
    public String toString() {
        return month.toString();
    }
}
