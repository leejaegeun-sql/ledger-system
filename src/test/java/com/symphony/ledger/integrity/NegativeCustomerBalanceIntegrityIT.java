package com.symphony.ledger.integrity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.symphony.ledger.support.FreshDatabaseTestBase;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * Integrity check C5, negative customer balance (DESIGN.md §11, §14.2).
 *
 * <p>C5 is defence in depth behind the database CHECK {@code accounts_customer_non_negative}. With the
 * constraint in place, a negative customer balance can't be committed, so the only way to test the
 * integrity query is to remove the constraint. This test exists to prove the query works if the
 * constraint is ever lost (a bad migration, or a manual change to the schema).
 *
 * <p>{@code @Isolated} so JUnit never runs it alongside another test while the constraint is missing. It
 * also runs on its own fresh database, so even a failed cleanup can't leak into another test.
 */
@Isolated
class NegativeCustomerBalanceIntegrityIT extends FreshDatabaseTestBase {

    @Test
    void negativeCustomerBalance_isReported() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        fund(alice, "100.00");

        jdbcTemplate.execute("ALTER TABLE accounts DROP CONSTRAINT accounts_customer_non_negative");
        try {
            jdbcTemplate.update("UPDATE accounts SET balance = ? WHERE id = ?", new BigDecimal("-50.00"), alice);

            String body = mvc.perform(get("/ledger/integrity"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DISCREPANCIES_FOUND"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(IntegrityIT.discrepancies(body)).containsExactlyInAnyOrder(
                    Map.of("check", "NEGATIVE_CUSTOMER_BALANCE", "accountId", alice.toString(), "actual", "-50.00"),
                    Map.of("check", "CURRENCY_NOT_ZERO_SUM", "currency", "SGD", "actual", "-150.00"),
                    Map.of("check", "CACHED_BALANCE_MISMATCH", "accountId", alice.toString(), "currency", "SGD",
                            "expected", "100.00", "actual", "-50.00", "difference", "-150.00"));
        } finally {
            // In this order: ADD CONSTRAINT checks existing rows, so it fails while the negative balance is there.
            jdbcTemplate.update("UPDATE accounts SET balance = ? WHERE id = ?", new BigDecimal("100.00"), alice);
            jdbcTemplate.execute("""
                    ALTER TABLE accounts ADD CONSTRAINT accounts_customer_non_negative
                        CHECK (type = 'SYSTEM' OR balance >= 0)
                    """);
        }

        mvc.perform(get("/ledger/integrity")).andExpect(jsonPath("$.status").value("OK"));
    }
}
