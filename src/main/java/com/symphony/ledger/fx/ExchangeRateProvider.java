package com.symphony.ledger.fx;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Optional;

/** Directed pairs only: S→D never falls back to {@code 1 / rate(D→S)} (DESIGN.md §10.2). */
public interface ExchangeRateProvider {

    /** Rate such that 1 {@code base} = rate {@code quote}, effective at {@code at}. */
    Optional<BigDecimal> rateFor(Currency base, Currency quote, Instant at);
}
