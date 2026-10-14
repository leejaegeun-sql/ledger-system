package com.symphony.ledger.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.symphony.ledger.support.IntegrationTestBase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.ResultActions;

/** {@code GET /accounts/{id}/transactions} (DESIGN.md §8.7). Every request carries a fresh Idempotency-Key. */
class HistoryIT extends IntegrationTestBase {

    private static final String EXTERNAL_SGD = "00000000-0000-0000-0000-000000000101";

    /**
     * The backdated deposit has a later entry id but an earlier created_at, so it must come first; the
     * deposit and the transfer share a created_at, so the entry id breaks the tie.
     */
    @Test
    void history_isOrderedByCreatedAtThenEntryId() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "SGD");
        Instant t1 = Instant.parse("2026-10-14T07:00:00Z");
        Instant t2 = Instant.parse("2026-10-14T08:00:00Z");

        clock.set(t2);
        String first = txId(deposit(alice, "\"100.00\"", newKey()));
        clock.set(t1);
        String backdated = txId(deposit(alice, "\"50.00\"", newKey()));
        clock.set(t2);
        String sameTime = txId(transfer(alice, bob, "\"30.00\"", newKey()));

        String body = history(alice)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].transactionId").value(contains(backdated, first, sameTime)))
                .andExpect(jsonPath("$.items[*].createdAt").value(contains(
                        "2026-10-14T07:00:00Z", "2026-10-14T08:00:00Z", "2026-10-14T08:00:00Z")))
                .andReturn().getResponse().getContentAsString();
        List<Long> entryIds = entryIds(body);
        assertThat(entryIds.get(0)).isGreaterThan(entryIds.get(1));
        assertThat(entryIds.get(1)).isLessThan(entryIds.get(2));
    }

    @Test
    void history_amountsAreSignedFromTheAccountsPointOfView() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "USD");
        String deposit = txId(deposit(alice, "\"500.00\"", newKey()));
        String aliceToBob = txId(transfer(alice, bob, "\"100.05\"", newKey()));
        String bobToAlice = txId(transfer(bob, alice, "\"10.00\"", newKey()));

        String aliceBody = history(alice)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(alice.toString()))
                .andExpect(jsonPath("$.currency").value("SGD"))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[*].transactionId").value(contains(deposit, aliceToBob, bobToAlice)))
                .andExpect(jsonPath("$.items[*].amount").value(contains("500.00", "-100.05", "13.49")))
                .andExpect(jsonPath("$.items[0].type").value("DEPOSIT"))
                .andExpect(jsonPath("$.items[0].sourceAccountId").value(EXTERNAL_SGD))
                .andExpect(jsonPath("$.items[0].destinationAccountId").value(alice.toString()))
                .andExpect(jsonPath("$.items[0]", hasKey("fxRate")))
                .andExpect(jsonPath("$.items[0].fxRate").value(nullValue()))
                .andExpect(jsonPath("$.items[0]", hasKey("reversesTransactionId")))
                .andExpect(jsonPath("$.items[0].reversesTransactionId").value(nullValue()))
                .andExpect(jsonPath("$.items[0].createdAt").value("2026-10-14T06:30:00Z"))
                .andExpect(jsonPath("$.items[1].type").value("TRANSFER"))
                .andExpect(jsonPath("$.items[1].sourceAccountId").value(alice.toString()))
                .andExpect(jsonPath("$.items[1].destinationAccountId").value(bob.toString()))
                .andExpect(jsonPath("$.items[1].fxRate").value("0.7412"))
                .andExpect(jsonPath("$.items[2].sourceAccountId").value(bob.toString()))
                .andExpect(jsonPath("$.items[2].destinationAccountId").value(alice.toString()))
                .andExpect(jsonPath("$.items[2].fxRate").value("1.349"))
                .andReturn().getResponse().getContentAsString();

        history(bob)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.items[*].transactionId").value(contains(aliceToBob, bobToAlice)))
                .andExpect(jsonPath("$.items[*].amount").value(contains("74.16", "-10.00")));

        // Each item's entryId is this account's own entry, not an FX pool leg.
        for (long entryId : entryIds(aliceBody)) {
            String account = jdbc.sql("SELECT account_id::text FROM entries WHERE id = ?")
                    .param(entryId).query(String.class).single();
            assertThat(account).isEqualTo(alice.toString());
        }
    }

    @Test
    void history_paginatesOldestFirst() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        for (int i = 1; i <= 5; i++) {
            clock.set(DEFAULT_NOW.plusSeconds(60L * i));
            deposit(alice, "\"" + i + ".00\"", newKey()).andExpect(status().isCreated());
        }

        String all = history(alice)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalItems").value(5))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.items[*].amount").value(contains("1.00", "2.00", "3.00", "4.00", "5.00")))
                .andReturn().getResponse().getContentAsString();

        List<Long> paged = new ArrayList<>();
        paged.addAll(entryIds(history(alice, "page", "0", "size", "2")
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalItems").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.items[*].amount").value(contains("1.00", "2.00")))
                .andReturn().getResponse().getContentAsString()));
        paged.addAll(entryIds(history(alice, "page", "1", "size", "2")
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.items[*].amount").value(contains("3.00", "4.00")))
                .andReturn().getResponse().getContentAsString()));
        paged.addAll(entryIds(history(alice, "page", "2", "size", "2")
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.items[*].amount").value(contains("5.00")))
                .andReturn().getResponse().getContentAsString()));
        history(alice, "page", "3", "size", "2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").value(empty()))
                .andExpect(jsonPath("$.totalItems").value(5))
                .andExpect(jsonPath("$.totalPages").value(3));

        assertThat(paged).isEqualTo(entryIds(all));
    }

    @Test
    void history_ofAccountWithNoActivity_isEmpty() throws Exception {
        UUID alice = createAccount("Alice", "EUR");

        history(alice)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(alice.toString()))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.items").value(empty()))
                .andExpect(jsonPath("$.totalItems").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void history_ofUnknownAccount_returns404() throws Exception {
        history(UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/problems/account-not-found"));
    }

    @Test
    void history_withMalformedAccountId_returns400() throws Exception {
        mvc.perform(withNewKey(get("/accounts/{id}/transactions", "not-a-uuid")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/validation-error"));
    }

    @ParameterizedTest
    @CsvSource({"page, -1", "size, 0", "size, 101", "page, abc", "size, 1.5"})
    void history_withBadPaging_returns400(String param, String value) throws Exception {
        UUID alice = createAccount("Alice", "SGD");

        history(alice, param, value)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/validation-error"));
    }

    /** {@code params} are name/value pairs. */
    private ResultActions history(UUID accountId, String... params) throws Exception {
        var request = withNewKey(get("/accounts/{id}/transactions", accountId));
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request);
    }

    private static String txId(ResultActions posted) throws Exception {
        String body = posted.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private static List<Long> entryIds(String historyBody) {
        List<Number> ids = JsonPath.read(historyBody, "$.items[*].entryId");
        return ids.stream().map(Number::longValue).toList();
    }
}
