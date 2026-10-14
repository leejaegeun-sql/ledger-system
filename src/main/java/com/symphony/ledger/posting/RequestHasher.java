package com.symphony.ledger.posting;

import com.symphony.ledger.common.Money;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Canonical request hashes for idempotency (DESIGN.md §7.3). */
public final class RequestHasher {

    private RequestHasher() {
    }

    public static String deposit(UUID accountId, BigDecimal amount) {
        return sha256Hex("DEPOSIT|account=" + accountId + "|amount=" + Money.normalize(amount));
    }

    public static String transfer(UUID sourceId, UUID destinationId, BigDecimal amount) {
        return sha256Hex("TRANSFER|source=" + sourceId + "|destination=" + destinationId
                + "|amount=" + Money.normalize(amount));
    }

    public static String reversal(UUID transactionId) {
        return sha256Hex("REVERSAL|transaction=" + transactionId);
    }

    static String sha256Hex(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
