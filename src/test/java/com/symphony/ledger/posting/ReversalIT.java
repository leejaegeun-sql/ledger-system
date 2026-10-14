package com.symphony.ledger.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.symphony.ledger.support.IntegrationTestBase;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;

/** {@code POST /transactions/{id}/reversals} (DESIGN.md §5.4, §8.8). */
class ReversalIT extends IntegrationTestBase {

    private static final UUID EXTERNAL_SGD = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID FX_POOL_SGD = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID FX_POOL_USD = UUID.fromString("00000000-0000-0000-0000-000000000202");

    @Test
    void reversal_restoresBalances_leavesTheOriginalUnchanged_andBothLinkToEachOther() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "SGD");
        fund(alice, "100.00");
        String originalBody = createdBody(transfer(alice, bob, "\"30.00\"", newKey()));
        UUID original = UUID.fromString(JsonPath.read(originalBody, "$.id"));
        List<List<Object>> originalRows = rowsOf(original);
        clock.advance(Duration.ofMinutes(10));

        String reversalBody = reverse(original, newKey())
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.type").value("REVERSAL"))
                .andExpect(jsonPath("$.reversesTransactionId").value(original.toString()))
                .andExpect(jsonPath("$.reversedByTransactionId").value(nullValue()))
                .andExpect(jsonPath("$.sourceAccountId").value(bob.toString()))
                .andExpect(jsonPath("$.destinationAccountId").value(alice.toString()))
                .andExpect(jsonPath("$.sourceAmount").value("30.00"))
                .andExpect(jsonPath("$.sourceCurrency").value("SGD"))
                .andExpect(jsonPath("$.destinationAmount").value("30.00"))
                .andExpect(jsonPath("$.destinationCurrency").value("SGD"))
                .andExpect(jsonPath("$.fxRate").value(nullValue()))
                .andExpect(jsonPath("$.createdAt").value("2026-10-14T06:40:00Z"))
                .andExpect(jsonPath("$.entries[*].accountId").value(contains(alice.toString(), bob.toString())))
                .andExpect(jsonPath("$.entries[*].amount").value(contains("30.00", "-30.00")))
                .andReturn().getResponse().getContentAsString();
        UUID reversal = UUID.fromString(JsonPath.read(reversalBody, "$.id"));

        assertThat(balance(alice)).isEqualByComparingTo("100.00");
        assertThat(balance(bob)).isEqualByComparingTo("0");
        assertThat(sumOfEntries(alice)).isEqualByComparingTo("100.00");
        assertThat(sumOfEntries(bob)).isEqualByComparingTo("0");
        assertThat(rowsOf(original)).isEqualTo(originalRows);

        // The original links forward; otherwise it is exactly as it was created.
        String originalNow = getTransaction(original)
                .andExpect(jsonPath("$.reversedByTransactionId").value(reversal.toString()))
                .andReturn().getResponse().getContentAsString();
        JSONAssert.assertEquals(originalBody.replace("\"reversedByTransactionId\":null",
                "\"reversedByTransactionId\":\"" + reversal + "\""), originalNow, JSONCompareMode.STRICT);
        // The reversal links back, and GET shows what the 201 showed.
        String reversalNow = getTransaction(reversal).andReturn().getResponse().getContentAsString();
        JSONAssert.assertEquals(reversalBody, reversalNow, JSONCompareMode.STRICT);
        assertThat(jdbc.sql("SELECT reverses_transaction_id FROM transactions WHERE id = ?")
                .param(reversal).query(UUID.class).single()).isEqualTo(original);
    }

    /** A reversal's source is the original's destination (DESIGN.md §5.5), here EXTERNAL_SGD. */
    @Test
    void reversingADeposit_returnsTheMoneyToTheExternalAccount() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        BigDecimal externalBefore = cachedBalance(EXTERNAL_SGD);
        UUID deposit = createdId(deposit(alice, "\"50.00\"", newKey()));

        reverse(deposit, newKey())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceAccountId").value(alice.toString()))
                .andExpect(jsonPath("$.destinationAccountId").value(EXTERNAL_SGD.toString()))
                .andExpect(jsonPath("$.sourceAmount").value("50.00"))
                .andExpect(jsonPath("$.entries[*].accountId").value(contains(EXTERNAL_SGD.toString(), alice.toString())))
                .andExpect(jsonPath("$.entries[*].amount").value(contains("50.00", "-50.00")));

        assertThat(balance(alice)).isEqualByComparingTo("0");
        assertThat(cachedBalance(EXTERNAL_SGD)).isEqualByComparingTo(externalBefore);
    }

    @Test
    void reversal_storesItsKeyAndRequestHash() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID deposit = createdId(deposit(alice, "\"10.00\"", newKey()));
        String key = newKey();

        UUID reversal = createdId(reverse(deposit, key));

        var row = jdbc.sql("SELECT idempotency_key, request_hash FROM transactions WHERE id = ?")
                .param(reversal)
                .query((rs, n) -> List.of(rs.getString(1), rs.getString(2)))
                .single();
        assertThat(row.get(0)).isEqualTo(key);
        assertThat(row.get(1)).isEqualTo(RequestHasher.sha256Hex("REVERSAL|transaction=" + deposit));
    }

    @Test
    void reversal_sameKeyTwice_replaysTheOriginal201() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "SGD");
        fund(alice, "100.00");
        UUID original = createdId(transfer(alice, bob, "\"30.00\"", newKey()));
        String key = newKey();

        MockHttpServletResponse first = reverse(original, key)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andReturn().getResponse();
        LedgerState before = ledgerState();

        MockHttpServletResponse second = reverse(original, key)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse();

        assertThat(second.getHeader("Location")).isNotNull().isEqualTo(first.getHeader("Location"));
        JSONAssert.assertEquals(first.getContentAsString(), second.getContentAsString(), JSONCompareMode.STRICT);
        assertThat(ledgerState()).isEqualTo(before);
        assertThat(balance(alice)).isEqualByComparingTo("100.00");
    }

    /** The hash covers the transaction being reversed, so the same key for another one is a different request. */
    @Test
    void reversal_keyReusedForAnotherTransaction_returns409() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID first = createdId(deposit(alice, "\"10.00\"", newKey()));
        UUID second = createdId(deposit(alice, "\"20.00\"", newKey()));
        String key = newKey();
        reverse(first, key).andExpect(status().isCreated());
        LedgerState before = ledgerState();

        reverse(second, key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/idempotency-key-reused"));

        assertThat(ledgerState()).isEqualTo(before);
        assertThat(balance(alice)).isEqualByComparingTo("20.00");
    }

    @Test
    void reversingTwiceWithDifferentKeys_returns409AndWritesNothing() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "SGD");
        fund(alice, "100.00");
        UUID original = createdId(transfer(alice, bob, "\"30.00\"", newKey()));
        reverse(original, newKey()).andExpect(status().isCreated());
        LedgerState before = ledgerState();

        reverse(original, newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/transaction-already-reversed"));

        assertThat(ledgerState()).isEqualTo(before);
        assertThat(balance(alice)).isEqualByComparingTo("100.00");
        assertThat(balance(bob)).isEqualByComparingTo("0");
    }

    @Test
    void reversingAReversal_returns422AndWritesNothing() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "SGD");
        fund(alice, "100.00");
        UUID original = createdId(transfer(alice, bob, "\"30.00\"", newKey()));
        UUID reversal = createdId(reverse(original, newKey()));
        LedgerState before = ledgerState();

        reverse(reversal, newKey())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("/problems/reversal-not-allowed"));

        assertThat(ledgerState()).isEqualTo(before);
    }

    /** Bob has already passed the money on, so taking it back would make him negative (DESIGN.md §5.4). */
    @Test
    void reversal_thatWouldMakeACustomerNegative_returns422AndWritesNothing() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "SGD");
        UUID carol = createAccount("Carol", "SGD");
        fund(alice, "100.00");
        UUID original = createdId(transfer(alice, bob, "\"30.00\"", newKey()));
        transfer(bob, carol, "\"25.00\"", newKey()).andExpect(status().isCreated());
        LedgerState before = ledgerState();
        String key = newKey();

        reverse(original, key)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("/problems/insufficient-funds"))
                .andExpect(jsonPath("$.accountId").value(bob.toString()))
                .andExpect(jsonPath("$.available").value("5.00"))
                .andExpect(jsonPath("$.required").value("30.00"))
                .andExpect(jsonPath("$.currency").value("SGD"));

        assertThat(ledgerState()).isEqualTo(before);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM transactions WHERE idempotency_key = ?")
                .param(key).query(Long.class).single()).isZero();
        getTransaction(original).andExpect(jsonPath("$.reversedByTransactionId").value(nullValue()));

        // The rejected request left no key behind, so once Bob can cover it the same key succeeds.
        fund(bob, "25.00");
        reverse(original, key)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
        assertThat(balance(alice)).isEqualByComparingTo("100.00");
        assertThat(balance(bob)).isEqualByComparingTo("0");
    }

    /**
     * The reversal posts the original's legs negated, at the original amounts and rate, even though the
     * rate has changed since (DESIGN.md §5.4, §10.2). The FX pools end where they started. Runs at a
     * far-future clock and sets its own rates, so other tests' rates don't matter.
     */
    @Test
    void fxReversal_afterTheRateChanged_postsTheOriginalAmountsAndRestoresTheFxPools() throws Exception {
        clock.set(Instant.parse("2300-01-01T00:00:00Z"));
        putRates("{\"rates\":[{\"base\":\"SGD\",\"quote\":\"USD\",\"rate\":\"0.7412\"}]}")
                .andExpect(status().isOk());
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "USD");
        fund(alice, "500.00");
        BigDecimal poolSgd = cachedBalance(FX_POOL_SGD);
        BigDecimal poolUsd = cachedBalance(FX_POOL_USD);
        UUID original = createdId(transfer(alice, bob, "\"100.05\"", newKey()));

        clock.advance(Duration.ofHours(1));
        putRates("""
                {"rates":[{"base":"SGD","quote":"USD","rate":"0.9"},{"base":"USD","quote":"SGD","rate":"1.1"}]}
                """).andExpect(status().isOk());

        reverse(original, newKey())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("REVERSAL"))
                .andExpect(jsonPath("$.fxRate").value("0.7412"))
                .andExpect(jsonPath("$.sourceAccountId").value(bob.toString()))
                .andExpect(jsonPath("$.sourceAmount").value("74.16"))
                .andExpect(jsonPath("$.sourceCurrency").value("USD"))
                .andExpect(jsonPath("$.destinationAccountId").value(alice.toString()))
                .andExpect(jsonPath("$.destinationAmount").value("100.05"))
                .andExpect(jsonPath("$.destinationCurrency").value("SGD"))
                .andExpect(jsonPath("$.createdAt").value("2300-01-01T01:00:00Z"))
                .andExpect(jsonPath("$.entries[*].accountId").value(contains(
                        alice.toString(), FX_POOL_SGD.toString(), FX_POOL_USD.toString(), bob.toString())))
                .andExpect(jsonPath("$.entries[*].amount").value(contains("100.05", "-100.05", "74.16", "-74.16")))
                .andExpect(jsonPath("$.entries[*].currency").value(contains("SGD", "SGD", "USD", "USD")));

        assertThat(balance(alice)).isEqualByComparingTo("500.00");
        assertThat(balance(bob)).isEqualByComparingTo("0");
        assertThat(cachedBalance(FX_POOL_SGD)).isEqualByComparingTo(poolSgd);
        assertThat(cachedBalance(FX_POOL_USD)).isEqualByComparingTo(poolUsd);
    }

    @Test
    void reversal_appearsInHistory_signedFromEachAccountsPointOfView() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "SGD");
        fund(alice, "100.00");
        UUID original = createdId(transfer(alice, bob, "\"30.00\"", newKey()));
        UUID reversal = createdId(reverse(original, newKey()));

        mvc.perform(withNewKey(get("/accounts/{id}/transactions", bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].transactionId").value(contains(original.toString(), reversal.toString())))
                .andExpect(jsonPath("$.items[*].amount").value(contains("30.00", "-30.00")))
                .andExpect(jsonPath("$.items[1].type").value("REVERSAL"))
                .andExpect(jsonPath("$.items[1].sourceAccountId").value(bob.toString()))
                .andExpect(jsonPath("$.items[1].destinationAccountId").value(alice.toString()))
                .andExpect(jsonPath("$.items[1].reversesTransactionId").value(original.toString()));
    }

    @Test
    void reversal_ofUnknownTransaction_returns404AndWritesNothing() throws Exception {
        UUID unknown = UUID.randomUUID();
        LedgerState before = ledgerState();

        reverse(unknown, newKey())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/problems/transaction-not-found"))
                .andExpect(jsonPath("$.transactionId").value(unknown.toString()));

        assertThat(ledgerState()).isEqualTo(before);
    }

    @Test
    void reversal_withMalformedId_returns400() throws Exception {
        mvc.perform(withNewKey(post("/transactions/{id}/reversals", "not-a-uuid")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/validation-error"));
    }

    @Test
    void reversal_withoutValidIdempotencyKey_returns400AndWritesNothing() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID deposit = createdId(deposit(alice, "\"10.00\"", newKey()));
        LedgerState before = ledgerState();

        reverse(deposit, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/missing-idempotency-key"))
                .andExpect(jsonPath("$.header").value("Idempotency-Key"))
                .andExpect(jsonPath("$.detail").value(containsString("POST /transactions/{id}/reversals moves money")));
        reverse(deposit, "x".repeat(256))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/invalid-idempotency-key"));

        assertThat(ledgerState()).isEqualTo(before);
    }

    /** The transaction row and its entry rows, every column, as stored. */
    private List<List<Object>> rowsOf(UUID transactionId) {
        List<List<Object>> rows = new ArrayList<>();
        rows.addAll(jdbc.sql("SELECT * FROM transactions WHERE id = ?")
                .param(transactionId).query((rs, n) -> columns(rs)).list());
        rows.addAll(jdbc.sql("SELECT * FROM entries WHERE transaction_id = ? ORDER BY id")
                .param(transactionId).query((rs, n) -> columns(rs)).list());
        return rows;
    }

    private static List<Object> columns(ResultSet rs) throws SQLException {
        List<Object> values = new ArrayList<>();
        for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
            values.add(rs.getObject(i));
        }
        return values;
    }

    private ResultActions getTransaction(UUID id) throws Exception {
        return mvc.perform(withNewKey(get("/transactions/{id}", id))).andExpect(status().isOk());
    }

    private static String createdBody(ResultActions posted) throws Exception {
        return posted.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }
}
