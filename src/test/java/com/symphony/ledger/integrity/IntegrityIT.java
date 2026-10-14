package com.symphony.ledger.integrity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.symphony.ledger.support.FreshDatabaseTestBase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.test.web.servlet.ResultActions;

/**
 * {@code GET /ledger/integrity} (DESIGN.md §11, §14.2). Each check is triggered only by the tampering
 * §4 allows. Every test runs on its own fresh database, so "nothing else is reported" is exact.
 */
class IntegrityIT extends FreshDatabaseTestBase {

    private static final UUID EXTERNAL_SGD = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final Instant NOW = DEFAULT_NOW;

    @Test
    void cleanLedger_isOk_withTotalsPerCurrency() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID carol = createAccount("Carol", "SGD");
        UUID bob = createAccount("Bob", "USD");
        fund(alice, "500.00");
        createdId(transfer(alice, bob, "\"100.05\"", newKey()));
        UUID local = createdId(transfer(alice, carol, "\"50.00\"", newKey()));
        createdId(reverse(local, newKey()));

        String body = integrity()
                .andExpect(jsonPath("$.checkedAt").value("2026-10-14T06:30:00Z"))
                .andExpect(jsonPath("$.status").value("OK"))
                .andReturn().getResponse().getContentAsString();

        assertThat(discrepancies(body)).isEmpty();
        // customerTotal = −(externalTotal + fxPoolTotal), and the net is 0 in every currency.
        assertThat(currencies(body)).containsExactly(
                totals("SGD", "399.95", "-500.00", "100.05", "0.00"),
                totals("USD", "74.16", "0.00", "-74.16", "0.00"),
                totals("EUR", "0.00", "0.00", "0.00", "0.00"),
                totals("JPY", "0", "0", "0", "0"));
    }

    /** C1 and C3: {@code UPDATE accounts SET balance = balance + 10} on one account. */
    @Test
    void cachedBalanceChanged_isCurrencyNotZeroSum_andCachedBalanceMismatch() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        fund(alice, "500.00");

        tamper.addToBalance(alice, "10");

        String body = integrity().andExpect(jsonPath("$.status").value("DISCREPANCIES_FOUND"))
                .andReturn().getResponse().getContentAsString();
        assertThat(discrepancies(body)).containsExactlyInAnyOrder(
                Map.of("check", "CURRENCY_NOT_ZERO_SUM", "currency", "SGD", "actual", "10.00"),
                Map.of("check", "CACHED_BALANCE_MISMATCH", "accountId", alice.toString(), "currency", "SGD",
                        "expected", "500.00", "actual", "510.00", "difference", "10.00"));
        assertThat(currencies(body)).first().isEqualTo(totals("SGD", "510.00", "-500.00", "0.00", "10.00"));
    }

    /**
     * C2 and C4: a transaction whose entries don't sum to zero. The cached balances are kept consistent
     * with the entries, so C3 stays quiet, and C1 fires because the balances no longer sum to zero either.
     */
    @Test
    void unbalancedTransaction_isCurrencyEntriesNotZeroSum_andTransactionUnbalanced() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        fund(alice, "500.00");

        UUID tx = tamper.insertTransaction("DEPOSIT", NOW, null);
        tamper.insertEntry(tx, EXTERNAL_SGD, "-10.00", "SGD", NOW);
        tamper.insertEntry(tx, alice, "15.00", "SGD", NOW);
        tamper.addToBalance(EXTERNAL_SGD, "-10.00");
        tamper.addToBalance(alice, "15.00");

        String body = integrity().andExpect(jsonPath("$.status").value("DISCREPANCIES_FOUND"))
                .andReturn().getResponse().getContentAsString();
        assertThat(discrepancies(body)).containsExactlyInAnyOrder(
                Map.of("check", "CURRENCY_NOT_ZERO_SUM", "currency", "SGD", "actual", "5.00"),
                Map.of("check", "CURRENCY_ENTRIES_NOT_ZERO_SUM", "currency", "SGD", "actual", "5.00"),
                Map.of("check", "TRANSACTION_UNBALANCED", "transactionId", tx.toString(), "currency", "SGD",
                        "actual", "5.00"));
    }

    /** C6: a DEPOSIT with three entries that still sum to zero. */
    @Test
    void depositWithThreeEntries_isUnexpectedEntryCount() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "SGD");
        fund(alice, "500.00");

        UUID tx = tamper.insertTransaction("DEPOSIT", NOW, null);
        tamper.insertEntry(tx, EXTERNAL_SGD, "-10.00", "SGD", NOW);
        tamper.insertEntry(tx, alice, "6.00", "SGD", NOW);
        tamper.insertEntry(tx, bob, "4.00", "SGD", NOW);
        tamper.addToBalance(EXTERNAL_SGD, "-10.00");
        tamper.addToBalance(alice, "6.00");
        tamper.addToBalance(bob, "4.00");

        String body = integrity().andExpect(jsonPath("$.status").value("DISCREPANCIES_FOUND"))
                .andReturn().getResponse().getContentAsString();
        assertThat(discrepancies(body)).containsExactly(
                Map.of("check", "UNEXPECTED_ENTRY_COUNT", "transactionId", tx.toString(),
                        "expected", "2", "actual", "3"));
    }

    /** C7: a REVERSAL whose amounts differ from the original's (same accounts, same count, still balanced). */
    @Test
    void reversalWithDifferentAmounts_isReversalMismatch() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "SGD");
        fund(alice, "100.00");
        UUID original = createdId(transfer(alice, bob, "\"30.00\"", newKey()));

        UUID reversal = tamper.insertTransaction("REVERSAL", NOW, original);
        tamper.insertEntry(reversal, alice, "25.00", "SGD", NOW);
        tamper.insertEntry(reversal, bob, "-25.00", "SGD", NOW);
        tamper.addToBalance(alice, "25.00");
        tamper.addToBalance(bob, "-25.00");

        String body = integrity().andExpect(jsonPath("$.status").value("DISCREPANCIES_FOUND"))
                .andReturn().getResponse().getContentAsString();
        assertThat(discrepancies(body)).containsExactlyInAnyOrder(
                Map.of("check", "REVERSAL_MISMATCH", "transactionId", reversal.toString(),
                        "accountId", alice.toString(), "expected", "30.00", "actual", "25.00"),
                Map.of("check", "REVERSAL_MISMATCH", "transactionId", reversal.toString(),
                        "accountId", bob.toString(), "expected", "-30.00", "actual", "-25.00"));
    }

    /** The append-only trigger rejects both, so the ledger is untouched and still clean. */
    @Test
    void updatingOrDeletingAnEntry_getsTheTriggersException() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        fund(alice, "500.00");
        long entryId = jdbcTemplate.queryForObject("SELECT id FROM entries WHERE account_id = ?", Long.class, alice);

        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE entries SET amount = amount + 10 WHERE id = ?", entryId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("entries is append-only");
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM entries WHERE id = ?", entryId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("entries is append-only");

        assertThat(sumOfEntries(alice)).isEqualByComparingTo("500.00");
        integrity().andExpect(jsonPath("$.status").value("OK"));
    }

    private ResultActions integrity() throws Exception {
        return mvc.perform(get("/ledger/integrity")).andExpect(status().isOk());
    }

    static List<Map<String, Object>> discrepancies(String body) {
        return JsonPath.read(body, "$.discrepancies");
    }

    static List<Map<String, Object>> currencies(String body) {
        return JsonPath.read(body, "$.currencies");
    }

    static Map<String, Object> totals(String currency, String customer, String external, String fxPool, String net) {
        return Map.of("currency", currency, "customerTotal", customer, "externalTotal", external,
                "fxPoolTotal", fxPool, "net", net);
    }
}
