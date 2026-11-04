package io.paymentslab.support

import com.zaxxer.hikari.HikariDataSource
import io.paymentslab.platform.Migrations
import java.sql.DriverManager
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * One PostgreSQL container per test JVM. A template database is migrated once and every spec
 * gets its own copy ({@code CREATE DATABASE … TEMPLATE}). Copies are needed because the ledger
 * tables are append-only, so specs can't clean up by deleting rows. Ryuk removes the container
 * when the JVM exits.
 */
final class TestDatabases {

    private static final String TEMPLATE = "payments_template"
    private static final PostgreSQLContainer POSTGRES = TestContainers.postgres()
    private static boolean templateReady // guarded by the class lock

    private TestDatabases() {}

    /** A fresh database with every migration applied. */
    static synchronized TestDatabase create() {
        ensureTemplate()
        newDatabase("TEMPLATE $TEMPLATE")
    }

    /** A fresh database with no migrations applied. */
    static synchronized TestDatabase createEmpty() {
        start()
        newDatabase("")
    }

    static synchronized void drop(String name) {
        adminSql("DROP DATABASE IF EXISTS $name WITH (FORCE)")
    }

    private static TestDatabase newDatabase(String clause) {
        String name = "spec_" + UUID.randomUUID().toString().replace("-", "")
        adminSql("CREATE DATABASE $name $clause")
        new TestDatabase(name, urlFor(name), POSTGRES.username, POSTGRES.password)
    }

    private static void ensureTemplate() {
        start()
        if (templateReady) {
            return
        }
        adminSql("CREATE DATABASE $TEMPLATE")
        // Flyway holds one connection and asks for a second, so the pool needs at least 2.
        // The template must have no open connections when it is copied, so close the pool after.
        def dataSource = new HikariDataSource(
                jdbcUrl: urlFor(TEMPLATE), username: POSTGRES.username, password: POSTGRES.password,
                maximumPoolSize: 3)
        try {
            Migrations.migrate(dataSource)
        } finally {
            dataSource.close()
        }
        templateReady = true
    }

    private static void start() {
        if (!POSTGRES.running) {
            POSTGRES.start()
        }
    }

    private static void adminSql(String sql) {
        DriverManager.getConnection(POSTGRES.jdbcUrl, POSTGRES.username, POSTGRES.password).withCloseable { connection ->
            connection.createStatement().withCloseable { it.execute(sql) }
        }
    }

    private static String urlFor(String database) {
        "jdbc:postgresql://${POSTGRES.host}:${POSTGRES.getMappedPort(5432)}/$database"
    }
}
