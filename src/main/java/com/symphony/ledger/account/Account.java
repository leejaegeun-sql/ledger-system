package com.symphony.ledger.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

/** {@code balance} is the cached sum of the account's entries (DESIGN.md §3.1). */
public record Account(UUID id, String name, Currency currency, AccountType type, BigDecimal balance, Instant createdAt) {
}
