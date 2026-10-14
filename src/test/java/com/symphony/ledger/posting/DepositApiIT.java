package com.symphony.ledger.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.symphony.ledger.support.IntegrationTestBase;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DepositApiIT extends IntegrationTestBase {

    private static final String EXTERNAL_SGD = "00000000-0000-0000-0000-000000000101";

    @Test
    void deposit_updatesBalance() throws Exception {
        UUID alice = createAccount("Alice", "SGD");

        deposit(alice, "\"500.00\"", newKey()).andExpect(status().isCreated());
        deposit(alice, "20.5", newKey()).andExpect(status().isCreated());

        mvc.perform(get("/accounts/{id}", alice))
                .andExpect(jsonPath("$.balance").value("520.50"));
    }

    @Test
    void deposit_returnsTransactionWithEntriesFromExternalAccount() throws Exception {
        UUID alice = createAccount("Alice", "SGD");

        deposit(alice, "\"500\"", newKey())
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/transactions/")))
                .andExpect(jsonPath("$.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.sourceAccountId").value(EXTERNAL_SGD))
                .andExpect(jsonPath("$.destinationAccountId").value(alice.toString()))
                .andExpect(jsonPath("$.sourceAmount").value("500.00"))
                .andExpect(jsonPath("$.sourceCurrency").value("SGD"))
                .andExpect(jsonPath("$.destinationAmount").value("500.00"))
                .andExpect(jsonPath("$.destinationCurrency").value("SGD"))
                .andExpect(jsonPath("$", hasKey("fxRate")))
                .andExpect(jsonPath("$.fxRate").value(nullValue()))
                .andExpect(jsonPath("$", hasKey("reversesTransactionId")))
                .andExpect(jsonPath("$.reversesTransactionId").value(nullValue()))
                .andExpect(jsonPath("$", hasKey("reversedByTransactionId")))
                .andExpect(jsonPath("$.reversedByTransactionId").value(nullValue()))
                .andExpect(jsonPath("$.createdAt").value("2026-10-14T06:30:00Z"))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].accountId").value(EXTERNAL_SGD))
                .andExpect(jsonPath("$.entries[0].amount").value("-500.00"))
                .andExpect(jsonPath("$.entries[1].accountId").value(alice.toString()))
                .andExpect(jsonPath("$.entries[1].amount").value("500.00"));
    }

    @Test
    void deposit_entriesSumToZero() throws Exception {
        UUID alice = createAccount("Alice", "USD");

        String body = deposit(alice, "\"123.45\"", newKey())
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID txId = UUID.fromString(JsonPath.read(body, "$.id"));

        List<BigDecimal> amounts = jdbc.sql("SELECT amount FROM entries WHERE transaction_id = ?")
                .param(txId)
                .query(BigDecimal.class)
                .list();
        assertThat(amounts).hasSize(2);
        assertThat(amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isZero();

        List<String> currencies = jdbc.sql("SELECT DISTINCT currency FROM entries WHERE transaction_id = ?")
                .param(txId)
                .query(String.class)
                .list();
        assertThat(currencies).containsExactly("USD");
    }

    @Test
    void deposit_storesIdempotencyKeyAndRequestHash() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        String key = newKey();

        String body = deposit(alice, "\"10.00\"", key)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID txId = UUID.fromString(JsonPath.read(body, "$.id"));

        var row = jdbc.sql("SELECT idempotency_key, request_hash FROM transactions WHERE id = ?")
                .param(txId)
                .query((rs, n) -> List.of(rs.getString(1), rs.getString(2)))
                .single();
        assertThat(row.get(0)).isEqualTo(key);
        assertThat(row.get(1)).isEqualTo(RequestHasher.sha256Hex("DEPOSIT|account=" + alice + "|amount=10"));
    }

    @Test
    void deposit_toUnknownAccount_returns404AndWritesNothing() throws Exception {
        long before = countRows("transactions");

        deposit(UUID.randomUUID(), "\"10.00\"", newKey())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/problems/account-not-found"));

        assertThat(countRows("transactions")).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"0\"", "\"-5.00\"", "\"10.001\"", "\"1000000000000.01\"", "0.001"})
    void deposit_invalidAmount_returns400AndWritesNothing(String amount) throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        long before = countRows("transactions");

        deposit(alice, amount, newKey())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/invalid-amount"));

        assertThat(countRows("transactions")).isEqualTo(before);
        mvc.perform(get("/accounts/{id}", alice)).andExpect(jsonPath("$.balance").value("0.00"));
    }

    @Test
    void deposit_fractionalAmountToJpyAccount_returns400() throws Exception {
        UUID yuki = createAccount("Yuki", "JPY");

        deposit(yuki, "\"100.5\"", newKey())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/invalid-amount"));
        deposit(yuki, "\"100.0\"", newKey()).andExpect(status().isCreated());
    }

    @Test
    void deposit_missingAmount_returns400() throws Exception {
        UUID alice = createAccount("Alice", "SGD");

        deposit(alice, "null", newKey())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/validation-error"));
    }

    @Test
    void deposit_withoutIdempotencyKey_returns400ExplainingTheHeader() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        long before = countRows("transactions");

        deposit(alice, "\"10.00\"", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/missing-idempotency-key"))
                .andExpect(jsonPath("$.header").value("Idempotency-Key"))
                .andExpect(jsonPath("$.detail").value(containsString("never applied twice")));
        deposit(alice, "\"10.00\"", "   ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/missing-idempotency-key"));

        assertThat(countRows("transactions")).isEqualTo(before);
    }

    @Test
    void deposit_withInvalidIdempotencyKey_returns400() throws Exception {
        UUID alice = createAccount("Alice", "SGD");

        deposit(alice, "\"10.00\"", "x".repeat(256))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/invalid-idempotency-key"))
                .andExpect(jsonPath("$.header").value("Idempotency-Key"));
        deposit(alice, "\"10.00\"", "café")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/invalid-idempotency-key"));
    }

    @Test
    void deposit_toSystemAccount_returns422() throws Exception {
        deposit(UUID.fromString(EXTERNAL_SGD), "\"10.00\"", newKey())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("/problems/system-account-not-allowed"));
    }
}
