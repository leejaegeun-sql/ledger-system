package com.symphony.ledger.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for integration tests: one PostgreSQL container per JVM (singleton pattern),
 * shared by every subclass, plus a {@link MutableClock} reset before each test.
 *
 * <p>The database is not cleaned between tests. Tests create their own accounts and assert
 * on those, or compare row counts before and after.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
public abstract class IntegrationTestBase {

    /** Same tag as docker-compose.yml. */
    public static final String POSTGRES_IMAGE = "postgres:17.11-alpine";

    public static final Instant DEFAULT_NOW = Instant.parse("2026-10-14T06:30:00Z");

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected JdbcClient jdbc;

    @BeforeEach
    void resetClock() {
        clock.set(DEFAULT_NOW);
    }

    protected UUID createAccount(String name, String currency) throws Exception {
        String body = mvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"currency\":\"%s\"}".formatted(name, currency)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    protected ResultActions deposit(UUID accountId, String amountJson, String idempotencyKey) throws Exception {
        var request = post("/accounts/{id}/deposits", accountId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":" + amountJson + "}");
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request);
    }

    protected static String newKey() {
        return UUID.randomUUID().toString();
    }

    protected long countRows(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}
