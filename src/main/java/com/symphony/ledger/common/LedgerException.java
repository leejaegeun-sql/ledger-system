package com.symphony.ledger.common;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * An error with a stable problem code, rendered as a ProblemDetail with
 * {@code type = /problems/<code>} (DESIGN.md §9).
 */
public class LedgerException extends RuntimeException {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final HttpStatus status;
    private final String code;
    private final String title;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public LedgerException(HttpStatus status, String code, String title, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
        this.title = title;
    }

    public LedgerException with(String name, Object value) {
        properties.put(name, value);
        return this;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }

    public Map<String, Object> properties() {
        return properties;
    }

    public static LedgerException accountNotFound(UUID accountId) {
        return new LedgerException(HttpStatus.NOT_FOUND, "account-not-found", "Account not found",
                "Account " + accountId + " does not exist.")
                .with("accountId", accountId);
    }

    public static LedgerException invalidAmount(String detail) {
        return new LedgerException(HttpStatus.BAD_REQUEST, "invalid-amount", "Invalid amount", detail);
    }

    public static LedgerException sameAccount(UUID accountId) {
        return new LedgerException(HttpStatus.BAD_REQUEST, "same-account", "Same source and destination",
                "Source and destination are both " + accountId + "; a transfer needs two different accounts.")
                .with("accountId", accountId);
    }

    /** {@code available} and {@code required} are shown at the currency's scale, e.g. {@code "399.95"}. */
    public static LedgerException insufficientFunds(UUID accountId, BigDecimal available, BigDecimal required,
            Currency currency) {
        String code = currency.getCurrencyCode();
        String availableText = Money.format(available, currency);
        String requiredText = Money.format(required, currency);
        return new LedgerException(HttpStatus.UNPROCESSABLE_CONTENT, "insufficient-funds", "Insufficient funds",
                "Account %s has %s %s; %s %s required.".formatted(accountId, availableText, code, requiredText, code))
                .with("accountId", accountId)
                .with("available", availableText)
                .with("required", requiredText)
                .with("currency", code);
    }

    public static LedgerException exchangeRateNotFound(Currency base, Currency quote) {
        return new LedgerException(HttpStatus.UNPROCESSABLE_CONTENT, "exchange-rate-not-found",
                "Exchange rate not found",
                "No %s→%s exchange rate is in effect.".formatted(base.getCurrencyCode(), quote.getCurrencyCode()))
                .with("base", base.getCurrencyCode())
                .with("quote", quote.getCurrencyCode());
    }

    public static LedgerException amountTooSmall(BigDecimal amount, Currency source, Currency destination,
            BigDecimal rate) {
        return new LedgerException(HttpStatus.UNPROCESSABLE_CONTENT, "amount-too-small", "Amount too small",
                "%s %s at rate %s converts to less than the smallest %s amount.".formatted(
                        Money.format(amount, source), source.getCurrencyCode(), Money.normalize(rate),
                        destination.getCurrencyCode()));
    }

    public static LedgerException systemAccountNotAllowed(UUID accountId) {
        return new LedgerException(HttpStatus.UNPROCESSABLE_CONTENT, "system-account-not-allowed",
                "System account not allowed",
                "Account " + accountId + " is a system account and cannot be used in this request.")
                .with("accountId", accountId);
    }

    /** {@code operation} is the endpoint, e.g. {@code POST /transactions}. */
    public static LedgerException missingIdempotencyKey(String operation) {
        return new LedgerException(HttpStatus.BAD_REQUEST, "missing-idempotency-key",
                "Idempotency-Key header required",
                operation + " moves money, so it requires an Idempotency-Key header. Send a unique value "
                        + "(a UUID is fine) for each new request, and send the same value again when you retry it. "
                        + "The ledger uses the key to make sure a request is never applied twice.")
                .with("header", IDEMPOTENCY_KEY_HEADER);
    }

    public static LedgerException invalidIdempotencyKey(String operation) {
        return new LedgerException(HttpStatus.BAD_REQUEST, "invalid-idempotency-key",
                "Invalid Idempotency-Key header",
                "The Idempotency-Key header on " + operation + " must be 1-255 printable ASCII characters. "
                        + "Send a unique value (a UUID is fine) for each new request, and send the same value "
                        + "again when you retry it.")
                .with("header", IDEMPOTENCY_KEY_HEADER);
    }
}
