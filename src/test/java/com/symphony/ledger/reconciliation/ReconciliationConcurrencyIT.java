package com.symphony.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.symphony.ledger.support.FreshDatabaseTestBase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.MediaType;

/**
 * Two runs for the same period at the same time (DESIGN.md §12.3, §14.2): one wins, the other gets 409
 * {@code reconciliation-in-progress} and leaves nothing behind. Runs through the real HTTP API on its own
 * fresh database.
 *
 * <p>To make the overlap certain rather than likely, the test holds {@code LOCK TABLE balance_snapshots IN
 * SHARE MODE} until both runs are blocked on their first snapshot INSERT. By then both have read their
 * REPEATABLE READ snapshot with no snapshots in it, so both try to create them.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ReconciliationConcurrencyIT extends FreshDatabaseTestBase {

    private static final int SYSTEM_ACCOUNTS = 8;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private DataSource dataSource;

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Test
    void concurrentRunsForTheSamePeriod_oneWins_theOtherGets409() throws Exception {
        clock.set(Instant.parse("2026-09-10T09:00:00Z"));
        UUID alice = createAccount("Alice", "SGD");
        fund(alice, "100.00");
        clock.set(Instant.parse("2026-10-02T09:00:00Z"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Result>> runs;
        try (Connection lock = dataSource.getConnection()) {
            lock.setAutoCommit(false);
            try (Statement statement = lock.createStatement()) {
                statement.execute("LOCK TABLE balance_snapshots IN SHARE MODE");
            }
            runs = List.of(pool.submit(() -> run("2026-09")), pool.submit(() -> run("2026-09")));
            awaitBackendsWaitingOnLocks(2);
            lock.commit();
        }
        List<Result> results = List.of(runs.get(0).get(60, TimeUnit.SECONDS), runs.get(1).get(60, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertThat(results).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(results).filteredOn(r -> r.status() == 409).singleElement()
                .satisfies(r -> assertThat(JsonPath.<String>read(r.body(), "$.type"))
                        .isEqualTo("/problems/reconciliation-in-progress"));
        // The loser rolled back completely: one report, and every snapshot belongs to the winner.
        String winner = JsonPath.read(results.stream().filter(r -> r.status() == 201).findFirst().orElseThrow().body(),
                "$.id");
        assertThat(countRows("reconciliations")).isEqualTo(1);
        assertThat(countRows("reconciliation_discrepancies")).isZero();
        assertThat(countRows("reconciliation_accounts")).isEqualTo(SYSTEM_ACCOUNTS + 1);
        assertThat(jdbc.sql("SELECT DISTINCT reconciliation_id::text FROM balance_snapshots").query(String.class).list())
                .containsExactly(winner);
        assertThat(countRows("balance_snapshots")).isEqualTo(SYSTEM_ACCOUNTS + 1);

        // Running again is safe: it verifies the winner's snapshots.
        mvc.perform(post("/reconciliations").contentType(MediaType.APPLICATION_JSON).content("{\"period\":\"2026-09\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("BALANCED"))
                .andExpect(jsonPath("$.snapshotsCreated").value(0))
                .andExpect(jsonPath("$.snapshotsVerified").value(SYSTEM_ACCOUNTS + 1));
    }

    private Result run(String period) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/reconciliations"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"period\":\"" + period + "\"}"))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Result(response.statusCode(), response.body());
    }

    private record Result(int status, String body) {
    }
}
