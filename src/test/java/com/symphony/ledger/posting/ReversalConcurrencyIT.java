package com.symphony.ledger.posting;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.symphony.ledger.support.Concurrently;
import com.symphony.ledger.support.FreshDatabaseTestBase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

/**
 * Concurrent reversals of one transaction through the real HTTP API (DESIGN.md §5.4, §7.5, §14.2).
 * Requests are released together with a latch. Each test runs on its own database and ends with a clean
 * integrity check.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ReversalConcurrencyIT extends FreshDatabaseTestBase {

    @Value("${local.server.port}")
    private int port;

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** All lock the same accounts; whoever comes second sees the reversal after the lock (DESIGN.md §7.5). */
    @Test
    void tenConcurrentReversalsWithDifferentKeys_exactlyOneSucceeds_theRestGet409() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "USD");
        fund(alice, "500.00");
        UUID original = createdId(transfer(alice, bob, "\"100.05\"", newKey()));

        List<Callable<Result>> calls = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String key = newKey();
            calls.add(() -> send(original, key));
        }
        List<Result> results = Concurrently.runTogether(calls);

        assertThat(results).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(results).filteredOn(r -> r.status() == 409).hasSize(9)
                .allSatisfy(r -> assertThat(JsonPath.<String>read(r.body(), "$.type"))
                        .isEqualTo("/problems/transaction-already-reversed"));
        assertThat(reversalsOf(original)).isEqualTo(1);
        assertThat(balance(alice)).isEqualByComparingTo("500.00");
        assertThat(balance(bob)).isEqualByComparingTo("0");
        assertThat(sumOfEntries(alice)).isEqualByComparingTo("500.00");
        assertThat(sumOfEntries(bob)).isEqualByComparingTo("0");
        assertIntegrityOk();
    }

    /**
     * Same key from every thread: the waiters re-check the key after the locks, before the "already
     * reversed" check, so they replay instead of getting 409 (DESIGN.md §7.4 step 5).
     */
    @Test
    void tenConcurrentIdenticalReversals_allGetTheSame201() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "SGD");
        fund(alice, "100.00");
        UUID original = createdId(transfer(alice, bob, "\"30.00\"", newKey()));
        String key = newKey();

        List<Callable<Result>> calls = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            calls.add(() -> send(original, key));
        }
        List<Result> results = Concurrently.runTogether(calls);

        assertThat(results).allSatisfy(r -> assertThat(r.status()).as(r.body()).isEqualTo(201));
        assertThat(results).filteredOn(r -> r.replayed() == null).hasSize(1);
        assertThat(results).extracting(r -> JsonPath.<String>read(r.body(), "$.id")).containsOnly(
                JsonPath.<String>read(results.getFirst().body(), "$.id"));
        assertThat(reversalsOf(original)).isEqualTo(1);
        assertThat(balance(alice)).isEqualByComparingTo("100.00");
        assertThat(balance(bob)).isEqualByComparingTo("0");
        assertIntegrityOk();
    }

    private record Result(int status, String replayed, String body) {
    }

    private Result send(UUID transactionId, String key) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/transactions/" + transactionId + "/reversals"))
                .timeout(Duration.ofSeconds(60))
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Result(response.statusCode(),
                response.headers().firstValue("Idempotent-Replayed").orElse(null),
                response.body());
    }

    private long reversalsOf(UUID transactionId) {
        return jdbc.sql("SELECT COUNT(*) FROM transactions WHERE reverses_transaction_id = ?")
                .param(transactionId).query(Long.class).single();
    }
}
