package com.symphony.ledger.common;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
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

    public static LedgerException transactionNotFound(UUID transactionId) {
        return new LedgerException(HttpStatus.NOT_FOUND, "transaction-not-found", "Transaction not found",
                "Transaction " + transactionId + " does not exist.")
                .with("transactionId", transactionId);
    }

    public static LedgerException reversalNotAllowed(UUID transactionId) {
        return new LedgerException(HttpStatus.UNPROCESSABLE_CONTENT, "reversal-not-allowed", "Reversal not allowed",
                "Transaction " + transactionId + " is itself a reversal, and reversals cannot be reversed.")
                .with("transactionId", transactionId);
    }

    /** The same shape as a bean-validation failure: {@code errors: [{field, message}]}. */
    public static LedgerException validationError(String field, String message) {
        return new LedgerException(HttpStatus.BAD_REQUEST, "validation-error", "Validation failed",
                "The request body is invalid.")
                .with("errors", List.of(Map.of("field", field, "message", message)));
    }

    public static LedgerException idempotencyKeyReused() {
        return new LedgerException(HttpStatus.CONFLICT, "idempotency-key-reused", "Idempotency-Key reused",
                "This Idempotency-Key was already used for a different request. Send the same request to "
                        + "retry it, or a new key for a new request.")
                .with("header", IDEMPOTENCY_KEY_HEADER);
    }

    public static LedgerException transactionAlreadyReversed() {
        return new LedgerException(HttpStatus.CONFLICT, "transaction-already-reversed",
                "Transaction already reversed", "The transaction has already been reversed.");
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

    /** {@code value} is what the client sent, possibly null. */
    public static LedgerException invalidPeriod(String value) {
        LedgerException e = new LedgerException(HttpStatus.BAD_REQUEST, "invalid-period", "Invalid period",
                "Period must be YYYY-MM with a month from 01 to 12, for example 2026-09.");
        return value == null ? e : e.with("period", value);
    }

    /** {@code period} and {@code current} are YYYY-MM. */
    public static LedgerException periodNotClosed(String period, String current) {
        return new LedgerException(HttpStatus.UNPROCESSABLE_CONTENT, "period-not-closed", "Period not closed",
                "Period %s has not ended. Only months before the current UTC month (%s) can be reconciled."
                        .formatted(period, current))
                .with("period", period);
    }

    public static LedgerException reconciliationNotFound(UUID reconciliationId) {
        return new LedgerException(HttpStatus.NOT_FOUND, "reconciliation-not-found", "Reconciliation not found",
                "Reconciliation " + reconciliationId + " does not exist.")
                .with("reconciliationId", reconciliationId);
    }

    /** {@code period} is YYYY-MM. */
    public static LedgerException reconciliationInProgress(String period) {
        return new LedgerException(HttpStatus.CONFLICT, "reconciliation-in-progress", "Reconciliation in progress",
                "Another reconciliation of " + period + " wrote this period's snapshots first. Retrying is safe: "
                        + "the next run verifies them.")
                .with("period", period);
    }
}
