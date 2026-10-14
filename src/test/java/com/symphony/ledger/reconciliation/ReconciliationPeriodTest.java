package com.symphony.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.symphony.ledger.common.LedgerException;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

/** Period parsing and bounds (DESIGN.md §8.12, §12.1). */
class ReconciliationPeriodTest {

    @Test
    void parse_acceptsYearDashTwoDigitMonth() {
        ReconciliationPeriod period = ReconciliationPeriod.parse("2026-09");

        assertThat(period.toString()).isEqualTo("2026-09");
        assertThat(period.firstDay()).isEqualTo(LocalDate.parse("2026-09-01"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"2026-13", "2026-00", "2026-9", "202609", "2026-09-01", "09-2026", " 2026-09",
            "2026-09 ", "+2026-09", "26-09", "2026/09", "abcd-ef"})
    void parse_rejectsAnythingElse_with400InvalidPeriod(String value) {
        assertThatThrownBy(() -> ReconciliationPeriod.parse(value)).isInstanceOfSatisfying(LedgerException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.code()).isEqualTo("invalid-period");
        });
    }

    @Test
    void bounds_areAHalfOpenUtcMonth() {
        ReconciliationPeriod september = ReconciliationPeriod.parse("2026-09");
        assertThat(september.start()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(september.end()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));

        ReconciliationPeriod december = ReconciliationPeriod.parse("2026-12");
        assertThat(december.end()).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
        assertThat(ReconciliationPeriod.parse("2027-01").previous()).isEqualTo(december);
    }

    @Test
    void closed_onlyOnceTheMonthHasEnded() {
        ReconciliationPeriod october = ReconciliationPeriod.parse("2026-10");

        assertThat(october.isClosedAt(Instant.parse("2026-10-14T06:30:00Z"))).isFalse();
        assertThat(october.isClosedAt(Instant.parse("2026-10-31T23:59:59.999999Z"))).isFalse();
        assertThat(october.isClosedAt(Instant.parse("2026-11-01T00:00:00Z"))).isTrue();
        assertThat(ReconciliationPeriod.parse("2026-12").isClosedAt(Instant.parse("2026-11-01T00:00:00Z"))).isFalse();
    }

    @Test
    void containing_isTheUtcMonthOfTheInstant() {
        assertThat(ReconciliationPeriod.containing(Instant.parse("2026-11-01T00:05:00Z")))
                .isEqualTo(ReconciliationPeriod.parse("2026-11"));
        assertThat(ReconciliationPeriod.containing(Instant.parse("2026-10-31T23:59:59Z")))
                .isEqualTo(ReconciliationPeriod.parse("2026-10"));
    }
}
