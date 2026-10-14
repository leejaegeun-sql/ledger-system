package com.symphony.ledger.posting;

import com.symphony.ledger.common.LedgerException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads committed transactions with their entries. Each method is its own short read transaction, so
 * {@link IdempotentExecutor} can call it after a posting's transaction has failed (DESIGN.md §7.4).
 */
@Service
public class TransactionLookup {

    private final TransactionRepository transactions;
    private final EntryRepository entries;

    public TransactionLookup(TransactionRepository transactions, EntryRepository entries) {
        this.transactions = transactions;
        this.entries = entries;
    }

    @Transactional(readOnly = true)
    public Optional<Posting> findByKey(String idempotencyKey) {
        return transactions.findByIdempotencyKey(idempotencyKey).map(this::withEntries);
    }

    @Transactional(readOnly = true)
    public Optional<Posting> findById(UUID id) {
        return transactions.findById(id).map(this::withEntries);
    }

    /** The current state, for {@code GET /transactions/{id}} (DESIGN.md §8.6). */
    @Transactional(readOnly = true)
    public CurrentTransaction get(UUID id) {
        Posting posting = findById(id).orElseThrow(() -> LedgerException.transactionNotFound(id));
        return new CurrentTransaction(posting, transactions.findReversalOf(id).orElse(null));
    }

    private Posting withEntries(Transaction tx) {
        return new Posting(tx, entries.findByTransactionIds(List.of(tx.id())));
    }

    /** {@code reversedByTransactionId} is null until a reversal exists. */
    public record CurrentTransaction(Posting posting, UUID reversedByTransactionId) {
    }
}
