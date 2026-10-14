package com.symphony.ledger.posting;

import com.symphony.ledger.account.SystemAccounts;
import java.util.List;

/** A committed transaction and its entries, in entry-id order. */
public record Posting(Transaction transaction, List<Entry> entries) {

    /** Leaving out FX pool entries, the one negative entry (DESIGN.md §5.5). */
    public Entry source() {
        return single(entries.stream()
                .filter(e -> !SystemAccounts.isFxPool(e.accountId()) && e.amount().signum() < 0)
                .toList());
    }

    /** Leaving out FX pool entries, the one positive entry (DESIGN.md §5.5). */
    public Entry destination() {
        return single(entries.stream()
                .filter(e -> !SystemAccounts.isFxPool(e.accountId()) && e.amount().signum() > 0)
                .toList());
    }

    private Entry single(List<Entry> candidates) {
        if (candidates.size() != 1) {
            throw new IllegalStateException(
                    "Transaction " + transaction.id() + " does not have exactly one source and destination");
        }
        return candidates.getFirst();
    }
}
