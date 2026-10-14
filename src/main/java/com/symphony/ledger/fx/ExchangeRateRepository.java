package com.symphony.ledger.fx;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code exchange_rates} is append-only: a new rate is a new row, and earlier rows stay as history. */
@Repository
public class ExchangeRateRepository {

    private final JdbcClient jdbc;

    public ExchangeRateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ExchangeRate rate, Instant createdAt) {
        jdbc.sql("""
                INSERT INTO exchange_rates (base, quote, rate, effective_from, created_at)
                VALUES (:base, :quote, :rate, :effectiveFrom, :createdAt)
                """)
                .param("base", rate.base().getCurrencyCode())
                .param("quote", rate.quote().getCurrencyCode())
                .param("rate", rate.rate())
                .param("effectiveFrom", rate.effectiveFrom().atOffset(ZoneOffset.UTC))
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    /**
     * For every pair with a rate in effect at {@code at}, that rate, chosen as in
     * {@link DatabaseExchangeRateProvider}. Sorted by base, then quote.
     */
    public List<ExchangeRate> findAllInEffectAt(Instant at) {
        return jdbc.sql("""
                SELECT DISTINCT ON (base, quote) base, quote, rate, effective_from
                FROM exchange_rates
                WHERE effective_from <= :at
                ORDER BY base, quote, effective_from DESC, id DESC
                """)
                .param("at", at.atOffset(ZoneOffset.UTC))
                .query((rs, n) -> new ExchangeRate(
                        Currency.getInstance(rs.getString("base")),
                        Currency.getInstance(rs.getString("quote")),
                        rs.getBigDecimal("rate"),
                        rs.getObject("effective_from", OffsetDateTime.class).toInstant()))
                .list();
    }
}
