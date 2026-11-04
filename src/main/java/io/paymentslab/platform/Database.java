package io.paymentslab.platform;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

/** Connection pool plus the jOOQ entry point. */
public final class Database implements AutoCloseable {

    private final HikariDataSource dataSource;
    private final DSLContext dsl;

    public Database(AppConfig config) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("payments-db");
        hikari.setJdbcUrl(config.dbUrl());
        hikari.setUsername(config.dbUser());
        hikari.setPassword(config.dbPassword());
        hikari.setMaximumPoolSize(config.dbPoolSize());
        // Fail fast: waiting longer than this for a connection means we are overloaded or the
        // database is gone, and the caller should get a 503 rather than hang.
        hikari.setConnectionTimeout(1_000);
        hikari.setValidationTimeout(500);
        // Start even if PostgreSQL is down; readiness reports it instead of the process dying.
        hikari.setInitializationFailTimeout(-1);
        // Server error details can quote the failing row (for example a transfer's free-text
        // reference), and error messages end up in logs. Keep only the message itself.
        hikari.addDataSourceProperty("logServerErrorDetail", "false");
        this.dataSource = new HikariDataSource(hikari);
        this.dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public DSLContext dsl() {
        return dsl;
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
