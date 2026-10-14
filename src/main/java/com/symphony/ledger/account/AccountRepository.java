package com.symphony.ledger.account;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Account account) {
        jdbc.sql("""
                INSERT INTO accounts (id, name, currency, type, balance, created_at)
                VALUES (:id, :name, :currency, :type, :balance, :createdAt)
                """)
                .param("id", account.id())
                .param("name", account.name())
                .param("currency", account.currency().getCurrencyCode())
                .param("type", account.type().name())
                .param("balance", account.balance())
                .param("createdAt", account.createdAt().atOffset(ZoneOffset.UTC))
                .update();
    }

    public Optional<Account> findById(UUID id) {
        return jdbc.sql("SELECT id, name, currency, type, balance, created_at FROM accounts WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> map(rs))
                .optional();
    }

    /** Applies {@code delta} to the cached balance and returns the new balance. Call only under the row lock. */
    public BigDecimal addToBalance(UUID id, BigDecimal delta) {
        return jdbc.sql("UPDATE accounts SET balance = balance + :delta WHERE id = :id RETURNING balance")
                .param("delta", delta)
                .param("id", id)
                .query(BigDecimal.class)
                .single();
    }

    static Account map(ResultSet rs) throws SQLException {
        return new Account(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                Currency.getInstance(rs.getString("currency")),
                AccountType.valueOf(rs.getString("type")),
                rs.getBigDecimal("balance"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
