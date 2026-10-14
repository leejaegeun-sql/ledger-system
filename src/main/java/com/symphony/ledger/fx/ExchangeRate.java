package com.symphony.ledger.fx;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;

/** 1 {@code base} = {@code rate} {@code quote}, from {@code effectiveFrom} until a newer row for the pair. */
public record ExchangeRate(Currency base, Currency quote, BigDecimal rate, Instant effectiveFrom) {
}
