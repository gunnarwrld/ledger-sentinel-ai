package io.paymentslab.support

import com.zaxxer.hikari.HikariDataSource
import io.paymentslab.platform.AppConfig
import org.jooq.Configuration
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.TransactionalCallable
import org.jooq.impl.DSL

/** A database owned by one spec (or one feature). Closing it drops the database. */
final class TestDatabase implements AutoCloseable {

    /** The API only talks to Redis in /health/ready, so API specs point it at a closed port. */
    private static final String UNUSED_REDIS = "redis://127.0.0.1:1"

    final String name
    final String jdbcUrl
    final HikariDataSource dataSource
    final DSLContext dsl
    private final String username
    private final String password

    TestDatabase(String name, String jdbcUrl, String username, String password) {
        this.name = name
        this.jdbcUrl = jdbcUrl
        this.username = username
        this.password = password
        this.dataSource = new HikariDataSource(
                jdbcUrl: jdbcUrl, username: username, password: password, poolName: name, maximumPoolSize: 10)
        this.dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
    }

    /** Runs the closure in one transaction and returns its result; an exception rolls back. */
    def <T> T inTransaction(Closure<T> work) {
        dsl.transactionResult({ Configuration configuration -> work(DSL.using(configuration)) } as TransactionalCallable<T>)
    }

    /** Config for starting the whole app (same wiring as Main) on this database and an ephemeral port. */
    AppConfig appConfig(int poolSize = 4) {
        new AppConfig("127.0.0.1", 0, jdbcUrl, username, password, poolSize, UNUSED_REDIS)
    }

    @Override
    void close() {
        dataSource.close()
        TestDatabases.drop(name)
    }
}
