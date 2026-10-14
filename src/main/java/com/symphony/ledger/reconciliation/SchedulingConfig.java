package com.symphony.ledger.reconciliation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled}, i.e. {@link MonthlyReconciliationJob}, unless
 * {@code ledger.reconciliation.scheduling-enabled} is false, as it is in tests (DESIGN.md §12.4).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBooleanProperty(name = "ledger.reconciliation.scheduling-enabled", matchIfMissing = true)
class SchedulingConfig {
}
