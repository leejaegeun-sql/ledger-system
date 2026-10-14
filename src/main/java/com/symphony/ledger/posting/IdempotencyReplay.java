package com.symphony.ledger.posting;

import java.util.UUID;

/**
 * Thrown inside a posting when the key turns out to be taken once the account locks are held
 * (DESIGN.md §7.4 step 5). Nothing has been written yet, so the rollback is free; the caller replays
 * the existing transaction or answers 409.
 */
class IdempotencyReplay extends RuntimeException {

    private final UUID transactionId;

    IdempotencyReplay(UUID transactionId) {
        super("Idempotency key already used by transaction " + transactionId, null, false, false);
        this.transactionId = transactionId;
    }

    UUID transactionId() {
        return transactionId;
    }
}
