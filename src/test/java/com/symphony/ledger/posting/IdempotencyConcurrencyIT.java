package com.symphony.ledger.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import com.symphony.ledger.support.Concurrently;
import com.symphony.ledger.support.IntegrationTestBase;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

/**
 * Idempotency under concurrency, through the real HTTP API (DESIGN.md §7.4, §7.5, §14.2).
 * Concurrent requests are released together with a latch.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class IdempotencyConcurrencyIT extends IntegrationTestBase {

    private static final UUID EXTERNAL_USD = UUID.fromString("00000000-0000-0000-0000-000000000102");

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private DataSource dataSource;

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Test
    void twentyConcurrentIdenticalTransfers_postOneTransactionAndAllGetItsResult() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "SGD");
        fund(alice, "1000.00");
        String key = newKey();

        List<Result> results = sendTogether(20, () -> send(alice, bob, "25.00", key));

        assertAllGotTheSame201(results);
        assertThat(transactionsWithKey(key)).isEqualTo(1);
        assertThat(balance(alice)).isEqualByComparingTo("975.00");
        assertThat(balance(bob)).isEqualByComparingTo("25.00");
    }

    /**
     * Alice can afford the transfer exactly once. The duplicates wait on her lock, then re-check the key
     * before the funds check, so they replay instead of failing with 422 (DESIGN.md §7.5, §16.2 item 3).
     */
    @Test
    void fundsRace_concurrentDuplicatesOfAnAffordableOnceTransfer_allReplayAndNoneGet422() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "SGD");
        fund(alice, "30.00");
        String key = newKey();

        List<Result> results = sendTogether(20, () -> send(alice, bob, "30.00", key));

        assertThat(results).noneMatch(r -> r.status() == 422);
        assertAllGotTheSame201(results);
        assertThat(transactionsWithKey(key)).isEqualTo(1);
        assertThat(balance(alice)).isEqualByComparingTo("0");
        assertThat(balance(bob)).isEqualByComparingTo("30.00");
    }

    /**
     * Same key, different body, disjoint accounts (DESIGN.md §7.5). The first request is held open on
     * another connection, so the second passes the fast path and the post-lock re-check (the row isn't
     * committed yet) and waits on the key's unique-index entry at its INSERT. When the first commits, the
     * second gets the unique violation, which must become 409, not 500.
     */
    @Test
    void sameKeyDifferentBody_waitingOnTheUniqueIndex_gets409WhenTheFirstCommits() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "SGD");
        UUID carol = createAccount("Carol", "USD");
        fund(alice, "100.00");
        String key = newKey();

        Result result;
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            insertDeposit(holder, key, carol, new BigDecimal("10.00"));
            CompletableFuture<Result> transfer = CompletableFuture.supplyAsync(() -> sendUnchecked(alice, bob, "30.00", key));
            awaitInsertBlockedOnTheKey();
            holder.commit();
            result = transfer.get(30, TimeUnit.SECONDS);
        }

        assertThat(result.status()).as(result.body()).isEqualTo(409);
        assertThat(JsonPath.<String>read(result.body(), "$.type")).isEqualTo("/problems/idempotency-key-reused");
        assertThat(balance(alice)).isEqualByComparingTo("100.00");
        assertThat(balance(bob)).isEqualByComparingTo("0");
        assertThat(balance(carol)).isEqualByComparingTo("10.00");
        assertThat(transactionsWithKey(key)).isEqualTo(1);
    }

    /** As above, but the first rolls back: the waiting request goes ahead and keeps the key (DESIGN.md §7.5). */
    @Test
    void sameKey_waitingOnTheUniqueIndex_keepsTheKeyWhenTheFirstRollsBack() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "SGD");
        UUID carol = createAccount("Carol", "USD");
        fund(alice, "100.00");
        String key = newKey();

        Result result;
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            insertDeposit(holder, key, carol, new BigDecimal("10.00"));
            CompletableFuture<Result> transfer = CompletableFuture.supplyAsync(() -> sendUnchecked(alice, bob, "30.00", key));
            awaitInsertBlockedOnTheKey();
            holder.rollback();
            result = transfer.get(30, TimeUnit.SECONDS);
        }

        assertThat(result.status()).as(result.body()).isEqualTo(201);
        assertThat(result.replayed()).isNull();
        assertThat(balance(alice)).isEqualByComparingTo("70.00");
        assertThat(balance(carol)).isEqualByComparingTo("0");
        assertThat(jdbc.sql("SELECT type FROM transactions WHERE idempotency_key = ?").param(key)
                .query(String.class).single()).isEqualTo("TRANSFER");
    }

    private record Result(int status, String location, String replayed, String body) {
    }

    /** All 201, one Location and transaction id, equal bodies, and exactly one without Idempotent-Replayed. */
    private static void assertAllGotTheSame201(List<Result> results) throws Exception {
        assertThat(results).allSatisfy(r -> assertThat(r.status()).as(r.body()).isEqualTo(201));
        assertThat(results).filteredOn(r -> r.replayed() == null).hasSize(1);
        assertThat(results).filteredOn(r -> r.replayed() != null)
                .allSatisfy(r -> assertThat(r.replayed()).isEqualTo("true"));
        Result original = results.stream().filter(r -> r.replayed() == null).findFirst().orElseThrow();
        String id = JsonPath.read(original.body(), "$.id");
        assertThat(original.location()).isEqualTo("/transactions/" + id);
        for (Result r : results) {
            assertThat(r.location()).isEqualTo(original.location());
            assertThat(JsonPath.<String>read(r.body(), "$.id")).isEqualTo(id);
            JSONAssert.assertEquals(original.body(), r.body(), JSONCompareMode.STRICT);
        }
    }

    private static List<Result> sendTogether(int count, Callable<Result> call) throws Exception {
        List<Callable<Result>> calls = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            calls.add(call);
        }
        return Concurrently.runTogether(calls);
    }

    private Result send(UUID source, UUID destination, String amount, String key) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/transactions"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"sourceAccountId\":\"%s\",\"destinationAccountId\":\"%s\",\"amount\":\"%s\"}"
                                .formatted(source, destination, amount)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Result(response.statusCode(),
                response.headers().firstValue("Location").orElse(null),
                response.headers().firstValue("Idempotent-Replayed").orElse(null),
                response.body());
    }

    private Result sendUnchecked(UUID source, UUID destination, String amount, String key) {
        try {
            return send(source, destination, amount, key);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A complete, balanced deposit written on {@code holder} and left uncommitted, so its idempotency key
     * sits in the unique index without being visible to anyone else. Test-only INSERTs and balance
     * UPDATEs, as DESIGN.md §4 allows.
     */
    private void insertDeposit(Connection holder, String key, UUID account, BigDecimal amount) throws Exception {
        UUID txId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now(clock));
        try (PreparedStatement tx = holder.prepareStatement("""
                INSERT INTO transactions (id, type, idempotency_key, request_hash, created_at)
                VALUES (?, 'DEPOSIT', ?, ?, ?)
                """)) {
            tx.setObject(1, txId);
            tx.setString(2, key);
            tx.setString(3, RequestHasher.sha256Hex("DEPOSIT|account=" + account + "|amount=" + amount.stripTrailingZeros().toPlainString()));
            tx.setTimestamp(4, now);
            tx.executeUpdate();
        }
        insertLeg(holder, txId, EXTERNAL_USD, amount.negate(), now);
        insertLeg(holder, txId, account, amount, now);
    }

    private static void insertLeg(Connection holder, UUID txId, UUID account, BigDecimal amount, Timestamp now)
            throws Exception {
        try (PreparedStatement entry = holder.prepareStatement("""
                INSERT INTO entries (transaction_id, account_id, amount, currency, created_at)
                VALUES (?, ?, ?, 'USD', ?)
                """);
             PreparedStatement balance = holder.prepareStatement(
                     "UPDATE accounts SET balance = balance + ? WHERE id = ?")) {
            entry.setObject(1, txId);
            entry.setObject(2, account);
            entry.setBigDecimal(3, amount);
            entry.setTimestamp(4, now);
            entry.executeUpdate();
            balance.setBigDecimal(1, amount);
            balance.setObject(2, account);
            balance.executeUpdate();
        }
    }

    /** Waits until some session's INSERT INTO transactions is blocked on a lock: the key's unique-index entry. */
    private void awaitInsertBlockedOnTheKey() {
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.sql("""
                SELECT COUNT(*) FROM pg_stat_activity
                WHERE pid <> pg_backend_pid()
                  AND wait_event_type = 'Lock'
                  AND query LIKE '%INSERT INTO transactions%'
                """).query(Long.class).single() > 0);
    }

    private long transactionsWithKey(String key) {
        return jdbc.sql("SELECT COUNT(*) FROM transactions WHERE idempotency_key = ?")
                .param(key).query(Long.class).single();
    }
}
