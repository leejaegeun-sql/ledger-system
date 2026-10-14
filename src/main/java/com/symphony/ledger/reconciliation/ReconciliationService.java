package com.symphony.ledger.reconciliation;

import com.symphony.ledger.account.AccountType;
import com.symphony.ledger.common.ClockConfig;
import com.symphony.ledger.common.Constraints;
import com.symphony.ledger.common.LedgerException;
import com.symphony.ledger.common.Money;
import com.symphony.ledger.reconciliation.ReconciliationReport.Discrepancy;
import com.symphony.ledger.reconciliation.ReconciliationReport.Kind;
import com.symphony.ledger.reconciliation.ReconciliationRepository.AccountFigures;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Month-end reconciliation (DESIGN.md §12). Reports discrepancies, never repairs them. */
@Service
public class ReconciliationService {

    static final String SNAPSHOT_CONSTRAINT = "balance_snapshots_account_period_uq";

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final ReconciliationRepository repository;
    private final Clock clock;

    public ReconciliationService(ReconciliationRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Checks R1–R4 for every account created before the period ends, writes the snapshots that don't exist
     * yet, and stores the report. All reads see one REPEATABLE READ snapshot, so postings during the run
     * (dated after the period anyway) don't affect it. A stored snapshot is compared, never overwritten.
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public ReconciliationReport reconcile(ReconciliationPeriod period, Trigger trigger) {
        Instant now = ClockConfig.now(clock);
        if (!period.isClosedAt(now)) {
            throw LedgerException.periodNotClosed(period.toString(), ReconciliationPeriod.containing(now).toString());
        }
        List<AccountFigures> accounts = repository.figures(period);
        List<AccountFigures> toSnapshot = new ArrayList<>();
        List<Discrepancy> discrepancies = new ArrayList<>();
        int verified = 0;

        for (AccountFigures a : accounts) {
            // R1. Without a previous snapshot the opening is computed from entries, so this always holds.
            BigDecimal opening = a.previousClosing() != null ? a.previousClosing() : a.beforeStart();
            BigDecimal rolledForward = opening.add(a.activity());
            if (rolledForward.compareTo(a.closing()) != 0) {
                discrepancies.add(new Discrepancy(Kind.ROLLFORWARD_MISMATCH, a.accountId(), a.currency(),
                        rolledForward, a.closing(), "Opening %s (%s snapshot) + activity %s = %s; recomputed closing %s"
                                .formatted(format(opening, a), period.previous(), format(a.activity(), a),
                                        format(rolledForward, a), format(a.closing(), a))));
            }
            // R2. entry_count catches changes that cancel out in the sum.
            if (a.storedClosing() == null) {
                toSnapshot.add(a);
            } else {
                verified++;
                if (a.storedClosing().compareTo(a.closing()) != 0 || a.storedCount() != a.closingCount()) {
                    discrepancies.add(new Discrepancy(Kind.SNAPSHOT_MISMATCH, a.accountId(), a.currency(),
                            a.storedClosing(), a.closing(), "Stored closing %s (%s); recomputed %s (%s)".formatted(
                                    format(a.storedClosing(), a), entries(a.storedCount()),
                                    format(a.closing(), a), entries(a.closingCount()))));
                }
            }
            // R3
            if (a.balance().compareTo(a.allEntries()) != 0) {
                discrepancies.add(new Discrepancy(Kind.CACHED_BALANCE_MISMATCH, a.accountId(), a.currency(),
                        a.allEntries(), a.balance(), "Cached balance %s; entries sum to %s"
                                .formatted(format(a.balance(), a), format(a.allEntries(), a))));
            }
            // R4
            if (a.type() == AccountType.CUSTOMER && a.closing().signum() < 0) {
                discrepancies.add(new Discrepancy(Kind.NEGATIVE_CLOSING_BALANCE, a.accountId(), a.currency(),
                        null, a.closing(), "Customer account closed %s at %s"
                                .formatted(period, format(a.closing(), a))));
            }
        }

        ReconciliationReport report = new ReconciliationReport(UUID.randomUUID(), period, trigger,
                discrepancies.isEmpty() ? ReconciliationReport.Status.BALANCED
                        : ReconciliationReport.Status.DISCREPANCIES_FOUND,
                accounts.size(), toSnapshot.size(), verified, now, List.copyOf(discrepancies));
        repository.insert(report);
        try {
            for (AccountFigures a : toSnapshot) {
                repository.insertSnapshot(a.accountId(), period, a.closing(), a.closingCount(), report.id(), now);
            }
        } catch (DuplicateKeyException e) {
            // A concurrent run for this period committed its snapshots first. Ours rolls back completely.
            if (SNAPSHOT_CONSTRAINT.equals(Constraints.violated(e))) {
                throw LedgerException.reconciliationInProgress(period.toString());
            }
            throw e;
        }
        log.info("Reconciliation {} of {} ({}): {}, {} discrepancies", report.id(), period, trigger, report.status(),
                discrepancies.size());
        return report;
    }

    @Transactional(readOnly = true)
    public ReconciliationReport get(UUID id) {
        return repository.findById(id).orElseThrow(() -> LedgerException.reconciliationNotFound(id));
    }

    private static String format(BigDecimal amount, AccountFigures account) {
        return Money.formatLenient(amount, account.currency());
    }

    private static String entries(long count) {
        return count == 1 ? "1 entry" : count + " entries";
    }
}
