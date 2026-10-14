package com.symphony.ledger.posting;

import com.symphony.ledger.account.AccountType;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Takes the row locks for a posting (DESIGN.md §6.2). Every write path locks only through here.
 *
 * <p>The order is decided by PostgreSQL in one statement. Never sort the IDs in Java and lock them one
 * by one: {@code UUID.compareTo} is signed, PostgreSQL's uuid order is unsigned, and mixing the two
 * can deadlock.
 */
@Component
public class AccountLocker {

    private final JdbcClient jdbc;

    public AccountLocker(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Must run inside the posting transaction. Balances are read after the lock, so they are current. */
    public Map<UUID, LockedAccount> lock(Collection<UUID> ids) {
        Map<UUID, LockedAccount> locked = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT id, currency, type, balance
                FROM accounts
                WHERE id IN (:ids)
                ORDER BY id
                FOR UPDATE
                """)
                .param("ids", ids)
                .query((rs, n) -> new LockedAccount(
                        rs.getObject("id", UUID.class),
                        Currency.getInstance(rs.getString("currency")),
                        AccountType.valueOf(rs.getString("type")),
                        rs.getBigDecimal("balance")))
                .list()
                .forEach(a -> locked.put(a.id(), a));
        if (locked.size() != ids.size()) {
            throw new IllegalStateException("Expected to lock " + ids + " but found " + locked.keySet());
        }
        return locked;
    }

    public record LockedAccount(UUID id, Currency currency, AccountType type, BigDecimal balance) {
    }
}
