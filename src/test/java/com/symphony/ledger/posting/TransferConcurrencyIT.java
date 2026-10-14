package com.symphony.ledger.posting;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.symphony.ledger.support.FreshDatabaseTestBase;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

/**
 * Concurrent transfers through the real HTTP API (DESIGN.md §6.2, §14.2). Each test starts every request
 * on its own thread and releases them together with a {@link CountDownLatch}. Every request carries a
 * fresh Idempotency-Key. Each test runs on its own database and ends with a clean integrity check.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class TransferConcurrencyIT extends FreshDatabaseTestBase {

    @Value("${local.server.port}")
    private int port;

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Test
    void twoHundredRandomTransfersBetweenAliceAndBob_loseNothing() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "SGD");
        fund(alice, "1000.00");
        fund(bob, "1000.00");

        Random random = new Random(20261014);
        List<Callable<TransferResult>> calls = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            boolean aliceToBob = random.nextBoolean();
            BigDecimal amount = randomAmount(random);
            calls.add(() -> aliceToBob ? send(alice, bob, amount) : send(bob, alice, amount));
        }

        List<TransferResult> results = runTogether(calls);

        assertThat(results).allSatisfy(r -> assertThat(r.status()).as(r.body()).isLessThan(500));
        assertThat(results).allSatisfy(r -> assertThat(r.status()).as(r.body()).isIn(201, 422));
        Map<UUID, BigDecimal> expected = expectedBalances(results, Map.of(
                alice, new BigDecimal("1000.00"), bob, new BigDecimal("1000.00")));
        assertThat(balance(alice).add(balance(bob))).isEqualByComparingTo("2000.00");
        for (UUID account : List.of(alice, bob)) {
            assertThat(balance(account)).isEqualByComparingTo(sumOfEntries(account));
            assertThat(balance(account)).isEqualByComparingTo(expected.get(account));
            assertThat(balance(account)).isNotNegative();
        }
        assertIntegrityOk();
    }

    @Test
    void tenConcurrentTransfersOfThirtyOutOfOneHundred_exactlyThreeSucceed() throws Exception {
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "SGD");
        fund(alice, "100.00");

        List<Callable<TransferResult>> calls = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            calls.add(() -> send(alice, bob, new BigDecimal("30.00")));
        }

        List<TransferResult> results = runTogether(calls);

        assertThat(results).filteredOn(r -> r.status() == 201).hasSize(3);
        assertThat(results).filteredOn(r -> r.status() == 422).hasSize(7)
                .allSatisfy(r -> assertThat(JsonPath.<String>read(r.body(), "$.type"))
                        .isEqualTo("/problems/insufficient-funds"));
        assertThat(balance(alice)).isEqualByComparingTo("10.00");
        assertThat(balance(bob)).isEqualByComparingTo("90.00");
        assertThat(sumOfEntries(alice)).isEqualByComparingTo("10.00");
        assertThat(sumOfEntries(bob)).isEqualByComparingTo("90.00");
        assertIntegrityOk();
    }

    /**
     * 20 senders pay one recipient at once, so every transfer locks the recipient. Each sends a different
     * amount (1.01 to 1.20), so a debit applied twice or to the wrong sender can't cancel out.
     */
    @Test
    void twentySendersPayingOneRecipientAtOnce_recipientGetsExactlyTheSum() throws Exception {
        UUID recipient = createAccount("Recipient", "SGD");
        List<UUID> senders = new ArrayList<>();
        List<BigDecimal> amounts = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            UUID sender = createAccount("Sender " + i, "SGD");
            fund(sender, "50.00");
            senders.add(sender);
            amounts.add(BigDecimal.valueOf(100 + i, 2));
        }

        List<Callable<TransferResult>> calls = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            UUID sender = senders.get(i);
            BigDecimal amount = amounts.get(i);
            calls.add(() -> send(sender, recipient, amount));
        }
        List<TransferResult> results = runTogether(calls);

        assertThat(results).allSatisfy(r -> assertThat(r.status()).as(r.body()).isEqualTo(201));
        assertThat(balance(recipient)).isEqualByComparingTo("22.10");
        assertThat(sumOfEntries(recipient)).isEqualByComparingTo("22.10");
        assertThat(entryAmounts(recipient)).hasSize(20);
        for (int i = 0; i < 20; i++) {
            UUID sender = senders.get(i);
            BigDecimal amount = amounts.get(i);
            List<BigDecimal> debits = entryAmounts(sender).stream().filter(a -> a.signum() < 0).toList();
            assertThat(debits).as("debits of sender %d", i + 1).singleElement()
                    .satisfies(d -> assertThat(d).isEqualByComparingTo(amount.negate()));
            assertThat(balance(sender)).isEqualByComparingTo(new BigDecimal("50.00").subtract(amount));
            assertThat(sumOfEntries(sender)).isEqualByComparingTo(balance(sender));
        }
        assertThat(countRows("transactions")).isEqualTo(40);
        assertIntegrityOk();
    }

    /**
     * 50 threads × 10 transfers among four accounts in two currencies, in every direction. FX transfers
     * also lock both FX pools, so lock sets overlap in many different ways: no deadlock, no lost update,
     * final balances exact, and integrity clean.
     */
    @Test
    void fiftyThreadsTransferringAmongFewAccounts_noDeadlockAndBalancesExact() throws Exception {
        List<UUID> accounts = List.of(
                createAccount("S1", "SGD"), createAccount("S2", "SGD"),
                createAccount("U1", "USD"), createAccount("U2", "USD"));
        Map<UUID, BigDecimal> initial = new HashMap<>();
        for (UUID account : accounts) {
            fund(account, "1000.00");
            initial.put(account, new BigDecimal("1000.00"));
        }

        Random random = new Random(42);
        List<Callable<List<TransferResult>>> threads = new ArrayList<>();
        for (int t = 0; t < 50; t++) {
            List<UUID[]> pairs = new ArrayList<>();
            List<BigDecimal> amounts = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                int from = random.nextInt(accounts.size());
                int to = (from + 1 + random.nextInt(accounts.size() - 1)) % accounts.size();
                pairs.add(new UUID[] {accounts.get(from), accounts.get(to)});
                amounts.add(randomAmount(random));
            }
            threads.add(() -> {
                List<TransferResult> mine = new ArrayList<>();
                for (int i = 0; i < pairs.size(); i++) {
                    mine.add(send(pairs.get(i)[0], pairs.get(i)[1], amounts.get(i)));
                }
                return mine;
            });
        }

        List<TransferResult> results = runTogether(threads).stream().flatMap(List::stream).toList();

        assertThat(results).hasSize(500);
        assertThat(results).allSatisfy(r -> assertThat(r.status()).as(r.body()).isIn(201, 422));
        assertThat(results).filteredOn(r -> r.status() == 201).isNotEmpty();
        Map<UUID, BigDecimal> expected = expectedBalances(results, initial);
        for (UUID account : accounts) {
            assertThat(balance(account)).isEqualByComparingTo(expected.get(account));
            assertThat(balance(account)).isEqualByComparingTo(sumOfEntries(account));
            assertThat(balance(account)).isNotNegative();
        }
        long successful = results.stream().filter(r -> r.status() == 201).count();
        assertThat(countRows("transactions")).isEqualTo(accounts.size() + successful);
        assertIntegrityOk();
    }

    private record TransferResult(int status, String body) {
    }

    /** 1.00 to 10.00 in steps of 0.01. */
    private static BigDecimal randomAmount(Random random) {
        return BigDecimal.valueOf(100 + random.nextInt(901), 2);
    }

    private TransferResult send(UUID source, UUID destination, BigDecimal amount) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/transactions"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", newKey())
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"sourceAccountId\":\"%s\",\"destinationAccountId\":\"%s\",\"amount\":\"%s\"}"
                                .formatted(source, destination, amount.toPlainString())))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new TransferResult(response.statusCode(), response.body());
    }

    private List<BigDecimal> entryAmounts(UUID account) {
        return jdbc.sql("SELECT amount FROM entries WHERE account_id = ?").param(account).query(BigDecimal.class).list();
    }

    /** Starting balances plus every entry of every 201 response, for the accounts in {@code initial}. */
    private static Map<UUID, BigDecimal> expectedBalances(List<TransferResult> results, Map<UUID, BigDecimal> initial) {
        Map<UUID, BigDecimal> expected = new HashMap<>(initial);
        for (TransferResult result : results) {
            if (result.status() != 201) {
                continue;
            }
            List<Map<String, Object>> entries = JsonPath.read(result.body(), "$.entries");
            for (Map<String, Object> entry : entries) {
                UUID account = UUID.fromString((String) entry.get("accountId"));
                expected.computeIfPresent(account, (id, b) -> b.add(new BigDecimal((String) entry.get("amount"))));
            }
        }
        return expected;
    }

    /** Runs each call on its own thread, all released together by one latch, and returns results in order. */
    private static <T> List<T> runTogether(List<Callable<T>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).as("all threads ready").isTrue();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(120, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
