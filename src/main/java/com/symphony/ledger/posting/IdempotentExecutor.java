package com.symphony.ledger.posting;

import com.symphony.ledger.common.LedgerException;
import java.util.Optional;
import java.util.function.Supplier;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Runs a posting at most once per idempotency key (DESIGN.md §7.4).
 *
 * <p>Deliberately not transactional: it sits outside the posting's {@code @Transactional} proxy, so when
 * the posting fails its database transaction has already rolled back, and the reload below runs in a new
 * one. The {@code transactions_idempotency_key_uq} constraint is what guarantees one transaction per key;
 * the lookups before and inside the posting only turn the common cases into replays early.
 */
@Component
public class IdempotentExecutor {

    static final String KEY_CONSTRAINT = "transactions_idempotency_key_uq";
    static final String REVERSES_CONSTRAINT = "transactions_reverses_uq";

    private final TransactionLookup lookup;

    public IdempotentExecutor(TransactionLookup lookup) {
        this.lookup = lookup;
    }

    public Outcome execute(String key, String requestHash, Supplier<Posting> posting) {
        Optional<Posting> existing = lookup.findByKey(key);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash);
        }
        try {
            return new Outcome(posting.get(), false);
        } catch (IdempotencyReplay e) {
            Posting stored = lookup.findById(e.transactionId())
                    .orElseThrow(() -> new IllegalStateException("Transaction " + e.transactionId() + " vanished"));
            return replay(stored, requestHash);
        } catch (DataIntegrityViolationException e) {
            String constraint = violatedConstraint(e);
            if (KEY_CONSTRAINT.equals(constraint)) {
                // Another request with this key committed while ours was in flight.
                return replay(lookup.findByKey(key).orElseThrow(() -> e), requestHash);
            }
            if (REVERSES_CONSTRAINT.equals(constraint)) {
                throw LedgerException.transactionAlreadyReversed();
            }
            throw e;
        }
    }

    /** Same request → the original result; same key with a different request → 409. */
    private static Outcome replay(Posting stored, String requestHash) {
        if (!stored.transaction().requestHash().equals(requestHash)) {
            throw LedgerException.idempotencyKeyReused();
        }
        return new Outcome(stored, true);
    }

    /** Read from the driver's error fields, never from message text. Null if it isn't a PostgreSQL error. */
    private static String violatedConstraint(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof PSQLException psql) {
                ServerErrorMessage message = psql.getServerErrorMessage();
                return message == null ? null : message.getConstraint();
            }
        }
        return null;
    }

    /** {@code replayed} is true when {@code posting} was committed by an earlier request with the same key. */
    public record Outcome(Posting posting, boolean replayed) {
    }
}
