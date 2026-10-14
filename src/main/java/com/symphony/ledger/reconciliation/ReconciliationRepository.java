package com.symphony.ledger.reconciliation;

import com.symphony.ledger.account.AccountType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReconciliationRepository {

    private final JdbcClient jdbc;

    public ReconciliationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * What the run needs for every account created before the period ends, system accounts included,
     * in id order. Snapshots are unique per account and period, so the joins never multiply entries.
     */
    public List<AccountFigures> figures(ReconciliationPeriod period) {
        return jdbc.sql("""
                SELECT a.id, a.currency, a.type, a.balance,
                       COALESCE(SUM(e.amount) FILTER (WHERE e.created_at < :start), 0)                         AS before_start,
                       COALESCE(SUM(e.amount) FILTER (WHERE e.created_at >= :start AND e.created_at < :end), 0) AS activity,
                       COALESCE(SUM(e.amount) FILTER (WHERE e.created_at < :end), 0)                           AS closing,
                       COUNT(e.id) FILTER (WHERE e.created_at < :end)                                          AS closing_count,
                       COALESCE(SUM(e.amount), 0)                                                              AS all_entries,
                       prev.closing_balance AS previous_closing,
                       cur.closing_balance  AS stored_closing,
                       cur.entry_count      AS stored_count
                FROM accounts a
                LEFT JOIN entries e              ON e.account_id = a.id
                LEFT JOIN balance_snapshots prev ON prev.account_id = a.id AND prev.period = :previous
                LEFT JOIN balance_snapshots cur  ON cur.account_id = a.id AND cur.period = :period
                WHERE a.created_at < :end
                GROUP BY a.id, prev.closing_balance, cur.closing_balance, cur.entry_count
                ORDER BY a.id
                """)
                .param("start", period.start().atOffset(ZoneOffset.UTC))
                .param("end", period.end().atOffset(ZoneOffset.UTC))
                .param("period", period.firstDay())
                .param("previous", period.previous().firstDay())
                .query((rs, n) -> new AccountFigures(
                        rs.getObject("id", UUID.class),
                        Currency.getInstance(rs.getString("currency")),
                        AccountType.valueOf(rs.getString("type")),
                        rs.getBigDecimal("balance"),
                        rs.getBigDecimal("before_start"),
                        rs.getBigDecimal("activity"),
                        rs.getBigDecimal("closing"),
                        rs.getLong("closing_count"),
                        rs.getBigDecimal("all_entries"),
                        rs.getBigDecimal("previous_closing"),
                        rs.getBigDecimal("stored_closing"),
                        rs.getObject("stored_count", Long.class)))
                .list();
    }

    /** The run's row and its discrepancies. Snapshots reference the row, so it goes in first. */
    public void insert(ReconciliationReport report) {
        jdbc.sql("""
                INSERT INTO reconciliations (id, period, trigger, status, accounts_checked, snapshots_created,
                                             snapshots_verified, discrepancy_count, created_at)
                VALUES (:id, :period, :trigger, :status, :accountsChecked, :snapshotsCreated,
                        :snapshotsVerified, :discrepancyCount, :createdAt)
                """)
                .param("id", report.id())
                .param("period", report.period().firstDay())
                .param("trigger", report.trigger().name())
                .param("status", report.status().name())
                .param("accountsChecked", report.accountsChecked())
                .param("snapshotsCreated", report.snapshotsCreated())
                .param("snapshotsVerified", report.snapshotsVerified())
                .param("discrepancyCount", report.discrepancies().size())
                .param("createdAt", report.createdAt().atOffset(ZoneOffset.UTC))
                .update();
        for (ReconciliationReport.Discrepancy d : report.discrepancies()) {
            jdbc.sql("""
                    INSERT INTO reconciliation_discrepancies (reconciliation_id, account_id, kind, expected, actual, detail)
                    VALUES (:reconciliationId, :accountId, :kind, :expected, :actual, :detail)
                    """)
                    .param("reconciliationId", report.id())
                    .param("accountId", d.accountId())
                    .param("kind", d.kind().name())
                    .param("expected", d.expected())
                    .param("actual", d.actual())
                    .param("detail", d.detail())
                    .update();
        }
    }

    /** Raises the unique violation on {@code balance_snapshots_account_period_uq} if another run got there first. */
    public void insertSnapshot(UUID accountId, ReconciliationPeriod period, BigDecimal closing, long entryCount,
            UUID reconciliationId, Instant createdAt) {
        jdbc.sql("""
                INSERT INTO balance_snapshots (account_id, period, closing_balance, entry_count, reconciliation_id, created_at)
                VALUES (:accountId, :period, :closing, :entryCount, :reconciliationId, :createdAt)
                """)
                .param("accountId", accountId)
                .param("period", period.firstDay())
                .param("closing", closing)
                .param("entryCount", entryCount)
                .param("reconciliationId", reconciliationId)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    /** Discrepancies in the order the run found them. */
    public Optional<ReconciliationReport> findById(UUID id) {
        List<ReconciliationReport.Discrepancy> discrepancies = jdbc.sql("""
                SELECT d.kind, d.account_id, a.currency, d.expected, d.actual, d.detail
                FROM reconciliation_discrepancies d JOIN accounts a ON a.id = d.account_id
                WHERE d.reconciliation_id = :id
                ORDER BY d.id
                """)
                .param("id", id)
                .query((rs, n) -> new ReconciliationReport.Discrepancy(
                        ReconciliationReport.Kind.valueOf(rs.getString("kind")),
                        rs.getObject("account_id", UUID.class),
                        Currency.getInstance(rs.getString("currency")),
                        rs.getBigDecimal("expected"),
                        rs.getBigDecimal("actual"),
                        rs.getString("detail")))
                .list();
        return jdbc.sql("""
                SELECT id, period, trigger, status, accounts_checked, snapshots_created, snapshots_verified, created_at
                FROM reconciliations WHERE id = :id
                """)
                .param("id", id)
                .query((rs, n) -> new ReconciliationReport(
                        rs.getObject("id", UUID.class),
                        new ReconciliationPeriod(YearMonth.from(rs.getObject("period", LocalDate.class))),
                        Trigger.valueOf(rs.getString("trigger")),
                        ReconciliationReport.Status.valueOf(rs.getString("status")),
                        rs.getInt("accounts_checked"),
                        rs.getInt("snapshots_created"),
                        rs.getInt("snapshots_verified"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        discrepancies))
                .optional();
    }

    /**
     * One account's sums of entries: before the period, inside it, and up to its end (with the count), plus
     * all of them. {@code previousClosing} is the previous period's snapshot; {@code storedClosing} and
     * {@code storedCount} this period's. Each is null when that snapshot doesn't exist.
     */
    public record AccountFigures(UUID accountId, Currency currency, AccountType type, BigDecimal balance,
            BigDecimal beforeStart, BigDecimal activity, BigDecimal closing, long closingCount, BigDecimal allEntries,
            BigDecimal previousClosing, BigDecimal storedClosing, Long storedCount) {
    }
}
