package com.symphony.ledger.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

/**
 * Amount validation and formatting per currency (DESIGN.md §3.4, §14.2).
 *
 * <p>Some amounts here don't survive a round trip through {@code double}. Each one is checked to really lose
 * precision that way, so if any step started going through {@code double}, these tests would fail.
 */
class MoneyTest {

    private static final Currency SGD = Currency.getInstance("SGD");
    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Currency JPY = Currency.getInstance("JPY");

    @Test
    void scale_isTheCurrencysDecimalPlaces() {
        assertThat(Money.scale(SGD)).isEqualTo(2);
        assertThat(Money.scale(USD)).isEqualTo(2);
        assertThat(Money.scale(EUR)).isEqualTo(2);
        assertThat(Money.scale(JPY)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "1", "100.05", "1000000000000", "1000000000000.0000"})
    void requirePositiveWithinMax_acceptsAmountsAboveZeroUpToTenToTheTwelve(String amount) {
        assertThatCode(() -> Money.requirePositiveWithinMax(new BigDecimal(amount))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-0.01", "-100", "1000000000000.01"})
    void requirePositiveWithinMax_rejectsZeroNegativeAndAboveMax(String amount) {
        assertInvalidAmount(() -> Money.requirePositiveWithinMax(new BigDecimal(amount)));
    }

    /** Through {@code double} this is exactly 10¹², which would pass. */
    @Test
    void requirePositiveWithinMax_rejectsAnAmountJustAboveMaxThatDoubleRoundsDownToIt() {
        String amount = "1000000000000.00001";
        assertLosesPrecisionThroughDouble(amount);
        assertThat(viaDouble(amount)).isEqualByComparingTo(Money.MAX_AMOUNT);

        assertInvalidAmount(() -> Money.requirePositiveWithinMax(new BigDecimal(amount)));
    }

    @ParameterizedTest
    @CsvSource({"100.10, USD", "100.1, SGD", "0.01, EUR", "100, USD", "100.0, JPY", "100.00, JPY", "15000, JPY",
            "1E+3, JPY"})
    void requireValidScale_countsDecimalsAfterStrippingTrailingZeros(String amount, String currency) {
        assertThatCode(() -> Money.requireValidScale(new BigDecimal(amount), Currency.getInstance(currency)))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({"10.001, USD", "0.005, SGD", "1.0001, EUR", "100.5, JPY", "0.1, JPY"})
    void requireValidScale_rejectsMoreDecimalsThanTheCurrencyAllows(String amount, String currency) {
        assertInvalidAmount(() -> Money.requireValidScale(new BigDecimal(amount), Currency.getInstance(currency)));
    }

    /** Through {@code double} these become 0.3 USD and 100 JPY, which would pass. */
    @ParameterizedTest
    @CsvSource({"0.30000000000000001, USD", "100.000000000000001, JPY"})
    void requireValidScale_rejectsExtraDecimalsThatDoubleWouldDrop(String amount, String currency) {
        assertLosesPrecisionThroughDouble(amount);

        assertInvalidAmount(() -> Money.requireValidScale(new BigDecimal(amount), Currency.getInstance(currency)));
    }

    @ParameterizedTest
    @CsvSource({"74.16, USD, 74.16", "74.1600, USD, 74.16", "100.1, SGD, 100.10", "-500, SGD, -500.00",
            "0.0000, EUR, 0.00", "15000.0000, JPY, 15000", "0, JPY, 0", "1000000000000, USD, 1000000000000.00"})
    void format_padsOrTrimsZerosToTheCurrencysScale(String amount, String currency, String expected) {
        assertThat(Money.format(new BigDecimal(amount), Currency.getInstance(currency))).isEqualTo(expected);
    }

    /** It never rounds: an amount with more decimals than the currency allows is a bug upstream. */
    @Test
    void format_refusesToRound() {
        assertThatThrownBy(() -> Money.format(new BigDecimal("10.005"), USD)).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Money.format(new BigDecimal("1.5"), JPY)).isInstanceOf(ArithmeticException.class);
    }

    @ParameterizedTest
    @CsvSource({"10.001, USD, 10.001", "10.0000, USD, 10.00", "0.5, JPY, 0.5", "15000.0000, JPY, 15000"})
    void formatLenient_showsExtraDecimalsInsteadOfFailing(String amount, String currency, String expected) {
        assertThat(Money.formatLenient(new BigDecimal(amount), Currency.getInstance(currency))).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"100, 100", "100.0, 100", "100.00, 100", "1E+2, 100", "100.50, 100.5", "0.0001, 0.0001",
            "0.30000000000000001, 0.30000000000000001"})
    void normalize_stripsTrailingZerosAndNeverUsesExponents(String amount, String expected) {
        assertThat(Money.normalize(new BigDecimal(amount))).isEqualTo(expected);
    }

    private static void assertInvalidAmount(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(LedgerException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.code()).isEqualTo("invalid-amount");
        });
    }

    private static BigDecimal viaDouble(String amount) {
        return BigDecimal.valueOf(Double.parseDouble(amount));
    }

    private static void assertLosesPrecisionThroughDouble(String amount) {
        assertThat(viaDouble(amount)).as("%s through double", amount).isNotEqualByComparingTo(new BigDecimal(amount));
    }
}
