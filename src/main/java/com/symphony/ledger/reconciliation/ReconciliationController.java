package com.symphony.ledger.reconciliation;

import com.symphony.ledger.common.Money;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Runs and reads month-end reconciliations. Moves no money, so it takes no Idempotency-Key (DESIGN.md §8.12). */
@RestController
@RequestMapping("/reconciliations")
public class ReconciliationController {

    private final ReconciliationService reconciliations;

    public ReconciliationController(ReconciliationService reconciliations) {
        this.reconciliations = reconciliations;
    }

    /** Synchronous. Discrepancies don't change the 201. */
    @PostMapping
    ResponseEntity<ReconciliationResponse> run(@RequestBody RunRequest request) {
        ReconciliationReport report = reconciliations.reconcile(ReconciliationPeriod.parse(request.period()),
                Trigger.MANUAL);
        return ResponseEntity.created(URI.create("/reconciliations/" + report.id()))
                .body(ReconciliationResponse.from(report));
    }

    /** The same representation as the 201 (DESIGN.md §8.13). */
    @GetMapping("/{id}")
    ReconciliationResponse get(@PathVariable UUID id) {
        return ReconciliationResponse.from(reconciliations.get(id));
    }

    /** {@code period} is YYYY-MM. Checked by {@link ReconciliationPeriod#parse}, so a missing one is invalid-period. */
    record RunRequest(String period) {
    }

    record ReconciliationResponse(UUID id, String period, Instant periodStart, Instant periodEnd, Trigger trigger,
            ReconciliationReport.Status status, int accountsChecked, int snapshotsCreated, int snapshotsVerified,
            Instant createdAt, List<DiscrepancyResponse> discrepancies) {

        static ReconciliationResponse from(ReconciliationReport r) {
            return new ReconciliationResponse(r.id(), r.period().toString(), r.period().start(), r.period().end(),
                    r.trigger(), r.status(), r.accountsChecked(), r.snapshotsCreated(), r.snapshotsVerified(),
                    r.createdAt(), r.discrepancies().stream().map(DiscrepancyResponse::from).toList());
        }
    }

    /** Amounts at the account's currency scale; {@code expected} is null for NEGATIVE_CLOSING_BALANCE. */
    record DiscrepancyResponse(ReconciliationReport.Kind kind, UUID accountId, String expected, String actual,
            String detail) {

        static DiscrepancyResponse from(ReconciliationReport.Discrepancy d) {
            return new DiscrepancyResponse(d.kind(), d.accountId(), format(d.expected(), d), format(d.actual(), d),
                    d.detail());
        }

        private static String format(BigDecimal amount, ReconciliationReport.Discrepancy d) {
            return amount == null ? null : Money.formatLenient(amount, d.currency());
        }
    }
}
