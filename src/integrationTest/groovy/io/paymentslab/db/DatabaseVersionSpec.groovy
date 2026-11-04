package io.paymentslab.db

import io.paymentslab.support.TestDatabase
import io.paymentslab.support.TestDatabases
import org.jooq.SQLDialect
import spock.lang.Shared
import spock.lang.Specification

/**
 * DESIGN D11 / ADR 0003: the free jOOQ edition has a single, unversioned POSTGRES dialect that
 * targets recent servers. The server image used by codegen and tests must be one it supports.
 */
class DatabaseVersionSpec extends Specification {

    @Shared TestDatabase database = TestDatabases.create()

    def cleanupSpec() {
        database.close()
    }

    def "jOOQ's POSTGRES dialect supports the PostgreSQL server we run"() {
        given:
        def connection = database.dataSource.connection
        def meta = connection.metaData

        expect:
        SQLDialect.POSTGRES.supportsDatabaseVersion(
                meta.databaseMajorVersion, meta.databaseMinorVersion, meta.databaseProductVersion)

        cleanup:
        connection?.close()
    }
}
