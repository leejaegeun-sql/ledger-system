package com.symphony.ledger.posting;

import com.symphony.ledger.account.Account;
import com.symphony.ledger.account.AccountRepository;
import com.symphony.ledger.account.AccountType;
import com.symphony.ledger.account.SystemAccounts;
import com.symphony.ledger.common.ClockConfig;
import com.symphony.ledger.common.LedgerException;
import com.symphony.ledger.common.Money;
import com.symphony.ledger.fx.ExchangeRateProvider;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
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
    private final ExchangeRateProvider rates;
    private final Clock clock;

    public PostingService(AccountRepository accounts, AccountLocker locker, TransactionRepository transactions,
            EntryRepository entries, ExchangeRateProvider rates, Clock clock) {
        this.accounts = accounts;
        this.locker = locker;
        this.transactions = transactions;
        this.entries = entries;
        this.rates = rates;
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

    /**
     * Same currency: source −amt, destination +amt (DESIGN.md §5.2). Across currencies: source −amt,
     * FX_POOL_S +amt, FX_POOL_D −dAmt, destination +dAmt, at the rate in effect now (§5.3).
     * The caller has already rejected source = destination.
     */
    @Transactional
    public Posting transfer(UUID sourceId, UUID destinationId, BigDecimal amount, String idempotencyKey,
            String requestHash) {
        Account source = accounts.findById(sourceId).orElseThrow(() -> LedgerException.accountNotFound(sourceId));
        Account destination = accounts.findById(destinationId)
                .orElseThrow(() -> LedgerException.accountNotFound(destinationId));
        Money.requireValidScale(amount, source.currency());
        for (Account account : List.of(source, destination)) {
            if (account.type() == AccountType.SYSTEM) {
                throw LedgerException.systemAccountNotAllowed(account.id());
            }
        }
        Currency s = source.currency();
        Currency d = destination.currency();
        boolean fx = !s.equals(d);
        // currency and type never change, so the lock set can be worked out before locking.
        List<UUID> lockIds = fx
                ? List.of(sourceId, destinationId, SystemAccounts.fxPool(s), SystemAccounts.fxPool(d))
                : List.of(sourceId, destinationId);

        Map<UUID, AccountLocker.LockedAccount> locked = locker.lock(lockIds);

        Instant now = ClockConfig.now(clock);
        BigDecimal rate = null;
        BigDecimal destinationAmount = amount;
        if (fx) {
            rate = rates.rateFor(s, d, now).orElseThrow(() -> LedgerException.exchangeRateNotFound(s, d));
            // The only rounding point in the ledger (§3.4): full-precision product, rounded once.
            destinationAmount = amount.multiply(rate).setScale(Money.scale(d), RoundingMode.HALF_EVEN);
            if (destinationAmount.signum() == 0) {
                throw LedgerException.amountTooSmall(amount, s, d, rate);
            }
        }
        BigDecimal available = locked.get(sourceId).balance();
        if (available.compareTo(amount) < 0) {
            throw LedgerException.insufficientFunds(sourceId, available, amount, s);
        }

        Transaction tx = new Transaction(UUID.randomUUID(), TransactionType.TRANSFER, idempotencyKey, requestHash,
                null, rate, now);
        List<Leg> legs = fx
                ? List.of(new Leg(sourceId, amount.negate()),
                        new Leg(SystemAccounts.fxPool(s), amount),
                        new Leg(SystemAccounts.fxPool(d), destinationAmount.negate()),
                        new Leg(destinationId, destinationAmount))
                : List.of(new Leg(sourceId, amount.negate()), new Leg(destinationId, amount));
        return post(tx, legs, locked);
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
