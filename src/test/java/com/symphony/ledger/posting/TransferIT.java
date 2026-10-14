package com.symphony.ledger.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.symphony.ledger.support.IntegrationTestBase;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

/** {@code POST /transactions} (DESIGN.md §5.2, §5.3, §8.5). Every request carries a fresh Idempotency-Key. */
class TransferIT extends IntegrationTestBase {

    private static final UUID EXTERNAL_SGD = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID FX_POOL_SGD = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID FX_POOL_USD = UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static final UUID FX_POOL_EUR = UUID.fromString("00000000-0000-0000-0000-000000000203");
    private static final UUID FX_POOL_JPY = UUID.fromString("00000000-0000-0000-0000-000000000204");

    @Test
    void sameCurrencyTransfer_movesBalancesAndEntriesSumToZero() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "SGD");
        fund(alice, "500.00");

        String body = transfer(alice, bob, "\"120.50\"", newKey())
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern("/transactions/[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.type").value("TRANSFER"))
                .andExpect(jsonPath("$.sourceAccountId").value(alice.toString()))
                .andExpect(jsonPath("$.destinationAccountId").value(bob.toString()))
                .andExpect(jsonPath("$.sourceAmount").value("120.50"))
                .andExpect(jsonPath("$.sourceCurrency").value("SGD"))
                .andExpect(jsonPath("$.destinationAmount").value("120.50"))
                .andExpect(jsonPath("$.destinationCurrency").value("SGD"))
                .andExpect(jsonPath("$", hasKey("fxRate")))
                .andExpect(jsonPath("$.fxRate").value(nullValue()))
                .andExpect(jsonPath("$", hasKey("reversesTransactionId")))
                .andExpect(jsonPath("$.reversesTransactionId").value(nullValue()))
                .andExpect(jsonPath("$", hasKey("reversedByTransactionId")))
                .andExpect(jsonPath("$.reversedByTransactionId").value(nullValue()))
                .andExpect(jsonPath("$.createdAt").value("2026-10-14T06:30:00Z"))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].accountId").value(alice.toString()))
                .andExpect(jsonPath("$.entries[0].amount").value("-120.50"))
                .andExpect(jsonPath("$.entries[0].currency").value("SGD"))
                .andExpect(jsonPath("$.entries[1].accountId").value(bob.toString()))
                .andExpect(jsonPath("$.entries[1].amount").value("120.50"))
                .andExpect(jsonPath("$.entries[1].currency").value("SGD"))
                .andReturn().getResponse().getContentAsString();
        UUID txId = UUID.fromString(JsonPath.read(body, "$.id"));

        assertThat(balance(alice)).isEqualByComparingTo("379.50");
        assertThat(balance(bob)).isEqualByComparingTo("120.50");
        assertThat(sumOfEntries(alice)).isEqualByComparingTo(balance(alice));
        assertThat(sumOfEntries(bob)).isEqualByComparingTo(balance(bob));
        assertThat(sumsByCurrency(txId)).containsOnlyKeys("SGD");
        assertThat(sumsByCurrency(txId).get("SGD")).isZero();
        assertThat(countEntries(txId)).isEqualTo(2);
    }

    @Test
    void transfer_acceptsNumericAmount_andTheWholeBalance() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "SGD");
        fund(alice, "30.50");

        transfer(alice, bob, "20.5", newKey())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceAmount").value("20.50"));
        transfer(alice, bob, "\"10.00\"", newKey()).andExpect(status().isCreated());

        assertThat(balance(alice)).isEqualByComparingTo("0");
        assertThat(balance(bob)).isEqualByComparingTo("30.50");
    }

    /** The FX example from DESIGN.md §5.3: 100.05 SGD at 0.7412 = 74.157060, which rounds to 74.16 USD. */
    @Test
    void fxTransfer_postsFourLegsThroughTheFxPools() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "USD");
        fund(alice, "500.00");
        BigDecimal poolSgdBefore = cachedBalance(FX_POOL_SGD);
        BigDecimal poolUsdBefore = cachedBalance(FX_POOL_USD);

        String body = transfer(alice, bob, "\"100.05\"", newKey())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("TRANSFER"))
                .andExpect(jsonPath("$.sourceAccountId").value(alice.toString()))
                .andExpect(jsonPath("$.destinationAccountId").value(bob.toString()))
                .andExpect(jsonPath("$.sourceAmount").value("100.05"))
                .andExpect(jsonPath("$.sourceCurrency").value("SGD"))
                .andExpect(jsonPath("$.destinationAmount").value("74.16"))
                .andExpect(jsonPath("$.destinationCurrency").value("USD"))
                .andExpect(jsonPath("$.fxRate").value("0.7412"))
                .andExpect(jsonPath("$.entries.length()").value(4))
                .andExpect(jsonPath("$.entries[0].accountId").value(alice.toString()))
                .andExpect(jsonPath("$.entries[0].amount").value("-100.05"))
                .andExpect(jsonPath("$.entries[0].currency").value("SGD"))
                .andExpect(jsonPath("$.entries[1].accountId").value(FX_POOL_SGD.toString()))
                .andExpect(jsonPath("$.entries[1].amount").value("100.05"))
                .andExpect(jsonPath("$.entries[1].currency").value("SGD"))
                .andExpect(jsonPath("$.entries[2].accountId").value(FX_POOL_USD.toString()))
                .andExpect(jsonPath("$.entries[2].amount").value("-74.16"))
                .andExpect(jsonPath("$.entries[2].currency").value("USD"))
                .andExpect(jsonPath("$.entries[3].accountId").value(bob.toString()))
                .andExpect(jsonPath("$.entries[3].amount").value("74.16"))
                .andExpect(jsonPath("$.entries[3].currency").value("USD"))
                .andReturn().getResponse().getContentAsString();
        UUID txId = UUID.fromString(JsonPath.read(body, "$.id"));

        assertThat(balance(alice)).isEqualByComparingTo("399.95");
        assertThat(balance(bob)).isEqualByComparingTo("74.16");
        assertThat(cachedBalance(FX_POOL_SGD)).isEqualByComparingTo(poolSgdBefore.add(new BigDecimal("100.05")));
        assertThat(cachedBalance(FX_POOL_USD)).isEqualByComparingTo(poolUsdBefore.subtract(new BigDecimal("74.16")));
        Map<String, BigDecimal> sums = sumsByCurrency(txId);
        assertThat(sums).containsOnlyKeys("SGD", "USD");
        assertThat(sums.values()).allSatisfy(sum -> assertThat(sum).isZero());
        assertThat(jdbc.sql("SELECT fx_rate FROM transactions WHERE id = ?").param(txId).query(BigDecimal.class)
                .single()).isEqualByComparingTo("0.7412");
    }

    /** USD→JPY is 150, so these land exactly on .5 JPY. HALF_UP would give 5 and 11. */
    @ParameterizedTest
    @CsvSource({"0.03, 4", "0.07, 10"})
    void fxTransfer_roundsHalfEven(String usd, String expectedJpy) throws Exception {
        UUID alice = createAccount("Alice", "USD");
        UUID yuki = createAccount("Yuki", "JPY");
        fund(alice, "1.00");

        transfer(alice, yuki, "\"" + usd + "\"", newKey())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.destinationAmount").value(expectedJpy))
                .andExpect(jsonPath("$.entries[2].accountId").value(FX_POOL_JPY.toString()))
                .andExpect(jsonPath("$.entries[2].amount").value("-" + expectedJpy));

        assertThat(balance(yuki)).isEqualByComparingTo(expectedJpy);
    }

    @Test
    void transfer_withInsufficientFunds_returns422AndWritesNothing() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "SGD");
        UUID carol = createAccount("Carol", "USD");
        fund(alice, "399.95");
        LedgerState before = state(alice, bob, carol, FX_POOL_SGD, FX_POOL_USD);

        transfer(alice, bob, "\"1000.00\"", newKey())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("/problems/insufficient-funds"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.detail").value("Account " + alice + " has 399.95 SGD; 1000.00 SGD required."))
                .andExpect(jsonPath("$.instance").value("/transactions"))
                .andExpect(jsonPath("$.accountId").value(alice.toString()))
                .andExpect(jsonPath("$.available").value("399.95"))
                .andExpect(jsonPath("$.required").value("1000.00"))
                .andExpect(jsonPath("$.currency").value("SGD"));
        transfer(alice, bob, "\"399.96\"", newKey())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("/problems/insufficient-funds"));
        transfer(alice, carol, "\"400\"", newKey())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("/problems/insufficient-funds"));

        assertThat(state(alice, bob, carol, FX_POOL_SGD, FX_POOL_USD)).isEqualTo(before);
    }

    @Test
    void transfer_toSameAccount_returns400AndWritesNothing() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        fund(alice, "100.00");
        LedgerState before = state(alice);

        transfer(alice, alice, "\"10.00\"", newKey())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/same-account"));

        assertThat(state(alice)).isEqualTo(before);
    }

    @Test
    void transfer_withUnknownAccount_returns404AndWritesNothing() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        fund(alice, "100.00");
        UUID unknownSource = UUID.randomUUID();
        UUID unknownDestination = UUID.randomUUID();
        LedgerState before = state(alice);

        transfer(unknownSource, alice, "\"10.00\"", newKey())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/problems/account-not-found"))
                .andExpect(jsonPath("$.accountId").value(unknownSource.toString()));
        transfer(alice, unknownDestination, "\"10.00\"", newKey())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/problems/account-not-found"))
                .andExpect(jsonPath("$.accountId").value(unknownDestination.toString()));
        transfer(unknownSource, unknownDestination, "\"10.00\"", newKey())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.accountId").value(unknownSource.toString()));

        assertThat(state(alice)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"0\"", "\"0.00\"", "\"-5.00\"", "-1", "\"10.001\"", "0.001", "\"1000000000000.01\""})
    void transfer_invalidAmount_returns400AndWritesNothing(String amount) throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "SGD");
        fund(alice, "100.00");
        LedgerState before = state(alice, bob);

        transfer(alice, bob, amount, newKey())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/invalid-amount"));

        assertThat(state(alice, bob)).isEqualTo(before);
    }

    /** Decimal places are checked against the source currency. */
    @Test
    void transfer_fractionalAmountFromJpyAccount_returns400() throws Exception {
        UUID yuki = createAccount("Yuki", "JPY");
        UUID alice = createAccount("Alice", "SGD");
        fund(yuki, "1000");
        LedgerState before = state(yuki, alice, FX_POOL_JPY, FX_POOL_SGD);

        transfer(yuki, alice, "\"1.5\"", newKey())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/invalid-amount"));
        assertThat(state(yuki, alice, FX_POOL_JPY, FX_POOL_SGD)).isEqualTo(before);

        transfer(yuki, alice, "\"100.0\"", newKey())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceAmount").value("100"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"destinationAccountId\":\"%2$s\",\"amount\":\"1.00\"}",
            "{\"sourceAccountId\":\"%1$s\",\"amount\":\"1.00\"}",
            "{\"sourceAccountId\":\"%1$s\",\"destinationAccountId\":\"%2$s\"}",
            "{\"sourceAccountId\":\"%1$s\",\"destinationAccountId\":\"%2$s\",\"amount\":null}",
            "{\"sourceAccountId\":\"not-a-uuid\",\"destinationAccountId\":\"%2$s\",\"amount\":\"1.00\"}",
            "{\"sourceAccountId\":\"%1$s\",\"destinationAccountId\":\"%2$s\",\"amount\":\"ten\"}",
            "{\"sourceAccountId\":\"%1$s\","
    })
    void transfer_withMissingOrMalformedField_returns400AndWritesNothing(String template) throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "SGD");
        fund(alice, "100.00");
        LedgerState before = state(alice, bob);

        mvc.perform(withNewKey(post("/transactions"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(template.formatted(alice, bob)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/validation-error"));

        assertThat(state(alice, bob)).isEqualTo(before);
    }

    /** The one place these tests leave the key out on purpose: the 400 path for a missing or bad key (§7.1). */
    @Test
    void transfer_withoutValidIdempotencyKey_returns400AndWritesNothing() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "SGD");
        fund(alice, "100.00");
        LedgerState before = state(alice, bob);

        transfer(alice, bob, "\"10.00\"", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/missing-idempotency-key"))
                .andExpect(jsonPath("$.header").value("Idempotency-Key"))
                .andExpect(jsonPath("$.detail").value(containsString("POST /transactions moves money")));
        transfer(alice, bob, "\"10.00\"", "x".repeat(256))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/invalid-idempotency-key"));

        assertThat(state(alice, bob)).isEqualTo(before);
    }

    @Test
    void transfer_involvingSystemAccount_returns422AndWritesNothing() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        fund(alice, "100.00");
        LedgerState before = state(alice, EXTERNAL_SGD, FX_POOL_SGD);

        transfer(EXTERNAL_SGD, alice, "\"10.00\"", newKey())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("/problems/system-account-not-allowed"))
                .andExpect(jsonPath("$.accountId").value(EXTERNAL_SGD.toString()));
        transfer(alice, FX_POOL_SGD, "\"10.00\"", newKey())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("/problems/system-account-not-allowed"))
                .andExpect(jsonPath("$.accountId").value(FX_POOL_SGD.toString()));
        // Request validation (400) comes before business rules (422), DESIGN.md §6.1 and §9.
        transfer(EXTERNAL_SGD, alice, "\"10.001\"", newKey())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/invalid-amount"));

        assertThat(state(alice, EXTERNAL_SGD, FX_POOL_SGD)).isEqualTo(before);
    }

    /** The seeded rates take effect at the epoch, so none is in effect just before it. */
    @Test
    void fxTransfer_withNoRateInEffect_returns422AndWritesNothing() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "USD");
        fund(alice, "100.00");
        LedgerState before = state(alice, bob, FX_POOL_SGD, FX_POOL_USD);
        clock.set(Instant.parse("1969-12-31T23:59:59Z"));

        transfer(alice, bob, "\"10.00\"", newKey())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("/problems/exchange-rate-not-found"));

        assertThat(state(alice, bob, FX_POOL_SGD, FX_POOL_USD)).isEqualTo(before);
    }

    @Test
    void fxTransfer_thatRoundsToZero_returns422AndWritesNothing() throws Exception {
        // Test-only INSERT into an append-only table (DESIGN.md §4 notes). The rate takes effect in 2099,
        // so tests running at the default clock never see it.
        Instant future = Instant.parse("2099-01-01T00:00:00Z");
        jdbc.sql("""
                INSERT INTO exchange_rates (base, quote, rate, effective_from, created_at)
                VALUES ('JPY', 'EUR', 0.004, :at, :at)
                """)
                .param("at", future.atOffset(ZoneOffset.UTC))
                .update();
        UUID yuki = createAccount("Yuki", "JPY");
        UUID eve = createAccount("Eve", "EUR");
        fund(yuki, "100");
        clock.set(future.plusSeconds(60));
        LedgerState before = state(yuki, eve, FX_POOL_JPY, FX_POOL_EUR);

        // 1 × 0.004 = 0.004 EUR, which rounds to 0.00.
        transfer(yuki, eve, "\"1\"", newKey())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("/problems/amount-too-small"));
        assertThat(state(yuki, eve, FX_POOL_JPY, FX_POOL_EUR)).isEqualTo(before);

        // 2 × 0.004 = 0.008 EUR, which rounds to 0.01.
        transfer(yuki, eve, "\"2\"", newKey())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.destinationAmount").value("0.01"))
                .andExpect(jsonPath("$.fxRate").value("0.004"));
    }

    @Test
    void transfer_storesIdempotencyKeyAndRequestHash() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "SGD");
        fund(alice, "100.00");
        String key = newKey();

        String body = transfer(alice, bob, "\"10.00\"", key)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID txId = UUID.fromString(JsonPath.read(body, "$.id"));

        var row = jdbc.sql("SELECT type, idempotency_key, request_hash FROM transactions WHERE id = ?")
                .param(txId)
                .query((rs, n) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3)))
                .single();
        assertThat(row.get(0)).isEqualTo("TRANSFER");
        assertThat(row.get(1)).isEqualTo(key);
        assertThat(row.get(2)).isEqualTo(
                RequestHasher.sha256Hex("TRANSFER|source=" + alice + "|destination=" + bob + "|amount=10"));
    }

    /** Row counts plus the cached balances of the given accounts. Equal before and after means nothing was written. */
    private record LedgerState(long transactions, long entries, List<BigDecimal> balances) {
    }

    private LedgerState state(UUID... accounts) {
        return new LedgerState(countRows("transactions"), countRows("entries"),
                Arrays.stream(accounts).map(this::cachedBalance).toList());
    }

    private BigDecimal cachedBalance(UUID accountId) {
        return jdbc.sql("SELECT balance FROM accounts WHERE id = ?").param(accountId).query(BigDecimal.class).single();
    }

    private Map<String, BigDecimal> sumsByCurrency(UUID txId) {
        return jdbc.sql("SELECT currency, SUM(amount) FROM entries WHERE transaction_id = ? GROUP BY currency")
                .param(txId)
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getBigDecimal(2)))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private long countEntries(UUID txId) {
        return jdbc.sql("SELECT COUNT(*) FROM entries WHERE transaction_id = ?").param(txId).query(Long.class).single();
    }
}
