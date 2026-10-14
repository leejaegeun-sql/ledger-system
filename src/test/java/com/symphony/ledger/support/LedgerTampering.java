package com.symphony.ledger.support;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The only tampering tests may do (DESIGN.md §4 notes): INSERT into the append-only tables, and UPDATE
 * {@code accounts.balance}. Never UPDATE or DELETE entries, transactions, snapshots or rates, and never
 * turn a trigger off, so the triggers run as they do in production.
 */
public final class LedgerTampering {

    private final JdbcTemplate jdbc;

    LedgerTampering(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A transaction row with a dummy idempotency key and request hash. {@code reverses} may be null. */
    public UUID insertTransaction(String type, Instant createdAt, UUID reverses) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO transactions (id, type, idempotency_key, request_hash, reverses_transaction_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, type, "tamper-" + id, "0".repeat(64), reverses, createdAt.atOffset(ZoneOffset.UTC));
        return id;
    }

    public void insertEntry(UUID transactionId, UUID accountId, String amount, String currency, Instant createdAt) {
        jdbc.update("""
                INSERT INTO entries (transaction_id, account_id, amount, currency, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, transactionId, accountId, new BigDecimal(amount), currency, createdAt.atOffset(ZoneOffset.UTC));
    }

    /** {@code UPDATE accounts SET balance = balance + delta}. */
    public void addToBalance(UUID accountId, String delta) {
        jdbc.update("UPDATE accounts SET balance = balance + ? WHERE id = ?", new BigDecimal(delta), accountId);
    }
}
