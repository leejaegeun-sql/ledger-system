package com.symphony.ledger.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.symphony.ledger.account.AccountRepository;
import com.symphony.ledger.support.FreshDatabaseTestBase;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * A posting that fails after it has started writing leaves no trace (DESIGN.md §6.1, §13 I13): the
 * transaction row, the entries and the balance updates already made are all rolled back, and the key is not
 * stored. Faults are injected by spying on the repositories, so production code has no test hooks. The
 * {@code verify} calls show each fault fired after at least one real write.
 */
class PostingRollbackIT extends FreshDatabaseTestBase {

    private static final DataAccessResourceFailureException FAULT =
            new DataAccessResourceFailureException("injected fault");

    @MockitoSpyBean
    private EntryRepository entries;

    @MockitoSpyBean
    private AccountRepository accounts;

    private UUID alice;
    private UUID bob;

    @BeforeEach
    void aliceHasOneHundred() throws Exception {
        alice = createAccount("Alice", "SGD");
        bob = createAccount("Bob", "SGD");
        fund(alice, "100.00");
        clearInvocations(entries, accounts);
    }

    /** By then the transaction row, Alice's entry and Alice's balance update have been written. */
    @Test
    void transfer_failingOnTheSecondEntry_rollsBackEverything_andTheSameKeySucceedsOnRetry() throws Exception {
        doCallRealMethod().doThrow(FAULT).when(entries).insert(any(), any(), any(), any());

        assertFailedTransferLeftNoTrace();

        verify(entries, times(2)).insert(any(), any(), any(), any());
        verify(accounts).addToBalance(eq(alice), any());
    }

    /** By then both entries and Alice's balance update have been written. */
    @Test
    void transfer_failingOnTheSecondBalanceUpdate_rollsBackEverything_andTheSameKeySucceedsOnRetry()
            throws Exception {
        doCallRealMethod().doThrow(FAULT).when(accounts).addToBalance(any(), any());

        assertFailedTransferLeftNoTrace();

        verify(entries, times(2)).insert(any(), any(), any(), any());
        verify(accounts, times(2)).addToBalance(any(), any());
    }

    /** PostingAssertions (DESIGN.md §5.6) sees Alice's entry 1.00 off, after both entries were written. */
    @Test
    void postingAssertion_entriesNotSummingToZero_returns500AndWritesNothing() throws Exception {
        doAnswer(invocation -> {
            Entry real = (Entry) invocation.callRealMethod();
            return new Entry(real.id(), real.transactionId(), real.accountId(), real.amount().add(BigDecimal.ONE),
                    real.currency(), real.createdAt());
        }).doCallRealMethod().when(entries).insert(any(), any(), any(), any());
        LedgerState before = ledgerState();
        String key = newKey();

        transfer(alice, bob, "\"30.00\"", key)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("/problems/ledger-invariant-violation"))
                .andExpect(jsonPath("$.detail").value("Entries in SGD sum to 1.00"));

        verify(entries, times(2)).insert(any(), any(), any(), any());
        assertNothingWritten(before, key);
    }

    /** PostingAssertions sees Bob's new balance as negative, after both entries and balances were written. */
    @Test
    void postingAssertion_negativeCustomerBalance_returns500AndWritesNothing() throws Exception {
        doAnswer(invocation -> ((BigDecimal) invocation.callRealMethod()).subtract(new BigDecimal("1000.00")))
                .when(accounts).addToBalance(eq(bob), any());
        LedgerState before = ledgerState();
        String key = newKey();

        transfer(alice, bob, "\"30.00\"", key)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("/problems/ledger-invariant-violation"))
                .andExpect(jsonPath("$.detail")
                        .value(startsWith("Customer account " + bob + " would have balance -970.00")));

        verify(accounts, times(2)).addToBalance(any(), any());
        assertNothingWritten(before, key);
    }

    /** Alice sends Bob 30.00, which fails with 500; then, with the fault removed, the same request succeeds. */
    private void assertFailedTransferLeftNoTrace() throws Exception {
        LedgerState before = ledgerState();
        String key = newKey();

        transfer(alice, bob, "\"30.00\"", key)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("/problems/internal-error"));

        assertNothingWritten(before, key);

        reset(entries, accounts);
        transfer(alice, bob, "\"30.00\"", key)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(TransactionController.REPLAYED_HEADER));
        assertThat(balance(alice)).isEqualByComparingTo("70.00");
        assertThat(balance(bob)).isEqualByComparingTo("30.00");
        assertThat(transactionsWithKey(key)).isEqualTo(1);
        assertIntegrityOk();
    }

    private void assertNothingWritten(LedgerState before, String key) throws Exception {
        assertThat(ledgerState()).isEqualTo(before);
        assertThat(balance(alice)).isEqualByComparingTo("100.00");
        assertThat(balance(bob)).isEqualByComparingTo("0.00");
        assertThat(transactionsWithKey(key)).isZero();
    }

    private long transactionsWithKey(String key) {
        return jdbc.sql("SELECT COUNT(*) FROM transactions WHERE idempotency_key = ?")
                .param(key)
                .query(Long.class)
                .single();
    }
}
