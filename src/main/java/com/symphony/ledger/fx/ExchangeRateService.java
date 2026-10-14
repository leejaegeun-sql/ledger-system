package com.symphony.ledger.fx;

import com.symphony.ledger.common.ClockConfig;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExchangeRateService {

    private final ExchangeRateRepository rates;
    private final Clock clock;

    public ExchangeRateService(ExchangeRateRepository rates, Clock clock) {
        this.rates = rates;
        this.clock = clock;
    }

    /**
     * Inserts every pair with {@code effective_from = now}; all or none are applied (DESIGN.md §8.9).
     * Postings that read the rate after this commits use it (§10.2).
     */
    @Transactional
    public List<ExchangeRate> setRates(List<NewRate> newRates) {
        Instant now = ClockConfig.now(clock);
        List<ExchangeRate> applied = newRates.stream()
                .map(r -> new ExchangeRate(r.base(), r.quote(), r.rate(), now))
                .toList();
        applied.forEach(rate -> rates.insert(rate, now));
        return applied;
    }

    /** The rate in effect now for every pair that has one (DESIGN.md §8.10). */
    public CurrentRates current() {
        Instant now = ClockConfig.now(clock);
        return new CurrentRates(now, rates.findAllInEffectAt(now));
    }

    /** Already validated by the caller. */
    public record NewRate(Currency base, Currency quote, BigDecimal rate) {
    }

    public record CurrentRates(Instant asOf, List<ExchangeRate> rates) {
    }
}
