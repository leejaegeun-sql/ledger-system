package com.symphony.ledger.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Replaces the auto-configured pool with a {@link FreshDatabase}. Importing it also gives
 * {@link FreshDatabaseTestBase} tests their own Spring context, separate from the one on the shared database.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FreshDatabaseConfig {

    @Bean
    FreshDatabase dataSource() {
        return new FreshDatabase();
    }
}
