package io.paymentslab.platform

import io.paymentslab.support.TestDatabase
import io.paymentslab.support.TestDatabases
import org.jooq.exception.DataAccessException
import spock.lang.Shared
import spock.lang.Specification

/** The app's connection pool. Error messages end up in logs, so they must not quote row data (references, threat T9). */
class DatabaseSpec extends Specification {

    static final String BAD_INSERT =
            "INSERT INTO accounts (id, owner_id, kind, currency) VALUES (gen_random_uuid(), NULL, 'CUSTOMER', 'EUR')"

    @Shared TestDatabase db = TestDatabases.create()

    def cleanupSpec() {
        db?.close()
    }

    def "a constraint violation names the constraint but doesn't quote the failing row"() {
        given:
        def database = new Database(db.appConfig())

        when:
        database.dsl().execute(BAD_INSERT)

        then:
        def e = thrown(DataAccessException)
        e.message.contains("accounts_owner_matches_kind")
        !e.message.contains("Failing row")

        cleanup:
        database?.close()
    }

    def "control: with the driver's defaults, the same error quotes the row"() {
        when:
        db.dsl.execute(BAD_INSERT)

        then:
        def e = thrown(DataAccessException)
        e.message.contains("Failing row")
    }
}
