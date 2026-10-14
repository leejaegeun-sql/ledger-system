package com.symphony.ledger.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Base class for tests that tamper with data or assert on the whole ledger: the integrity check,
 * reconciliation, and the concurrency tests, which end with an integrity check. Each test method runs
 * against its own new database, migrated by the application's Flyway, so its result never depends on
 * which tests ran before it.
 *
 * <p>Tampering goes through {@link #tamper}, which only does what DESIGN.md §4 allows.
 */
@Import(FreshDatabaseConfig.class)
public abstract class FreshDatabaseTestBase extends IntegrationTestBase {

    @Autowired
    private FreshDatabase database;

    @Autowired
    private Flyway flyway;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    protected LedgerTampering tamper;

    @BeforeEach
    void freshDatabase() {
        database.recreate();
        flyway.migrate();
        assertThat(jdbc.sql("SELECT current_database()").query(String.class).single())
                .as("the application's queries go to this test's database")
                .isEqualTo(database.name());
        tamper = new LedgerTampering(jdbcTemplate);
    }

    /** {@code GET /ledger/integrity} finds nothing wrong anywhere in this test's ledger (DESIGN.md §11). */
    protected void assertIntegrityOk() throws Exception {
        mvc.perform(get("/ledger/integrity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.discrepancies").isEmpty());
    }

    /** Waits until {@code expected} sessions on this test's database are blocked on a lock. */
    protected void awaitBackendsWaitingOnLocks(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            long waiting = jdbc.sql("""
                    SELECT COUNT(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND wait_event_type = 'Lock'
                    """).query(Long.class).single();
            if (waiting >= expected) {
                return;
            }
            assertThat(System.nanoTime()).as("%d sessions blocked on a lock", expected).isLessThan(deadline);
            Thread.sleep(20);
        }
    }
}
