package com.symphony.ledger.posting;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class EntryRepository {

    private final JdbcClient jdbc;

    public EntryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** All entries of a transaction share its {@code created_at}. */
    public Entry insert(Transaction tx, UUID accountId, BigDecimal amount, Currency currency) {
        long id = jdbc.sql("""
                INSERT INTO entries (transaction_id, account_id, amount, currency, created_at)
                VALUES (:txId, :accountId, :amount, :currency, :createdAt)
                RETURNING id
                """)
                .param("txId", tx.id())
                .param("accountId", accountId)
                .param("amount", amount)
                .param("currency", currency.getCurrencyCode())
                .param("createdAt", tx.createdAt().atOffset(ZoneOffset.UTC))
                .query(Long.class)
                .single();
        return new Entry(id, tx.id(), accountId, amount, currency, tx.createdAt());
    }

    /** One page of the account's entries, oldest first, entry id breaking ties (DESIGN.md §8.7). */
    public List<Entry> findByAccount(UUID accountId, long offset, int limit) {
        return jdbc.sql("""
                SELECT id, transaction_id, account_id, amount, currency, created_at
                FROM entries
                WHERE account_id = :accountId
                ORDER BY created_at, id
                LIMIT :limit OFFSET :offset
                """)
                .param("accountId", accountId)
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, n) -> map(rs))
                .list();
    }

    public long countByAccount(UUID accountId) {
        return jdbc.sql("SELECT COUNT(*) FROM entries WHERE account_id = :accountId")
                .param("accountId", accountId)
                .query(Long.class)
                .single();
    }

    /** All entries of the given transactions, in entry-id order. */
    public List<Entry> findByTransactionIds(Collection<UUID> transactionIds) {
        if (transactionIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT id, transaction_id, account_id, amount, currency, created_at
                FROM entries
                WHERE transaction_id IN (:ids)
                ORDER BY id
                """)
                .param("ids", transactionIds)
                .query((rs, n) -> map(rs))
                .list();
    }

    private static Entry map(ResultSet rs) throws SQLException {
        return new Entry(
                rs.getLong("id"),
                rs.getObject("transaction_id", UUID.class),
                rs.getObject("account_id", UUID.class),
                rs.getBigDecimal("amount"),
                Currency.getInstance(rs.getString("currency")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
