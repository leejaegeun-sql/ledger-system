package com.symphony.ledger.common;

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
