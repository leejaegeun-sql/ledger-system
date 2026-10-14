package com.symphony.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.symphony.ledger.support.FreshDatabaseTestBase;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Month-end reconciliation: {@code POST /reconciliations}, {@code GET /reconciliations/{id}} and the
 * scheduled job (DESIGN.md §8.12, §8.13, §12, §14.2). The clock is moved across month ends. Every test
 * runs on its own fresh database: snapshots are permanent and a run covers every account.
 */
class ReconciliationIT extends FreshDatabaseTestBase {

    private static final UUID EXTERNAL_SGD = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID FX_POOL_SGD = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final int SYSTEM_ACCOUNTS = 8;

    @Autowired
    private MonthlyReconciliationJob job;

    @Autowired
    private ApplicationContext context;

    @Value("${ledger.reconciliation.scheduling-enabled}")
    private boolean schedulingEnabled;

    @Test
    void firstRun_createsSnapshotsForEveryAccountThatExistedByPeriodEnd() throws Exception {
        clock.set(Instant.parse("2026-09-10T09:00:00Z"));
        UUID alice = createAccount("Alice", "SGD");
        fund(alice, "100.00");
        clock.set(Instant.parse("2026-10-02T09:00:00Z"));
        UUID carol = createAccount("Carol", "SGD"); // created after the period: not covered

        String body = reconcile("2026-09")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.period").value("2026-09"))
                .andExpect(jsonPath("$.periodStart").value("2026-09-01T00:00:00Z"))
                .andExpect(jsonPath("$.periodEnd").value("2026-10-01T00:00:00Z"))
                .andExpect(jsonPath("$.trigger").value("MANUAL"))
                .andExpect(jsonPath("$.status").value("BALANCED"))
                .andExpect(jsonPath("$.accountsChecked").value(SYSTEM_ACCOUNTS + 1))
                .andExpect(jsonPath("$.accountsMatched").value(SYSTEM_ACCOUNTS + 1))
                .andExpect(jsonPath("$.accountsWithDiscrepancies").value(0))
                .andExpect(jsonPath("$.snapshotsCreated").value(SYSTEM_ACCOUNTS + 1))
                .andExpect(jsonPath("$.snapshotsVerified").value(0))
                .andExpect(jsonPath("$.createdAt").value("2026-10-02T09:00:00Z"))
                .andExpect(jsonPath("$.discrepancies").isEmpty())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(body, "$.id"));

        // One line per account checked. No previous snapshot, so every opening is computed from entries.
        assertThat(JsonPath.<List<String>>read(body, "$.accounts[*].accountId"))
                .hasSize(SYSTEM_ACCOUNTS + 1).doesNotContain(carol.toString());
        assertThat(accountLine(body, alice))
                .isEqualTo(line(alice, "SGD", "0.00", "COMPUTED", "100.00", "100.00", "CREATED", "MATCHED"));
        assertThat(accountLine(body, EXTERNAL_SGD))
                .isEqualTo(line(EXTERNAL_SGD, "SGD", "0.00", "COMPUTED", "-100.00", "-100.00", "CREATED", "MATCHED"));
        assertThat(accountLine(body, FX_POOL_SGD))
                .isEqualTo(line(FX_POOL_SGD, "SGD", "0.00", "COMPUTED", "0.00", "0.00", "CREATED", "MATCHED"));

        Map<UUID, Snapshot> snapshots = snapshots("2026-09-01");
        assertThat(snapshots).hasSize(SYSTEM_ACCOUNTS + 1).doesNotContainKey(carol);
        assertThat(snapshots.values()).allSatisfy(s -> assertThat(s.reconciliationId()).isEqualTo(id));
        assertSnapshot(snapshots.get(alice), "100.00", 1);
        assertSnapshot(snapshots.get(EXTERNAL_SGD), "-100.00", 1);
        assertSnapshot(snapshots.get(FX_POOL_SGD), "0", 0);
    }

    @Test
    void reRun_verifiesTheStoredSnapshots_andIgnoresActivityAfterThePeriod() throws Exception {
        clock.set(Instant.parse("2026-09-10T09:00:00Z"));
        UUID alice = createAccount("Alice", "SGD");
        fund(alice, "100.00");
        clock.set(Instant.parse("2026-10-02T09:00:00Z"));
        reconcile("2026-09").andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("BALANCED"));
        Map<UUID, Snapshot> stored = snapshots("2026-09-01");

        clock.set(Instant.parse("2026-10-03T09:00:00Z"));
        fund(alice, "50.00");
        String rerun = reconcile("2026-09")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("BALANCED"))
                .andExpect(jsonPath("$.accountsChecked").value(SYSTEM_ACCOUNTS + 1))
                .andExpect(jsonPath("$.accountsMatched").value(SYSTEM_ACCOUNTS + 1))
                .andExpect(jsonPath("$.accountsWithDiscrepancies").value(0))
                .andExpect(jsonPath("$.snapshotsCreated").value(0))
                .andExpect(jsonPath("$.snapshotsVerified").value(SYSTEM_ACCOUNTS + 1))
                .andExpect(jsonPath("$.discrepancies").isEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(rerun, "$.accounts[*].snapshot")).containsOnly("MATCHED");
        assertThat(accountLine(rerun, alice))
                .isEqualTo(line(alice, "SGD", "0.00", "COMPUTED", "100.00", "100.00", "MATCHED", "MATCHED"));

        assertThat(snapshots("2026-09-01")).isEqualTo(stored);
        assertThat(countRows("reconciliations")).isEqualTo(2);
    }

    /** Periods are half-open UTC months: an entry at exactly 00:00 on the 1st belongs to the new month. */
    @Test
    void clockMovedAcrossMonthEnds_eachMonthRollsForwardFromThePreviousSnapshot() throws Exception {
        clock.set(Instant.parse("2026-09-10T09:00:00Z"));
        UUID alice = createAccount("Alice", "SGD");
        UUID bob = createAccount("Bob", "SGD");
        fund(alice, "100.00");
        clock.set(Instant.parse("2026-09-30T23:59:59.999999Z"));
        fund(alice, "1.00");
        clock.set(Instant.parse("2026-10-01T00:00:00Z"));
        fund(alice, "2.00");
        clock.set(Instant.parse("2026-10-05T09:00:00Z"));
        createdId(transfer(alice, bob, "\"30.00\"", newKey()));

        clock.set(Instant.parse("2026-10-06T09:00:00Z"));
        reconcile("2026-09").andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("BALANCED"));
        assertSnapshot(snapshots("2026-09-01").get(alice), "101.00", 2);
        assertSnapshot(snapshots("2026-09-01").get(bob), "0", 0);

        clock.set(Instant.parse("2026-11-02T09:00:00Z"));
        String october = reconcile("2026-10")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("BALANCED"))
                .andExpect(jsonPath("$.periodStart").value("2026-10-01T00:00:00Z"))
                .andExpect(jsonPath("$.periodEnd").value("2026-11-01T00:00:00Z"))
                .andExpect(jsonPath("$.snapshotsCreated").value(SYSTEM_ACCOUNTS + 2))
                .andExpect(jsonPath("$.discrepancies").isEmpty())
                .andReturn().getResponse().getContentAsString();
        // October opens from September's snapshot: 101.00 + (2.00 − 30.00) = 73.00.
        assertThat(JsonPath.<List<String>>read(october, "$.accounts[*].openingSource")).containsOnly("SNAPSHOT");
        assertThat(accountLine(october, alice))
                .isEqualTo(line(alice, "SGD", "101.00", "SNAPSHOT", "-28.00", "73.00", "CREATED", "MATCHED"));
        assertThat(accountLine(october, bob))
                .isEqualTo(line(bob, "SGD", "0.00", "SNAPSHOT", "30.00", "30.00", "CREATED", "MATCHED"));
        assertSnapshot(snapshots("2026-10-01").get(alice), "73.00", 4);
        assertSnapshot(snapshots("2026-10-01").get(bob), "30.00", 1);
    }

    /**
     * DESIGN.md §14.2, "Reconciliation tamper test (backdated transaction)": reconciliation catches history
     * being rewritten, without breaking the append-only rule. The numbered comments are the spec's steps.
     */
    @Test
    void backdatedTransaction_isCaughtBySnapshots_butNotByTheIntegrityCheck() throws Exception {
        // 1. Set the clock to 2026-09-10. Deposit 100.00 SGD into Alice.
        clock.set(Instant.parse("2026-09-10T09:00:00Z"));
        UUID alice = createAccount("Alice", "SGD");
        fund(alice, "100.00");

        // 2. Move the clock to 2026-10-02. Reconcile 2026-09: BALANCED, snapshots for Alice and EXTERNAL_SGD.
        clock.set(Instant.parse("2026-10-02T09:00:00Z"));
        reconcile("2026-09").andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("BALANCED"));
        Map<UUID, Snapshot> september = snapshots("2026-09-01");
        assertSnapshot(september.get(alice), "100.00", 1);
        assertSnapshot(september.get(EXTERNAL_SGD), "-100.00", 1);

        // 3. Tamper, inserts only: a balanced DEPOSIT dated inside September, and cached balances to match.
        Instant backdated = Instant.parse("2026-09-15T12:00:00Z");
        UUID tx = tamper.insertTransaction("DEPOSIT", backdated, null);
        tamper.insertEntry(tx, alice, "10.00", "SGD", backdated);
        tamper.insertEntry(tx, EXTERNAL_SGD, "-10.00", "SGD", backdated);
        tamper.addToBalance(alice, "10.00");
        tamper.addToBalance(EXTERNAL_SGD, "-10.00");

        // 4. Reconcile 2026-09 again: SNAPSHOT_MISMATCH for both, and no CACHED_BALANCE_MISMATCH.
        String rerun = reconcile("2026-09")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DISCREPANCIES_FOUND"))
                .andExpect(jsonPath("$.accountsMatched").value(SYSTEM_ACCOUNTS - 1))
                .andExpect(jsonPath("$.accountsWithDiscrepancies").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat(accountLine(rerun, alice)).isEqualTo(
                line(alice, "SGD", "0.00", "COMPUTED", "110.00", "110.00", "MISMATCHED", "DISCREPANCIES_FOUND"));
        assertThat(accountLine(rerun, EXTERNAL_SGD)).isEqualTo(line(EXTERNAL_SGD, "SGD", "0.00", "COMPUTED",
                "-110.00", "-110.00", "MISMATCHED", "DISCREPANCIES_FOUND"));
        assertThat(discrepancies(rerun)).containsExactlyInAnyOrder(
                discrepancy("SNAPSHOT_MISMATCH", alice, "100.00", "110.00",
                        "Stored closing 100.00 (1 entry); recomputed 110.00 (2 entries)"),
                discrepancy("SNAPSHOT_MISMATCH", EXTERNAL_SGD, "-100.00", "-110.00",
                        "Stored closing -100.00 (1 entry); recomputed -110.00 (2 entries)"));
        assertThat(kinds(rerun)).doesNotContain("CACHED_BALANCE_MISMATCH");

        // 5. The stored 2026-09 snapshots are unchanged.
        assertThat(snapshots("2026-09-01")).isEqualTo(september);

        // 6. The integrity check reports OK: the backdated transaction is balanced and consistent.
        mvc.perform(get("/ledger/integrity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"));

        // 7. Reconcile 2026-10: the opening comes from the stale September snapshot, the closing includes
        //    the backdated entries, so both accounts fail the roll-forward.
        clock.set(Instant.parse("2026-11-02T09:00:00Z"));
        String october = reconcile("2026-10")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DISCREPANCIES_FOUND"))
                .andReturn().getResponse().getContentAsString();
        assertThat(accountLine(october, alice)).isEqualTo(
                line(alice, "SGD", "100.00", "SNAPSHOT", "0.00", "110.00", "CREATED", "DISCREPANCIES_FOUND"));
        assertThat(accountLine(october, EXTERNAL_SGD)).isEqualTo(line(EXTERNAL_SGD, "SGD", "-100.00", "SNAPSHOT",
                "0.00", "-110.00", "CREATED", "DISCREPANCIES_FOUND"));
        assertThat(withoutDetail(discrepancies(october))).containsExactlyInAnyOrder(
                Map.of("kind", "ROLLFORWARD_MISMATCH", "accountId", alice.toString(),
                        "expected", "100.00", "actual", "110.00"),
                Map.of("kind", "ROLLFORWARD_MISMATCH", "accountId", EXTERNAL_SGD.toString(),
                        "expected", "-100.00", "actual", "-110.00"));
    }

    /** R3, stored with the report: GET returns exactly what the 201 returned. */
    @Test
    void cachedBalanceDrift_isReported_andGetReturnsTheSameReport() throws Exception {
        clock.set(Instant.parse("2026-09-10T09:00:00Z"));
        UUID alice = createAccount("Alice", "SGD");
        fund(alice, "100.00");
        tamper.addToBalance(alice, "10");

        clock.set(Instant.parse("2026-10-02T09:00:00Z"));
        ResultActions created = reconcile("2026-09")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DISCREPANCIES_FOUND"));
        String body = created.andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");
        created.andExpect(header().string("Location", "/reconciliations/" + id));
        assertThat(withoutDetail(discrepancies(body))).containsExactly(
                Map.of("kind", "CACHED_BALANCE_MISMATCH", "accountId", alice.toString(),
                        "expected", "100.00", "actual", "110.00"));
        assertThat(accountLine(body, alice)).isEqualTo(
                line(alice, "SGD", "0.00", "COMPUTED", "100.00", "100.00", "CREATED", "DISCREPANCIES_FOUND"));
        assertThat(JsonPath.<List<Object>>read(body, "$.accounts")).hasSize(SYSTEM_ACCOUNTS + 1);

        clock.set(Instant.parse("2026-10-03T09:00:00Z"));
        String fetched = mvc.perform(get("/reconciliations/{id}", id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JSONAssert.assertEquals(body, fetched, JSONCompareMode.STRICT);
    }

    /** R4: a backdated withdrawal takes Alice's September closing below zero (entries only, no balance update). */
    @Test
    void negativeClosingBalance_isReported() throws Exception {
        clock.set(Instant.parse("2026-09-10T09:00:00Z"));
        UUID alice = createAccount("Alice", "SGD");
        fund(alice, "100.00");
        Instant backdated = Instant.parse("2026-09-20T12:00:00Z");
        UUID tx = tamper.insertTransaction("TRANSFER", backdated, null);
        tamper.insertEntry(tx, alice, "-150.00", "SGD", backdated);
        tamper.insertEntry(tx, EXTERNAL_SGD, "150.00", "SGD", backdated);

        clock.set(Instant.parse("2026-10-02T09:00:00Z"));
        String body = reconcile("2026-09")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DISCREPANCIES_FOUND"))
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> negative = new HashMap<>();
        negative.put("kind", "NEGATIVE_CLOSING_BALANCE");
        negative.put("accountId", alice.toString());
        negative.put("expected", null);
        negative.put("actual", "-50.00");
        assertThat(accountLine(body, alice)).isEqualTo(
                line(alice, "SGD", "0.00", "COMPUTED", "-50.00", "-50.00", "CREATED", "DISCREPANCIES_FOUND"));
        assertThat(withoutDetail(discrepancies(body))).containsExactlyInAnyOrder(
                negative,
                Map.of("kind", "CACHED_BALANCE_MISMATCH", "accountId", alice.toString(),
                        "expected", "-50.00", "actual", "100.00"),
                Map.of("kind", "CACHED_BALANCE_MISMATCH", "accountId", EXTERNAL_SGD.toString(),
                        "expected", "50.00", "actual", "-100.00"));
    }

    /**
     * A manual run gets the same five-minute grace as the scheduled job (§12.1): until then, postings dated just
     * before midnight may still be committing, and a snapshot taken without them would be wrong for good.
     */
    @Test
    void currentOrFuturePeriod_orWithinFiveMinutesOfItsEnd_is422PeriodNotClosed_andWritesNothing() throws Exception {
        createAccount("Alice", "SGD");

        expectProblem(reconcile("2026-10"), 422, "period-not-closed");
        expectProblem(reconcile("2027-01"), 422, "period-not-closed");
        clock.set(Instant.parse("2026-10-31T23:59:59.999999Z"));
        expectProblem(reconcile("2026-10"), 422, "period-not-closed");
        clock.set(Instant.parse("2026-11-01T00:00:00Z"));
        expectProblem(reconcile("2026-10"), 422, "period-not-closed");
        clock.set(Instant.parse("2026-11-01T00:04:59.999999Z"));
        reconcile("2026-10")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("/problems/period-not-closed"))
                .andExpect(jsonPath("$.period").value("2026-10"))
                .andExpect(jsonPath("$.closesAt").value("2026-11-01T00:05:00Z"));

        assertThat(countRows("reconciliations")).isZero();
        assertThat(countRows("balance_snapshots")).isZero();

        clock.set(Instant.parse("2026-11-01T00:05:00Z"));
        reconcile("2026-10").andExpect(status().isCreated());
    }

    @Test
    void malformedOrMissingPeriod_is400InvalidPeriod_andWritesNothing() throws Exception {
        for (String period : List.of("2026-13", "2026-00", "2026-9", "202609", "2026-09-01", "09-2026", "", " 2026-09")) {
            expectProblem(reconcile(period), 400, "invalid-period");
        }
        for (String body : List.of("{}", "{\"period\":null}")) {
            expectProblem(mvc.perform(post("/reconciliations").contentType(MediaType.APPLICATION_JSON).content(body)),
                    400, "invalid-period");
        }
        assertThat(countRows("reconciliations")).isZero();
    }

    @Test
    void getReconciliation_unknownIs404_malformedIs400() throws Exception {
        expectProblem(mvc.perform(get("/reconciliations/{id}", UUID.randomUUID())), 404, "reconciliation-not-found");
        mvc.perform(get("/reconciliations/{id}", "not-a-uuid")).andExpect(status().isBadRequest());
    }

    /** Called directly: at 00:05 UTC on 1 November it reconciles October, recorded as SCHEDULED (§12.4). */
    @Test
    void scheduledJob_calledDirectly_reconcilesThePreviousMonth() throws Exception {
        clock.set(Instant.parse("2026-10-10T09:00:00Z"));
        UUID alice = createAccount("Alice", "SGD");
        fund(alice, "100.00");

        clock.set(Instant.parse("2026-11-01T00:05:00Z"));
        job.reconcilePreviousMonth();

        List<Map<String, Object>> runs = jdbc.sql("SELECT id, period::text AS period FROM reconciliations")
                .query().listOfRows();
        assertThat(runs).hasSize(1);
        assertThat(runs.getFirst().get("period")).isEqualTo("2026-10-01");
        mvc.perform(get("/reconciliations/{id}", runs.getFirst().get("id")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("2026-10"))
                .andExpect(jsonPath("$.trigger").value("SCHEDULED"))
                .andExpect(jsonPath("$.status").value("BALANCED"))
                .andExpect(jsonPath("$.createdAt").value("2026-11-01T00:05:00Z"));
        assertSnapshot(snapshots("2026-10-01").get(alice), "100.00", 1);
    }

    @Test
    void scheduling_isOffInTests() {
        assertThat(schedulingEnabled).isFalse();
        assertThat(context.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
    }

    private ResultActions reconcile(String period) throws Exception {
        return mvc.perform(post("/reconciliations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"period\":\"" + period + "\"}"));
    }

    private static void expectProblem(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status)).andExpect(jsonPath("$.type").value("/problems/" + code));
    }

    private static List<Map<String, Object>> discrepancies(String body) {
        return JsonPath.read(body, "$.discrepancies");
    }

    private static List<String> kinds(String body) {
        return JsonPath.read(body, "$.discrepancies[*].kind");
    }

    private static List<Map<String, Object>> withoutDetail(List<Map<String, Object>> discrepancies) {
        return discrepancies.stream().map(d -> {
            Map<String, Object> copy = new HashMap<>(d);
            copy.remove("detail");
            return copy;
        }).toList();
    }

    /** The report's line for {@code accountId}. */
    private static Map<String, Object> accountLine(String body, UUID accountId) {
        List<Map<String, Object>> lines = JsonPath.read(body, "$.accounts[?(@.accountId == '" + accountId + "')]");
        assertThat(lines).as("line for " + accountId).hasSize(1);
        return lines.getFirst();
    }

    private static Map<String, Object> line(UUID accountId, String currency, String opening, String openingSource,
            String movement, String closing, String snapshot, String status) {
        return Map.of("accountId", accountId.toString(), "currency", currency, "openingBalance", opening,
                "openingSource", openingSource, "movement", movement, "closingBalance", closing,
                "snapshot", snapshot, "status", status);
    }

    private static Map<String, Object> discrepancy(String kind, UUID accountId, String expected, String actual,
            String detail) {
        return Map.of("kind", kind, "accountId", accountId.toString(), "expected", expected, "actual", actual,
                "detail", detail);
    }

    /** All snapshots of the period starting {@code firstDay}, by account. */
    private Map<UUID, Snapshot> snapshots(String firstDay) {
        Map<UUID, Snapshot> byAccount = new HashMap<>();
        jdbc.sql("""
                SELECT id, account_id, closing_balance, entry_count, reconciliation_id, created_at
                FROM balance_snapshots WHERE period = ?
                """)
                .param(LocalDate.parse(firstDay))
                .query((rs, n) -> new Snapshot(
                        rs.getLong("id"),
                        rs.getObject("account_id", UUID.class),
                        rs.getBigDecimal("closing_balance"),
                        rs.getLong("entry_count"),
                        rs.getObject("reconciliation_id", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list()
                .forEach(s -> byAccount.put(s.accountId(), s));
        return byAccount;
    }

    private static void assertSnapshot(Snapshot snapshot, String closing, long entryCount) {
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.closingBalance()).isEqualByComparingTo(closing);
        assertThat(snapshot.entryCount()).isEqualTo(entryCount);
    }

    private record Snapshot(long id, UUID accountId, BigDecimal closingBalance, long entryCount,
            UUID reconciliationId, Instant createdAt) {
    }
}
