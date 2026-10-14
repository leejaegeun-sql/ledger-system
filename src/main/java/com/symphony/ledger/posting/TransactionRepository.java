package com.symphony.ledger.posting;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TransactionRepository {

    private final JdbcClient jdbc;

    public TransactionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Raises the unique violation on {@code idempotency_key} or {@code reverses_transaction_id} here (DESIGN.md §2.1). */
    public void insert(Transaction tx) {
        jdbc.sql("""
                INSERT INTO transactions
                    (id, type, idempotency_key, request_hash, reverses_transaction_id, fx_rate, created_at)
                VALUES (:id, :type, :key, :hash, :reverses, :fxRate, :createdAt)
                """)
                .param("id", tx.id())
                .param("type", tx.type().name())
                .param("key", tx.idempotencyKey())
                .param("hash", tx.requestHash())
                .param("reverses", tx.reversesTransactionId())
                .param("fxRate", tx.fxRate())
                .param("createdAt", tx.createdAt().atOffset(ZoneOffset.UTC))
                .update();
    }

    public List<Transaction> findByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT id, type, idempotency_key, request_hash, reverses_transaction_id, fx_rate, created_at
                FROM transactions
                WHERE id IN (:ids)
                """)
                .param("ids", ids)
                .query((rs, n) -> new Transaction(
                        rs.getObject("id", UUID.class),
                        TransactionType.valueOf(rs.getString("type")),
                        rs.getString("idempotency_key"),
                        rs.getString("request_hash"),
                        rs.getObject("reverses_transaction_id", UUID.class),
                        rs.getBigDecimal("fx_rate"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }
}
