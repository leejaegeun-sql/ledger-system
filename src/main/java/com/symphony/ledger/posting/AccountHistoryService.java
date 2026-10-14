package com.symphony.ledger.posting;

import com.symphony.ledger.account.Account;
import com.symphony.ledger.account.AccountRepository;
import com.symphony.ledger.common.LedgerException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** An account's entries with the transactions they belong to (DESIGN.md §8.7). */
@Service
public class AccountHistoryService {

    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final EntryRepository entries;

    public AccountHistoryService(AccountRepository accounts, TransactionRepository transactions,
            EntryRepository entries) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.entries = entries;
    }

    /** REPEATABLE READ so the count and the page come from the same snapshot. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AccountHistory history(UUID accountId, int page, int size) {
        Account account = accounts.findById(accountId).orElseThrow(() -> LedgerException.accountNotFound(accountId));
        long total = entries.countByAccount(accountId);
        List<Entry> mine = entries.findByAccount(accountId, (long) page * size, size);

        List<UUID> txIds = mine.stream().map(Entry::transactionId).distinct().toList();
        Map<UUID, List<Entry>> entriesByTx = entries.findByTransactionIds(txIds).stream()
                .collect(Collectors.groupingBy(Entry::transactionId));
        Map<UUID, Posting> postings = transactions.findByIds(txIds).stream()
                .map(tx -> new Posting(tx, entriesByTx.get(tx.id())))
                .collect(Collectors.toMap(p -> p.transaction().id(), Function.identity()));

        List<AccountHistory.Line> lines = mine.stream()
                .map(e -> new AccountHistory.Line(e, postings.get(e.transactionId())))
                .toList();
        return new AccountHistory(account, lines, page, size, total);
    }

    /** {@code lines} are this account's entries, each with its whole transaction. */
    public record AccountHistory(Account account, List<Line> lines, int page, int size, long totalItems) {

        public record Line(Entry entry, Posting posting) {
        }
    }
}
