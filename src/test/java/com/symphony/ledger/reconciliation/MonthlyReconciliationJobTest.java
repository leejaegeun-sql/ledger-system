package com.symphony.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.symphony.ledger.common.LedgerException;
import com.symphony.ledger.support.MutableClock;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

/** The scheduled month-end run (DESIGN.md §12.4). */
class MonthlyReconciliationJobTest {

    private final ReconciliationService reconciliations = mock(ReconciliationService.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-01T00:05:00Z"));
    private final MonthlyReconciliationJob job = new MonthlyReconciliationJob(reconciliations, clock);

    @Test
    void reconcilesThePreviousMonth_computedWithTheClock_asScheduled() {
        job.reconcilePreviousMonth();
        verify(reconciliations).reconcile(ReconciliationPeriod.parse("2026-10"), Trigger.SCHEDULED);

        clock.set(Instant.parse("2027-01-01T00:05:00Z"));
        job.reconcilePreviousMonth();
        verify(reconciliations).reconcile(ReconciliationPeriod.parse("2026-12"), Trigger.SCHEDULED);
    }

    @Test
    void anotherRunHasThePeriod_logsAndReturns() {
        when(reconciliations.reconcile(any(), any()))
                .thenThrow(LedgerException.reconciliationInProgress("2026-10"));

        assertThatCode(job::reconcilePreviousMonth).doesNotThrowAnyException();
    }

    /** Logged at ERROR, with no automatic retry. */
    @Test
    void anyOtherFailure_isLoggedAndNotRetried() {
        when(reconciliations.reconcile(any(), any())).thenThrow(new IllegalStateException("database down"));

        assertThatCode(job::reconcilePreviousMonth).doesNotThrowAnyException();
        verify(reconciliations).reconcile(any(), any());
    }

    /** 00:05 UTC on the 1st of each month, overridable by {@code ledger.reconciliation.cron}. */
    @Test
    void schedule_isFiveMinutesPastMidnightUtcOnTheFirst() throws Exception {
        Method method = MonthlyReconciliationJob.class.getMethod("reconcilePreviousMonth");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("${ledger.reconciliation.cron:0 5 0 1 * *}");
        assertThat(scheduled.zone()).isEqualTo("UTC");
        ZonedDateTime next = CronExpression.parse("0 5 0 1 * *")
                .next(ZonedDateTime.parse("2026-10-14T06:30:00Z").withZoneSameInstant(ZoneOffset.UTC));
        assertThat(next.toInstant()).isEqualTo(Instant.parse("2026-11-01T00:05:00Z"));
    }
}
