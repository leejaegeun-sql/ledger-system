package com.symphony.ledger.posting;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

/** A signed amount on one account: positive is money in, negative is money out (DESIGN.md §3.1). */
public record Entry(long id, UUID transactionId, UUID accountId, BigDecimal amount, Currency currency, Instant createdAt) {
}
