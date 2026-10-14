package com.symphony.ledger.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.symphony.ledger.common.LedgerException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

/**
 * How {@link IdempotentExecutor} turns each outcome of a posting into a result (DESIGN.md §7.4). Through
 * the API the "already reversed" check after the locks answers first, so the reversal constraint's branch
 * (the backstop) is covered here.
 */
class IdempotentExecutorTest {

    private static final String KEY = "key-1";
    private static final String HASH = "a".repeat(64);
    private static final String OTHER_HASH = "b".repeat(64);

    private final TransactionLookup lookup = mock(TransactionLookup.class);
    private final IdempotentExecutor executor = new IdempotentExecutor(lookup);

    @Test
    void newKey_runsThePosting() {
        Posting posted = posting(HASH);
        when(lookup.findByKey(KEY)).thenReturn(Optional.empty());

        IdempotentExecutor.Outcome outcome = executor.execute(KEY, HASH, () -> posted);

        assertThat(outcome.posting()).isSameAs(posted);
        assertThat(outcome.replayed()).isFalse();
    }

    @Test
    void fastPath_sameHash_replaysWithoutRunningThePosting() {
        Posting stored = posting(HASH);
        when(lookup.findByKey(KEY)).thenReturn(Optional.of(stored));

        IdempotentExecutor.Outcome outcome = executor.execute(KEY, HASH, mustNotRun());

        assertThat(outcome.posting()).isSameAs(stored);
        assertThat(outcome.replayed()).isTrue();
    }

    @Test
    void fastPath_differentHash_is409KeyReused() {
        when(lookup.findByKey(KEY)).thenReturn(Optional.of(posting(OTHER_HASH)));

        assertLedgerError(() -> executor.execute(KEY, HASH, mustNotRun()), HttpStatus.CONFLICT, "idempotency-key-reused");
    }

    @Test
    void replayFoundAfterTheLocks_loadsThatTransactionAndReplays() {
        Posting stored = posting(HASH);
        when(lookup.findByKey(KEY)).thenReturn(Optional.empty());
        when(lookup.findById(stored.transaction().id())).thenReturn(Optional.of(stored));

        IdempotentExecutor.Outcome outcome = executor.execute(KEY, HASH, () -> {
            throw new IdempotencyReplay(stored.transaction().id());
        });

        assertThat(outcome.posting()).isSameAs(stored);
        assertThat(outcome.replayed()).isTrue();
    }

    @Test
    void replayFoundAfterTheLocks_withDifferentHash_is409KeyReused() {
        Posting stored = posting(OTHER_HASH);
        when(lookup.findByKey(KEY)).thenReturn(Optional.empty());
        when(lookup.findById(stored.transaction().id())).thenReturn(Optional.of(stored));

        assertLedgerError(() -> executor.execute(KEY, HASH, () -> {
            throw new IdempotencyReplay(stored.transaction().id());
        }), HttpStatus.CONFLICT, "idempotency-key-reused");
    }

    @Test
    void violationOfTheKeyConstraint_reloadsByKeyAndReplaysWhenTheHashMatches() {
        Posting stored = posting(HASH);
        when(lookup.findByKey(KEY)).thenReturn(Optional.empty(), Optional.of(stored));

        IdempotentExecutor.Outcome outcome = executor.execute(KEY, HASH, () -> {
            throw uniqueViolation("transactions_idempotency_key_uq");
        });

        assertThat(outcome.posting()).isSameAs(stored);
        assertThat(outcome.replayed()).isTrue();
        verify(lookup, times(2)).findByKey(KEY);
    }

    @Test
    void violationOfTheKeyConstraint_withDifferentHash_is409KeyReused() {
        when(lookup.findByKey(KEY)).thenReturn(Optional.empty(), Optional.of(posting(OTHER_HASH)));

        assertLedgerError(() -> executor.execute(KEY, HASH, () -> {
            throw uniqueViolation("transactions_idempotency_key_uq");
        }), HttpStatus.CONFLICT, "idempotency-key-reused");
    }

    /** A second reversal of the same transaction is not an idempotent replay (DESIGN.md §7.4, §16.2 item 4). */
    @Test
    void violationOfTheReversesConstraint_is409AlreadyReversed_notAReplay() {
        when(lookup.findByKey(KEY)).thenReturn(Optional.empty());

        assertLedgerError(() -> executor.execute(KEY, HASH, () -> {
            throw uniqueViolation("transactions_reverses_uq");
        }), HttpStatus.CONFLICT, "transaction-already-reversed");
        verify(lookup, times(1)).findByKey(KEY);
        verify(lookup, never()).findById(any());
    }

    @Test
    void violationOfAnyOtherConstraint_isRethrown() {
        when(lookup.findByKey(KEY)).thenReturn(Optional.empty());
        DuplicateKeyException other = uniqueViolation("accounts_system_name_uq");

        assertThatThrownBy(() -> executor.execute(KEY, HASH, () -> {
            throw other;
        })).isSameAs(other);
    }

    private static Posting posting(String hash) {
        return new Posting(new Transaction(UUID.randomUUID(), TransactionType.DEPOSIT, KEY, hash, null, null,
                Instant.parse("2026-10-14T06:30:00Z")), List.of());
    }

    /** What Spring's exception translation produces for PostgreSQL's 23505 on {@code constraint}. */
    private static DuplicateKeyException uniqueViolation(String constraint) {
        ServerErrorMessage message = new ServerErrorMessage(
                "SERROR\0C23505\0Mduplicate key value violates unique constraint\0n" + constraint + "\0");
        return new DuplicateKeyException("duplicate key", new PSQLException(message));
    }

    private static Supplier<Posting> mustNotRun() {
        return () -> {
            throw new AssertionError("the posting must not run");
        };
    }

    private static void assertLedgerError(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(LedgerException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            assertThat(e.code()).isEqualTo(code);
        });
    }
}
