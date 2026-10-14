package com.symphony.ledger.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Base class for tests that tamper with data or assert on the whole ledger (the integrity check,
 * reconciliation). Each test method runs against its own new database, migrated by the application's
 * Flyway, so its result never depends on which tests ran before it.
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
}
