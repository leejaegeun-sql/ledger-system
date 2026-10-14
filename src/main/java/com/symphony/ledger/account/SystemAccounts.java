package com.symphony.ledger.account;

import java.util.Currency;
import java.util.Map;
import java.util.UUID;

/** Fixed IDs of the system accounts seeded by V2 (DESIGN.md §3.3). */
public final class SystemAccounts {

    private static final Map<String, UUID> EXTERNAL = Map.of(
            "SGD", UUID.fromString("00000000-0000-0000-0000-000000000101"),
            "USD", UUID.fromString("00000000-0000-0000-0000-000000000102"),
            "EUR", UUID.fromString("00000000-0000-0000-0000-000000000103"),
            "JPY", UUID.fromString("00000000-0000-0000-0000-000000000104"));

    private static final Map<String, UUID> FX_POOL = Map.of(
            "SGD", UUID.fromString("00000000-0000-0000-0000-000000000201"),
            "USD", UUID.fromString("00000000-0000-0000-0000-000000000202"),
            "EUR", UUID.fromString("00000000-0000-0000-0000-000000000203"),
            "JPY", UUID.fromString("00000000-0000-0000-0000-000000000204"));

    private SystemAccounts() {
    }

    /** Source of deposits in {@code currency}. */
    public static UUID external(Currency currency) {
        return EXTERNAL.get(currency.getCurrencyCode());
    }

    /** The {@code currency} side of FX conversions. */
    public static UUID fxPool(Currency currency) {
        return FX_POOL.get(currency.getCurrencyCode());
    }

    public static boolean isExternal(UUID accountId) {
        return EXTERNAL.containsValue(accountId);
    }

    public static boolean isFxPool(UUID accountId) {
        return FX_POOL.containsValue(accountId);
    }
}
