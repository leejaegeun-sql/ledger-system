package com.symphony.ledger.integrity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.symphony.ledger.common.Constraints;
import com.symphony.ledger.support.FreshDatabaseTestBase;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;

/**
 * The database's own guards (DESIGN.md §4, §13 I5, I6, I12): the append-only triggers reject UPDATE, DELETE
 * and TRUNCATE on every protected table, and the composite foreign key keeps an entry's currency equal to its
 * account's.
 *
 * <p>Each test runs on its own fresh database: if a trigger is missing, the statement really does change or
 * wipe that table, and only this test's database is lost.
 */
class DatabaseGuardsIT extends FreshDatabaseTestBase {

    static final List<String> APPEND_ONLY_TABLES = List.of("entries", "transactions", "balance_snapshots",
            "exchange_rates", "reconciliations", "reconciliation_accounts", "reconciliation_discrepancies");

    private UUID alice;

    /** At least one row in every protected table, including a reconciliation with a discrepancy. */
    @BeforeEach
    void fillEveryProtectedTable() throws Exception {
        clock.set(Instant.parse("2026-09-10T09:00:00Z"));
        alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "USD");
        fund(alice, "100.00");
        createdId(transfer(alice, bob, "\"10.00\"", newKey()));

        clock.set(Instant.parse("2026-10-02T09:00:00Z"));
        tamper.addToBalance(alice, "1.00");
        mvc.perform(post("/reconciliations").contentType(MediaType.APPLICATION_JSON).content("{\"period\":\"2026-09\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DISCREPANCIES_FOUND"));
        tamper.addToBalance(alice, "-1.00");

        assertThat(rowCounts()).allSatisfy((table, count) -> assertThat(count).as(table).isPositive());
    }

    @ParameterizedTest
    @FieldSource("APPEND_ONLY_TABLES")
    void updateDeleteAndTruncate_areRejected_andLeaveEveryRow(String table) throws Exception {
        Map<String, Long> before = rowCounts();

        // A no-op UPDATE is enough: the row trigger fires before anything is written.
        assertRejected(table, "UPDATE " + table + " SET id = id");
        assertRejected(table, "DELETE FROM " + table);
        // CASCADE, or tables that others reference would fail on the foreign key before any trigger ran. The
        // named table's trigger fires first, so the message names it.
        assertRejected(table, "TRUNCATE " + table + " CASCADE");

        assertThat(rowCounts()).isEqualTo(before);
        assertIntegrityOk();
    }

    @Test
    void entryInAnotherCurrencyThanItsAccount_isRejectedByTheCompositeForeignKey() {
        long entries = countRows("entries");
        UUID tx = tamper.insertTransaction("DEPOSIT", Instant.parse("2026-10-02T09:00:00Z"), null);

        assertThatThrownBy(() -> tamper.insertEntry(tx, alice, "1.00", "USD", Instant.parse("2026-10-02T09:00:00Z")))
                .isInstanceOfSatisfying(DataIntegrityViolationException.class,
                        e -> assertThat(Constraints.violated(e)).isEqualTo("entries_account_currency_fk"));

        assertThat(countRows("entries")).isEqualTo(entries);
    }

    private void assertRejected(String table, String sql) {
        assertThatThrownBy(() -> jdbcTemplate.execute(sql))
                .as(sql)
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining(table + " is append-only");
    }

    private Map<String, Long> rowCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : APPEND_ONLY_TABLES) {
            counts.put(table, countRows(table));
        }
        return counts;
    }
}
