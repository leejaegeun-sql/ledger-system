package com.symphony.ledger.posting;

import com.symphony.ledger.common.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The transaction representation returned by every posting endpoint (DESIGN.md §8.4). */
record TransactionResponse(
        UUID id,
        TransactionType type,
        UUID sourceAccountId,
        UUID destinationAccountId,
        String sourceAmount,
        String sourceCurrency,
        String destinationAmount,
        String destinationCurrency,
        String fxRate,
        UUID reversesTransactionId,
        UUID reversedByTransactionId,
        Instant createdAt,
        List<EntryResponse> entries) {

    record EntryResponse(long id, UUID accountId, String amount, String currency) {
    }

    /** Source and destination are derived from the entries (DESIGN.md §5.5). */
    static TransactionResponse from(Posting posting, UUID reversedByTransactionId) {
        Transaction tx = posting.transaction();
        Entry source = posting.source();
        Entry destination = posting.destination();
        return new TransactionResponse(
                tx.id(),
                tx.type(),
                source.accountId(),
                destination.accountId(),
                Money.format(source.amount().negate(), source.currency()),
                source.currency().getCurrencyCode(),
                Money.format(destination.amount(), destination.currency()),
                destination.currency().getCurrencyCode(),
                tx.fxRate() == null ? null : Money.normalize(tx.fxRate()),
                tx.reversesTransactionId(),
                reversedByTransactionId,
                tx.createdAt(),
                posting.entries().stream()
                        .map(e -> new EntryResponse(e.id(), e.accountId(), Money.format(e.amount(), e.currency()),
                                e.currency().getCurrencyCode()))
                        .toList());
    }
}
