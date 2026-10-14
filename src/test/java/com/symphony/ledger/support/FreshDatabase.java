package com.symphony.ledger.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * The application's DataSource in {@link FreshDatabaseTestBase} tests: a pool on a brand-new PostgreSQL
 * database in the shared container. {@link #recreate()} moves it to another brand-new database and drops
 * the previous one, so rows a test inserts (which can never be deleted: entries are append-only) are
 * gone before the next test starts.
 *
 * <p>Every bean in the context (JdbcClient, JdbcTemplate, the transaction manager, Flyway) holds this
 * object rather than the pool behind it, so after {@code recreate()} they all use the new database.
 */
public final class FreshDatabase extends DelegatingDataSource implements DisposableBean {

    private HikariDataSource pool;
    private String name;

    public FreshDatabase() {
        recreate();
    }

    /** Switches to a new, empty database. The caller runs the migrations. */
    public synchronized void recreate() {
        HikariDataSource previousPool = pool;
        String previousName = name;

        name = "fresh_" + UUID.randomUUID().toString().replace("-", "");
        admin("CREATE DATABASE " + name);
        pool = pool(name);
        setTargetDataSource(pool);

        if (previousPool != null) {
            previousPool.close();
            admin("DROP DATABASE " + previousName + " WITH (FORCE)");
        }
    }

    /** The database every connection from this DataSource currently goes to. */
    public synchronized String name() {
        return name;
    }

    @Override
    public synchronized void destroy() {
        pool.close();
    }

    private static HikariDataSource pool(String database) {
        HikariConfig config = new HikariConfig();
        config.setPoolName(database);
        config.setJdbcUrl("jdbc:postgresql://%s:%d/%s".formatted(IntegrationTestBase.POSTGRES.getHost(),
                IntegrationTestBase.POSTGRES.getMappedPort(5432), database));
        config.setUsername(IntegrationTestBase.POSTGRES.getUsername());
        config.setPassword(IntegrationTestBase.POSTGRES.getPassword());
        return new HikariDataSource(config);
    }

    /** CREATE and DROP DATABASE can't run in a transaction, or while connected to that database. */
    private static void admin(String sql) {
        try (Connection connection = DriverManager.getConnection(IntegrationTestBase.POSTGRES.getJdbcUrl(),
                IntegrationTestBase.POSTGRES.getUsername(), IntegrationTestBase.POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(sql + " failed", e);
        }
    }
}
