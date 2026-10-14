package com.symphony.ledger.posting;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.symphony.ledger.support.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.test.web.servlet.ResultActions;

/** {@code GET /transactions/{id}} (DESIGN.md §8.6). Every request carries a fresh Idempotency-Key. */
class GetTransactionIT extends IntegrationTestBase {

    private static final String FX_POOL_SGD = "00000000-0000-0000-0000-000000000201";
    private static final String FX_POOL_USD = "00000000-0000-0000-0000-000000000202";

    @Test
    void get_returnsTheSameRepresentationAsTheCreate() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "SGD");
        UUID carol = createAccount("Carol", "USD");
        String deposit = created(deposit(alice, "\"500.00\"", newKey()));
        String transfer = created(transfer(alice, bob, "\"20.00\"", newKey()));
        String fxTransfer = created(transfer(alice, carol, "\"100.05\"", newKey()));

        for (String createdBody : new String[] {deposit, transfer, fxTransfer}) {
            String fetched = getTransaction(JsonPath.read(createdBody, "$.id"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            JSONAssert.assertEquals(createdBody, fetched, JSONCompareMode.STRICT);
        }
    }

    @Test
    void get_fxTransfer_includesAllFourEntries() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "USD");
        fund(alice, "500.00");
        String id = JsonPath.read(created(transfer(alice, bob, "\"100.05\"", newKey())), "$.id");

        getTransaction(id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.type").value("TRANSFER"))
                .andExpect(jsonPath("$.sourceAccountId").value(alice.toString()))
                .andExpect(jsonPath("$.destinationAccountId").value(bob.toString()))
                .andExpect(jsonPath("$.sourceAmount").value("100.05"))
                .andExpect(jsonPath("$.destinationAmount").value("74.16"))
                .andExpect(jsonPath("$.fxRate").value("0.7412"))
                .andExpect(jsonPath("$.reversedByTransactionId").value(nullValue()))
                .andExpect(jsonPath("$.entries.length()").value(4))
                .andExpect(jsonPath("$.entries[0].accountId").value(alice.toString()))
                .andExpect(jsonPath("$.entries[0].amount").value("-100.05"))
                .andExpect(jsonPath("$.entries[1].accountId").value(FX_POOL_SGD))
                .andExpect(jsonPath("$.entries[1].amount").value("100.05"))
                .andExpect(jsonPath("$.entries[2].accountId").value(FX_POOL_USD))
                .andExpect(jsonPath("$.entries[2].amount").value("-74.16"))
                .andExpect(jsonPath("$.entries[3].accountId").value(bob.toString()))
                .andExpect(jsonPath("$.entries[3].amount").value("74.16"));
    }

    /** GET always shows the current state: reversedByTransactionId is filled in once a reversal exists. */
    @Test
    void get_reversedTransaction_showsItsReversal() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "SGD");
        fund(alice, "100.00");
        String id = JsonPath.read(created(transfer(alice, bob, "\"30.00\"", newKey())), "$.id");
        UUID reversal = insertReversalOf(UUID.fromString(id));

        getTransaction(id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reversedByTransactionId").value(reversal.toString()));
        getTransaction(reversal.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("REVERSAL"))
                .andExpect(jsonPath("$.reversesTransactionId").value(id))
                .andExpect(jsonPath("$.reversedByTransactionId").value(nullValue()))
                .andExpect(jsonPath("$.sourceAccountId").value(bob.toString()))
                .andExpect(jsonPath("$.destinationAccountId").value(alice.toString()))
                .andExpect(jsonPath("$.sourceAmount").value("30.00"));
    }

    @Test
    void get_unknownId_returns404() throws Exception {
        UUID unknown = UUID.randomUUID();

        getTransaction(unknown.toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/problems/transaction-not-found"))
                .andExpect(jsonPath("$.transactionId").value(unknown.toString()));
    }

    @Test
    void get_malformedId_returns400() throws Exception {
        getTransaction("not-a-uuid")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/validation-error"));
    }

    private ResultActions getTransaction(String id) throws Exception {
        return mvc.perform(withNewKey(get("/transactions/{id}", id)));
    }

    private static String created(ResultActions posted) throws Exception {
        return posted.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }
}
