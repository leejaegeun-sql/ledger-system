package com.symphony.ledger.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.symphony.ledger.support.IntegrationTestBase;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.mock.web.MockHttpServletResponse;

/** Sequential idempotency behaviour (DESIGN.md §7, §14.2). */
class IdempotencyIT extends IntegrationTestBase {

    private static final UUID FX_POOL_SGD = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID FX_POOL_USD = UUID.fromString("00000000-0000-0000-0000-000000000202");

    @Test
    void deposit_sameKeyAndBodyTwice_replaysTheOriginal201() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        String key = newKey();

        MockHttpServletResponse first = deposit(alice, "\"500.00\"", key)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andReturn().getResponse();
        long transactions = countRows("transactions");
        long entries = countRows("entries");

        MockHttpServletResponse second = deposit(alice, "\"500.00\"", key)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse();

        assertSameResult(first, second);
        assertThat(countRows("transactions")).isEqualTo(transactions);
        assertThat(countRows("entries")).isEqualTo(entries);
        assertThat(balance(alice)).isEqualByComparingTo("500.00");
    }

    @Test
    void transfer_sameKeyAndBodyTwice_replaysTheOriginal201() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "USD");
        fund(alice, "500.00");
        BigDecimal poolSgd = cachedBalance(FX_POOL_SGD);
        BigDecimal poolUsd = cachedBalance(FX_POOL_USD);
        String key = newKey();

        MockHttpServletResponse first = transfer(alice, bob, "\"100.05\"", key)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.destinationAmount").value("74.16"))
                .andReturn().getResponse();
        long transactions = countRows("transactions");
        long entries = countRows("entries");

        MockHttpServletResponse second = transfer(alice, bob, "\"100.05\"", key)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse();

        assertSameResult(first, second);
        assertThat(countRows("transactions")).isEqualTo(transactions);
        assertThat(countRows("entries")).isEqualTo(entries);
        assertThat(balance(alice)).isEqualByComparingTo("399.95");
        assertThat(balance(bob)).isEqualByComparingTo("74.16");
        assertThat(cachedBalance(FX_POOL_SGD)).isEqualByComparingTo(poolSgd.add(new BigDecimal("100.05")));
        assertThat(cachedBalance(FX_POOL_USD)).isEqualByComparingTo(poolUsd.subtract(new BigDecimal("74.16")));
    }

    /** {@code "100"}, {@code "100.0"} and {@code 100.00} hash the same (DESIGN.md §7.3). */
    @Test
    void replay_treatsEquivalentAmountsAsTheSameBody() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "SGD");
        String depositKey = newKey();
        String transferKey = newKey();

        MockHttpServletResponse deposit = deposit(alice, "\"200\"", depositKey).andReturn().getResponse();
        MockHttpServletResponse depositAgain = deposit(alice, "200.00", depositKey)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse();
        MockHttpServletResponse transfer = transfer(alice, bob, "\"100\"", transferKey).andReturn().getResponse();
        MockHttpServletResponse transferAgain = transfer(alice, bob, "\"100.0\"", transferKey)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse();

        assertSameResult(deposit, depositAgain);
        assertSameResult(transfer, transferAgain);
        assertThat(balance(alice)).isEqualByComparingTo("100.00");
        assertThat(balance(bob)).isEqualByComparingTo("100.00");
    }

    @Test
    void deposit_sameKeyDifferentBody_returns409AndWritesNothing() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "SGD");
        String key = newKey();
        deposit(alice, "\"10.00\"", key).andExpect(status().isCreated());
        long transactions = countRows("transactions");

        deposit(alice, "\"20.00\"", key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/idempotency-key-reused"));
        deposit(bob, "\"10.00\"", key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/idempotency-key-reused"));

        assertThat(countRows("transactions")).isEqualTo(transactions);
        assertThat(balance(alice)).isEqualByComparingTo("10.00");
        assertThat(balance(bob)).isEqualByComparingTo("0");
    }

    @Test
    void transfer_sameKeyDifferentBody_returns409AndWritesNothing() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "SGD");
        UUID carol = createAccount("Carol", "SGD");
        fund(alice, "100.00");
        String key = newKey();
        transfer(alice, bob, "\"10.00\"", key).andExpect(status().isCreated());
        long transactions = countRows("transactions");

        transfer(alice, bob, "\"10.01\"", key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/idempotency-key-reused"));
        transfer(alice, carol, "\"10.00\"", key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/idempotency-key-reused"));
        transfer(bob, alice, "\"10.00\"", key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/idempotency-key-reused"));

        assertThat(countRows("transactions")).isEqualTo(transactions);
        assertThat(balance(alice)).isEqualByComparingTo("90.00");
        assertThat(balance(bob)).isEqualByComparingTo("10.00");
        assertThat(balance(carol)).isEqualByComparingTo("0");
    }

    /** The operation type is part of the hash, so a deposit's key reused for a transfer is a different body (§7.2). */
    @Test
    void keyReusedAcrossOperations_returns409() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "SGD");
        String depositKey = newKey();
        deposit(alice, "\"50.00\"", depositKey).andExpect(status().isCreated());
        String transferKey = newKey();
        transfer(alice, bob, "\"10.00\"", transferKey).andExpect(status().isCreated());
        long transactions = countRows("transactions");

        transfer(alice, bob, "\"50.00\"", depositKey)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/idempotency-key-reused"));
        deposit(bob, "\"10.00\"", transferKey)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/idempotency-key-reused"));

        assertThat(countRows("transactions")).isEqualTo(transactions);
    }

    /** A rejected request leaves no trace, including its key (DESIGN.md §7.5, I13). */
    @Test
    void rejectedRequest_doesNotStoreItsKey_soARetryCanSucceed() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "SGD");
        fund(alice, "10.00");
        String key = newKey();

        transfer(alice, bob, "\"30.00\"", key)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("/problems/insufficient-funds"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM transactions WHERE idempotency_key = ?")
                .param(key).query(Long.class).single()).isZero();

        fund(alice, "50.00");
        transfer(alice, bob, "\"30.00\"", key)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));

        assertThat(balance(alice)).isEqualByComparingTo("30.00");
        assertThat(balance(bob)).isEqualByComparingTo("30.00");
    }

    /** A replay renders the transaction as it was created, so reversedByTransactionId stays null (§7.4). */
    @Test
    void replay_afterTheTransactionWasReversed_stillShowsReversedByNull() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "SGD");
        fund(alice, "100.00");
        String key = newKey();
        MockHttpServletResponse first = transfer(alice, bob, "\"30.00\"", key)
                .andExpect(status().isCreated())
                .andReturn().getResponse();
        insertReversalOf(UUID.fromString(JsonPath.read(first.getContentAsString(), "$.id")));

        MockHttpServletResponse replay = transfer(alice, bob, "\"30.00\"", key)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.reversedByTransactionId").value(nullValue()))
                .andReturn().getResponse();

        assertSameResult(first, replay);
    }

    /** Same Location, same transaction, and a body equal to the first response. */
    private static void assertSameResult(MockHttpServletResponse first, MockHttpServletResponse second)
            throws Exception {
        assertThat(second.getHeader("Location")).isNotNull().isEqualTo(first.getHeader("Location"));
        assertThat(JsonPath.<String>read(second.getContentAsString(), "$.id"))
                .isEqualTo(JsonPath.<String>read(first.getContentAsString(), "$.id"));
        JSONAssert.assertEquals(first.getContentAsString(), second.getContentAsString(), JSONCompareMode.STRICT);
    }

    private BigDecimal cachedBalance(UUID accountId) {
        return jdbc.sql("SELECT balance FROM accounts WHERE id = ?").param(accountId).query(BigDecimal.class).single();
    }
}
