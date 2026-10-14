package com.symphony.ledger.integrity;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The result of {@code GET /ledger/integrity} (DESIGN.md §8.11, §11). Amounts are strings at the currency's
 * scale. {@code customerTotal = −(externalTotal + fxPoolTotal)} whenever {@code net} is 0.
 */
public record IntegrityReport(Instant checkedAt, Status status, List<CurrencyTotals> currencies,
        List<Discrepancy> discrepancies) {

    public enum Status {
        OK,
        DISCREPANCIES_FOUND
    }

    public enum Check {
        CURRENCY_NOT_ZERO_SUM,
        CURRENCY_ENTRIES_NOT_ZERO_SUM,
        CACHED_BALANCE_MISMATCH,
        TRANSACTION_UNBALANCED,
        NEGATIVE_CUSTOMER_BALANCE,
        UNEXPECTED_ENTRY_COUNT,
        REVERSAL_MISMATCH
    }

    /** Sums of cached balances in one currency. */
    public record CurrencyTotals(String currency, String customerTotal, String externalTotal, String fxPoolTotal,
            String net) {
    }

    /** Each check fills in only the fields its row in §11 lists; the others are left out of the JSON. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Discrepancy(Check check, UUID transactionId, UUID accountId, String currency, String expected,
            String actual, String difference) {
    }
}
