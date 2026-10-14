package com.symphony.ledger.account;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.symphony.ledger.support.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

class AccountApiIT extends IntegrationTestBase {

    @Test
    void createAccount_returns201WithLocationAndZeroBalance() throws Exception {
        mvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Alice\",\"currency\":\"SGD\",\"type\":\"SYSTEM\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern("/accounts/[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.name").value("Alice"))
                .andExpect(jsonPath("$.currency").value("SGD"))
                .andExpect(jsonPath("$.type").value("CUSTOMER"))
                .andExpect(jsonPath("$.balance").value("0.00"))
                .andExpect(jsonPath("$.createdAt").value("2026-10-14T06:30:00Z"));
    }

    @Test
    void createdAccount_canBeFetched() throws Exception {
        UUID id = createAccount("Yuki", "JPY");

        mvc.perform(get("/accounts/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("Yuki"))
                .andExpect(jsonPath("$.currency").value("JPY"))
                .andExpect(jsonPath("$.type").value("CUSTOMER"))
                .andExpect(jsonPath("$.balance").value("0"));
    }

    @Test
    void systemAccounts_areSeeded() throws Exception {
        mvc.perform(get("/accounts/{id}", "00000000-0000-0000-0000-000000000101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("EXTERNAL_SGD"))
                .andExpect(jsonPath("$.type").value("SYSTEM"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"currency\":\"SGD\"}",
            "{\"name\":\"  \",\"currency\":\"SGD\"}",
            "{\"name\":\"Alice\"}",
            "{\"name\":\"Alice\",\"currency\":\"GBP\"}"
    })
    void createAccount_withInvalidBody_returns400(String body) throws Exception {
        mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/validation-error"));
    }

    @Test
    void getAccount_unknownId_returns404() throws Exception {
        mvc.perform(get("/accounts/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/problems/account-not-found"));
    }

    @Test
    void getAccount_malformedId_returns400() throws Exception {
        mvc.perform(get("/accounts/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/validation-error"));
    }
}
