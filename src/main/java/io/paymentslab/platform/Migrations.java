package io.paymentslab.platform;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * Applies db/migration/*.sql. Run as its own step ({@code migrate}), not on every server start.
 *
 * <p>Flyway holds one connection and asks for a second, so a pooled DataSource needs at least two.
 */
public final class Migrations {

    private static final String LOCATION = "classpath:db/migration";

    private Migrations() {}

    public static Flyway flyway(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations(LOCATION).load();
    }

    /** Lets Flyway open its own connections, so migrating never depends on DB_POOL_SIZE. */
    public static Flyway flyway(String url, String user, String password) {
        return Flyway.configure().dataSource(url, user, password).locations(LOCATION).load();
    }

    public static MigrateResult migrate(DataSource dataSource) {
        return flyway(dataSource).migrate();
    }
}
