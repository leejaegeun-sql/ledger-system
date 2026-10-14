package com.symphony.ledger.integrity;

import com.symphony.ledger.account.AccountType;
import com.symphony.ledger.account.SystemAccounts;
import com.symphony.ledger.common.ClockConfig;
import com.symphony.ledger.common.Money;
import com.symphony.ledger.integrity.IntegrityReport.Check;
import com.symphony.ledger.integrity.IntegrityReport.CurrencyTotals;
import com.symphony.ledger.integrity.IntegrityReport.Discrepancy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** The on-demand integrity check, C1–C7 (DESIGN.md §11). Reports discrepancies, never repairs them. */
@Service
public class IntegrityService {

    private final JdbcClient jdbc;
    private final Clock clock;

    public IntegrityService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Every query reads the same REPEATABLE READ snapshot. Under READ COMMITTED, a transfer committing between
     * the balances query and the entries query would show up as a false discrepancy.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public IntegrityReport check() {
        Instant checkedAt = ClockConfig.now(clock);
        List<AccountRow> accounts = accounts();
        Map<String, BigDecimal> entrySums = entrySumsByCurrency();
        List<CurrencyTotals> currencies = new ArrayList<>();
        List<Discrepancy> discrepancies = new ArrayList<>();

        // C1, with the per-currency totals.
        for (Currency currency : Money.CURRENCIES) {
            BigDecimal net = total(accounts, currency, a -> true);
            currencies.add(new CurrencyTotals(currency.getCurrencyCode(),
                    format(total(accounts, currency, a -> a.type() == AccountType.CUSTOMER), currency),
                    format(total(accounts, currency, a -> SystemAccounts.isExternal(a.id())), currency),
                    format(total(accounts, currency, a -> SystemAccounts.isFxPool(a.id())), currency),
                    format(net, currency)));
            if (net.signum() != 0) {
                discrepancies.add(new Discrepancy(Check.CURRENCY_NOT_ZERO_SUM, null, null,
                        currency.getCurrencyCode(), null, format(net, currency), null));
            }
        }
        // C2
        for (Currency currency : Money.CURRENCIES) {
            BigDecimal sum = entrySums.getOrDefault(currency.getCurrencyCode(), BigDecimal.ZERO);
            if (sum.signum() != 0) {
                discrepancies.add(new Discrepancy(Check.CURRENCY_ENTRIES_NOT_ZERO_SUM, null, null,
                        currency.getCurrencyCode(), null, format(sum, currency), null));
            }
        }
        // C3: accounts with no entries count as 0.
        for (AccountRow a : accounts) {
            if (a.balance().compareTo(a.entriesTotal()) != 0) {
                discrepancies.add(new Discrepancy(Check.CACHED_BALANCE_MISMATCH, null, a.id(),
                        a.currency().getCurrencyCode(), format(a.entriesTotal(), a.currency()),
                        format(a.balance(), a.currency()), format(a.balance().subtract(a.entriesTotal()), a.currency())));
            }
        }
        discrepancies.addAll(unbalancedTransactions());
        // C5
        for (AccountRow a : accounts) {
            if (a.type() == AccountType.CUSTOMER && a.balance().signum() < 0) {
                discrepancies.add(new Discrepancy(Check.NEGATIVE_CUSTOMER_BALANCE, null, a.id(), null, null,
                        format(a.balance(), a.currency()), null));
            }
        }
        discrepancies.addAll(unexpectedEntryCounts());
        discrepancies.addAll(reversalMismatches());

        IntegrityReport.Status status = discrepancies.isEmpty()
                ? IntegrityReport.Status.OK
                : IntegrityReport.Status.DISCREPANCIES_FOUND;
        return new IntegrityReport(checkedAt, status, currencies, discrepancies);
    }

    private List<AccountRow> accounts() {
        return jdbc.sql("""
                SELECT a.id, a.currency, a.type, a.balance, COALESCE(e.total, 0) AS entries_total
                FROM accounts a
                LEFT JOIN (SELECT account_id, SUM(amount) AS total FROM entries GROUP BY account_id) e
                    ON e.account_id = a.id
                ORDER BY a.id
                """)
                .query((rs, n) -> new AccountRow(
                        rs.getObject("id", UUID.class),
                        Currency.getInstance(rs.getString("currency")),
                        AccountType.valueOf(rs.getString("type")),
                        rs.getBigDecimal("balance"),
                        rs.getBigDecimal("entries_total")))
                .list();
    }

    private Map<String, BigDecimal> entrySumsByCurrency() {
        return jdbc.sql("SELECT currency, SUM(amount) AS total FROM entries GROUP BY currency")
                .query((rs, n) -> Map.entry(rs.getString("currency"), rs.getBigDecimal("total")))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** C4: per transaction and currency, the entries sum to 0. */
    private List<Discrepancy> unbalancedTransactions() {
        return jdbc.sql("""
                SELECT e.transaction_id, e.currency, SUM(e.amount) AS total
                FROM entries e JOIN transactions t ON t.id = e.transaction_id
                GROUP BY e.transaction_id, e.currency, t.created_at
                HAVING SUM(e.amount) <> 0
                ORDER BY t.created_at, e.transaction_id, e.currency
                """)
                .query((rs, n) -> {
                    Currency currency = Currency.getInstance(rs.getString("currency"));
                    return new Discrepancy(Check.TRANSACTION_UNBALANCED, rs.getObject("transaction_id", UUID.class),
                            null, currency.getCurrencyCode(), null, format(rs.getBigDecimal("total"), currency), null);
                })
                .list();
    }

    /**
     * C6: DEPOSIT 2 entries; TRANSFER 2, or 4 when it has an FX rate (§5.2, §5.3); REVERSAL as many as the
     * transaction it reverses.
     */
    private List<Discrepancy> unexpectedEntryCounts() {
        return jdbc.sql("""
                SELECT id, expected, actual FROM (
                    SELECT t.id, t.created_at,
                           (SELECT COUNT(*) FROM entries e WHERE e.transaction_id = t.id) AS actual,
                           CASE
                               WHEN t.type = 'DEPOSIT' THEN 2
                               WHEN t.type = 'TRANSFER' THEN CASE WHEN t.fx_rate IS NULL THEN 2 ELSE 4 END
                               ELSE (SELECT COUNT(*) FROM entries o WHERE o.transaction_id = t.reverses_transaction_id)
                           END AS expected
                    FROM transactions t
                ) counts
                WHERE expected <> actual
                ORDER BY created_at, id
                """)
                .query((rs, n) -> new Discrepancy(Check.UNEXPECTED_ENTRY_COUNT, rs.getObject("id", UUID.class),
                        null, null, Long.toString(rs.getLong("expected")), Long.toString(rs.getLong("actual")), null))
                .list();
    }

    /**
     * C7: a reversal's entries are exactly the original's negated, matched by account. An account on only one
     * side counts as 0 on the other.
     */
    private List<Discrepancy> reversalMismatches() {
        return jdbc.sql("""
                WITH pairs AS (
                    SELECT id AS reversal_id, reverses_transaction_id AS original_id, created_at
                    FROM transactions WHERE type = 'REVERSAL'
                ),
                expected AS (
                    SELECT p.reversal_id, e.account_id, -SUM(e.amount) AS amount
                    FROM pairs p JOIN entries e ON e.transaction_id = p.original_id
                    GROUP BY p.reversal_id, e.account_id
                ),
                actual AS (
                    SELECT p.reversal_id, e.account_id, SUM(e.amount) AS amount
                    FROM pairs p JOIN entries e ON e.transaction_id = p.reversal_id
                    GROUP BY p.reversal_id, e.account_id
                )
                SELECT COALESCE(x.reversal_id, y.reversal_id) AS transaction_id,
                       COALESCE(x.account_id, y.account_id)   AS account_id,
                       a.currency,
                       COALESCE(x.amount, 0) AS expected,
                       COALESCE(y.amount, 0) AS actual
                FROM expected x
                FULL JOIN actual y ON y.reversal_id = x.reversal_id AND y.account_id = x.account_id
                JOIN accounts a ON a.id = COALESCE(x.account_id, y.account_id)
                JOIN pairs p ON p.reversal_id = COALESCE(x.reversal_id, y.reversal_id)
                WHERE COALESCE(x.amount, 0) <> COALESCE(y.amount, 0)
                ORDER BY p.created_at, 1, 2
                """)
                .query((rs, n) -> {
                    Currency currency = Currency.getInstance(rs.getString("currency"));
                    return new Discrepancy(Check.REVERSAL_MISMATCH, rs.getObject("transaction_id", UUID.class),
                            rs.getObject("account_id", UUID.class), null,
                            format(rs.getBigDecimal("expected"), currency),
                            format(rs.getBigDecimal("actual"), currency), null);
                })
                .list();
    }

    private static BigDecimal total(List<AccountRow> accounts, Currency currency, Predicate<AccountRow> filter) {
        return accounts.stream()
                .filter(a -> a.currency().equals(currency))
                .filter(filter)
                .map(AccountRow::balance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String format(BigDecimal amount, Currency currency) {
        return Money.formatLenient(amount, currency);
    }

    private record AccountRow(UUID id, Currency currency, AccountType type, BigDecimal balance,
            BigDecimal entriesTotal) {
    }
}
