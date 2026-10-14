package com.symphony.ledger.fx;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The row with the greatest {@code effective_from} ≤ {@code at}, ties broken by the greatest id
 * (DESIGN.md §10.1). Called inside the posting transaction, so a concurrent rate change affects only
 * postings that read the rate after it commits.
 */
@Component
public class DatabaseExchangeRateProvider implements ExchangeRateProvider {

    private final JdbcClient jdbc;

    public DatabaseExchangeRateProvider(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<BigDecimal> rateFor(Currency base, Currency quote, Instant at) {
        return jdbc.sql("""
                SELECT rate
                FROM exchange_rates
                WHERE base = :base AND quote = :quote AND effective_from <= :at
                ORDER BY effective_from DESC, id DESC
                LIMIT 1
                """)
                .param("base", base.getCurrencyCode())
                .param("quote", quote.getCurrencyCode())
                .param("at", at.atOffset(ZoneOffset.UTC))
                .query(BigDecimal.class)
                .optional();
    }
}
