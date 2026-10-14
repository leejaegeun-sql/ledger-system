package com.symphony.ledger.posting;

import java.time.ZoneOffset;
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
}
