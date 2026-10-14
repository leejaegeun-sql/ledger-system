package com.symphony.ledger.reconciliation;

import com.symphony.ledger.common.LedgerException;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reconciles the previous month at 00:05 UTC on the 1st (DESIGN.md §12.4). The five minutes cover postings
 * that took their {@code created_at} just before midnight but commit just after. Runs only when
 * {@link SchedulingConfig} is on; tests call {@link #reconcilePreviousMonth()} directly.
 */
@Component
public class MonthlyReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(MonthlyReconciliationJob.class);

    private final ReconciliationService reconciliations;
    private final Clock clock;

    public MonthlyReconciliationJob(ReconciliationService reconciliations, Clock clock) {
        this.reconciliations = reconciliations;
        this.clock = clock;
    }

    /** No automatic retry: a failure is logged at ERROR, and the month can be reconciled manually. */
    @Scheduled(cron = "${ledger.reconciliation.cron:0 5 0 1 * *}", zone = "UTC")
    public void reconcilePreviousMonth() {
        ReconciliationPeriod period = ReconciliationPeriod.containing(clock.instant()).previous();
        try {
            reconciliations.reconcile(period, Trigger.SCHEDULED);
        } catch (RuntimeException e) {
            if (e instanceof LedgerException ledger && "reconciliation-in-progress".equals(ledger.code())) {
                log.warn("Scheduled reconciliation of {} skipped: another run has the period", period);
                return;
            }
            log.error("Scheduled reconciliation of {} failed and will not be retried; run POST /reconciliations "
                    + "to reconcile it manually", period, e);
        }
    }
}
