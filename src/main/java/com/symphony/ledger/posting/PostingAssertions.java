package com.symphony.ledger.posting;

import com.symphony.ledger.account.AccountType;
import com.symphony.ledger.common.LedgerInvariantViolation;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/** Checked before every posting commits (DESIGN.md §5.6). A failure rolls the posting back. */
final class PostingAssertions {

    private PostingAssertions() {
    }

    static void verify(List<Entry> entries, Map<UUID, BigDecimal> newBalances,
            Map<UUID, AccountLocker.LockedAccount> accounts) {
        Map<String, BigDecimal> sums = entries.stream().collect(Collectors.groupingBy(
                e -> e.currency().getCurrencyCode(), TreeMap::new,
                Collectors.reducing(BigDecimal.ZERO, Entry::amount, BigDecimal::add)));
        sums.forEach((currency, sum) -> {
            if (sum.signum() != 0) {
                throw new LedgerInvariantViolation("Entries in " + currency + " sum to " + sum.toPlainString());
            }
        });
        newBalances.forEach((accountId, balance) -> {
            if (accounts.get(accountId).type() == AccountType.CUSTOMER && balance.signum() < 0) {
                throw new LedgerInvariantViolation(
                        "Customer account " + accountId + " would have balance " + balance.toPlainString());
            }
        });
    }
}
