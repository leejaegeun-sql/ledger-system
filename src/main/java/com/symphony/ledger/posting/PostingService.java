package com.symphony.ledger.posting;

import com.symphony.ledger.account.Account;
import com.symphony.ledger.account.AccountRepository;
import com.symphony.ledger.account.AccountType;
import com.symphony.ledger.account.SystemAccounts;
import com.symphony.ledger.common.ClockConfig;
import com.symphony.ledger.common.LedgerException;
import com.symphony.ledger.common.Money;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Money-moving writes. Each one is a single {@code @Transactional} method on READ COMMITTED
 * (DESIGN.md §6.1); do not switch these to REPEATABLE READ.
 */
@Service
public class PostingService {

    private final AccountRepository accounts;
    private final AccountLocker locker;
    private final TransactionRepository transactions;
    private final EntryRepository entries;
    private final Clock clock;

    public PostingService(AccountRepository accounts, AccountLocker locker, TransactionRepository transactions,
            EntryRepository entries, Clock clock) {
        this.accounts = accounts;
        this.locker = locker;
        this.transactions = transactions;
        this.entries = entries;
        this.clock = clock;
    }

    /** EXTERNAL_&lt;ccy&gt; −amt, account +amt (DESIGN.md §5.1). */
    @Transactional
    public Posting deposit(UUID accountId, BigDecimal amount, String idempotencyKey, String requestHash) {
        Account account = accounts.findById(accountId).orElseThrow(() -> LedgerException.accountNotFound(accountId));
        Money.requireValidScale(amount, account.currency());
        if (account.type() == AccountType.SYSTEM) {
            throw LedgerException.systemAccountNotAllowed(accountId);
        }
        UUID external = SystemAccounts.external(account.currency());

        Map<UUID, AccountLocker.LockedAccount> locked = locker.lock(List.of(accountId, external));

        Transaction tx = new Transaction(UUID.randomUUID(), TransactionType.DEPOSIT, idempotencyKey, requestHash,
                null, null, ClockConfig.now(clock));
        return post(tx, List.of(new Leg(external, amount.negate()), new Leg(accountId, amount)), locked);
    }

    /** Inserts the transaction and its entries, updates cached balances, then asserts the invariants. */
    private Posting post(Transaction tx, List<Leg> legs, Map<UUID, AccountLocker.LockedAccount> locked) {
        transactions.insert(tx);
        List<Entry> posted = new ArrayList<>();
        Map<UUID, BigDecimal> newBalances = new LinkedHashMap<>();
        for (Leg leg : legs) {
            posted.add(entries.insert(tx, leg.accountId(), leg.amount(), locked.get(leg.accountId()).currency()));
            newBalances.put(leg.accountId(), accounts.addToBalance(leg.accountId(), leg.amount()));
        }
        PostingAssertions.verify(posted, newBalances, locked);
        return new Posting(tx, List.copyOf(posted));
    }

    private record Leg(UUID accountId, BigDecimal amount) {
    }
}
