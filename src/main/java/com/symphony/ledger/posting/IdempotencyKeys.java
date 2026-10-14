package com.symphony.ledger.posting;

import com.symphony.ledger.common.LedgerException;

/** Validation of the {@code Idempotency-Key} header (DESIGN.md §7.1). */
final class IdempotencyKeys {

    static final String HEADER = "Idempotency-Key";

    private IdempotencyKeys() {
    }

    /** Returns the key, or throws 400 if it is missing, blank, too long or not printable ASCII. */
    static String require(String key, String operation) {
        if (key == null || key.isBlank()) {
            throw LedgerException.missingIdempotencyKey(operation);
        }
        if (key.length() > 255 || !key.chars().allMatch(c -> c >= 0x20 && c <= 0x7E)) {
            throw LedgerException.invalidIdempotencyKey(operation);
        }
        return key;
    }
}
