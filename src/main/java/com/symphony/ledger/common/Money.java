package com.symphony.ledger.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/** Currency scale, amount validation and formatting (DESIGN.md §3.4). */
public final class Money {

    /** 10^12 currency units. */
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000000");

    private Money() {
    }

    /** Decimal places allowed for {@code currency}: SGD/USD/EUR = 2, JPY = 0. */
    public static int scale(Currency currency) {
        return currency.getDefaultFractionDigits();
    }

    /** Currency-independent checks, done with the rest of the request syntax. */
    public static void requirePositiveWithinMax(BigDecimal amount) {
        if (amount.signum() <= 0) {
            throw LedgerException.invalidAmount("Amount must be greater than 0.");
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw LedgerException.invalidAmount("Amount must not exceed " + MAX_AMOUNT.toPlainString() + ".");
        }
    }

    /** {@code "100.10"} is valid for USD and {@code "100.0"} for JPY: trailing zeros don't count. */
    public static void requireValidScale(BigDecimal amount, Currency currency) {
        if (amount.stripTrailingZeros().scale() > scale(currency)) {
            throw LedgerException.invalidAmount("%s amounts allow at most %d decimal places."
                    .formatted(currency.getCurrencyCode(), scale(currency)));
        }
    }

    /** Formats to the currency's scale, e.g. {@code "74.16"} or {@code "15000"}. Never rounds. */
    public static String format(BigDecimal amount, Currency currency) {
        return amount.setScale(scale(currency), RoundingMode.UNNECESSARY).toPlainString();
    }

    /** {@code "100"}, {@code "100.0"} and {@code 100.00} all normalize to {@code "100"}. */
    public static String normalize(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }
}
