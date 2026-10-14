package com.symphony.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.symphony.ledger.support.Concurrently;
import com.symphony.ledger.support.FreshDatabaseTestBase;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
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
 * Reconciliation and the integrity check while postings commit on the same accounts (DESIGN.md §6.2, §11,
 * §12.3). Runs through the real HTTP API on its own fresh database.
 *
 * <p>To make the interleaving certain rather than likely, the test holds {@code LOCK TABLE reconciliations IN
 * SHARE MODE} until the run is blocked on its first INSERT. By then it has read its REPEATABLE READ snapshot.
 * Transfers and deposits then commit on every account the run is about to write report lines and snapshots for,
 * with integrity checks running alongside them, and only then is the run let go. Its inserts check foreign keys
 * against account rows that changed after its snapshot.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ReconciliationUnderLoadIT extends FreshDatabaseTestBase {

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
    void postingsCommittingDuringARun_doNotFailIt_andIntegrityStaysOk() throws Exception {
        clock.set(Instant.parse("2026-09-10T09:00:00Z"));
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "SGD");
        UUID ursula = createAccount("Ursula", "USD");
        fund(alice, "1000.00");
        fund(bob, "1000.00");
        fund(ursula, "1000.00");
        clock.set(Instant.parse("2026-10-02T09:00:00Z"));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<Result> run;
        List<Result> during;
        try (Connection lock = dataSource.getConnection()) {
            lock.setAutoCommit(false);
            try (Statement statement = lock.createStatement()) {
                statement.execute("LOCK TABLE reconciliations IN SHARE MODE");
            }
            run = pool.submit(() -> send(httpPost("/reconciliations", null, "{\"period\":\"2026-09\"}")));
            awaitBackendsWaitingOnLocks(1);

            // Every account the run covers that these touch: customers, EXTERNAL_SGD, FX_POOL_SGD and FX_POOL_USD.
            List<Callable<Result>> calls = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                calls.add(() -> send(transfer(alice, bob, "1.00")));
                calls.add(() -> send(transfer(bob, alice, "2.00")));
                calls.add(() -> send(transfer(alice, ursula, "3.00")));
                calls.add(() -> send(deposit(bob, "4.00")));
                calls.add(() -> send(httpGet("/ledger/integrity")));
            }
            during = Concurrently.runTogether(calls);
            lock.commit();
        }
        Result result = run.get(60, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(during).filteredOn(r -> r.path().equals("/ledger/integrity")).hasSize(10)
                .allSatisfy(r -> {
                    assertThat(r.status()).as(r.body()).isEqualTo(200);
                    assertThat(JsonPath.<String>read(r.body(), "$.status")).as(r.body()).isEqualTo("OK");
                });
        assertThat(during).filteredOn(r -> !r.path().equals("/ledger/integrity")).hasSize(40)
                .allSatisfy(r -> assertThat(r.status()).as(r.body()).isEqualTo(201));

        assertThat(result.status()).as(result.body()).isEqualTo(201);
        assertThat(JsonPath.<String>read(result.body(), "$.status")).isEqualTo("BALANCED");
        assertThat(JsonPath.<Integer>read(result.body(), "$.accountsChecked")).isEqualTo(SYSTEM_ACCOUNTS + 3);
        assertThat(JsonPath.<Integer>read(result.body(), "$.snapshotsCreated")).isEqualTo(SYSTEM_ACCOUNTS + 3);
        // The snapshots are September's closings: none of October's postings are in them.
        for (UUID account : List.of(alice, bob, ursula)) {
            assertThat(jdbc.sql("SELECT closing_balance FROM balance_snapshots WHERE account_id = ?")
                    .param(account).query(BigDecimal.class).single()).isEqualByComparingTo("1000.00");
        }

        assertIntegrityOk();
        mvc.perform(post("/reconciliations").contentType(MediaType.APPLICATION_JSON).content("{\"period\":\"2026-09\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("BALANCED"))
                .andExpect(jsonPath("$.snapshotsVerified").value(SYSTEM_ACCOUNTS + 3));
    }

    private HttpRequest transfer(UUID source, UUID destination, String amount) {
        return httpPost("/transactions", newKey(),
                "{\"sourceAccountId\":\"%s\",\"destinationAccountId\":\"%s\",\"amount\":\"%s\"}"
                        .formatted(source, destination, amount));
    }

    private HttpRequest deposit(UUID account, String amount) {
        return httpPost("/accounts/" + account + "/deposits", newKey(), "{\"amount\":\"" + amount + "\"}");
    }

    private HttpRequest httpPost(String path, String idempotencyKey, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return request.build();
    }

    private HttpRequest httpGet(String path) {
        return HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(60)).GET().build();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private Result send(HttpRequest request) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Result(request.uri().getPath(), response.statusCode(), response.body());
    }

    private record Result(String path, int status, String body) {
    }
}
