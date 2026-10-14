package com.symphony.ledger.posting;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.Currency;
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
}
