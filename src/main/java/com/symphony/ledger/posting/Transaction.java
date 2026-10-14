package com.symphony.ledger.posting;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Transaction(
        UUID id,
        TransactionType type,
        String idempotencyKey,
        String requestHash,
        UUID reversesTransactionId,
        BigDecimal fxRate,
        Instant createdAt) {
}
