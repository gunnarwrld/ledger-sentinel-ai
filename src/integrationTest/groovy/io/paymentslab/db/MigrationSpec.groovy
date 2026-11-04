package io.paymentslab.db

import static io.paymentslab.db.Tables.ACCOUNTS
import static io.paymentslab.db.Tables.CALLERS

import com.zaxxer.hikari.HikariDataSource
import io.paymentslab.platform.Migrations
import io.paymentslab.support.TestDatabase
import io.paymentslab.support.TestDatabases
import org.flywaydb.core.api.output.MigrateResult
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import spock.lang.Shared
import spock.lang.Specification

/** Runs the real migrations on an empty PostgreSQL database and attacks the constraints with direct inserts. */
class MigrationSpec extends Specification {

    static final String CHECK_VIOLATION = "23514"

    @Shared TestDatabase database = TestDatabases.createEmpty()
    @Shared HikariDataSource dataSource = database.dataSource
    @Shared DSLContext db = database.dsl
    @Shared MigrateResult firstRun

    def setupSpec() {
        firstRun = Migrations.migrate(dataSource)
    }

    def cleanupSpec() {
        database.close()
    }

    def "a clean database migrates to the latest version"() {
        expect:
        firstRun.success
        firstRun.migrationsExecuted >= 1
        Migrations.flyway(dataSource).info().pending().length == 0
    }

    def "re-running the migrations changes nothing"() {
        when:
        def secondRun = Migrations.migrate(dataSource)

        then:
        secondRun.success
        secondRun.migrationsExecuted == 0
    }

    def "a customer account round-trips through the generated jOOQ classes with its defaults"() {
        given:
        def ownerId = insertCaller("CUSTOMER")
        def accountId = UUID.randomUUID()
        def account = db.newRecord(ACCOUNTS)
        account.id = accountId
        account.ownerId = ownerId
        account.kind = "CUSTOMER"
        account.currency = "EUR"
        account.insert()

        when:
        def row = db.selectFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(accountId)).fetchOne()

        then:
        row.ownerId == ownerId
        row.currency == "EUR"
        row.balanceMinor == 0L
        row.status == "ACTIVE"
        !row.allowNegative
        row.createdAt != null
    }

    def "a SYSTEM account may go negative when allow_negative is set"() {
        when:
        insertAccount(kind: "SYSTEM", hasOwner: false, balance: -500_00L, allowNegative: true)

        then:
        noExceptionThrown()
    }

    def "the schema rejects #scenario"() {
        when:
        insertAccount(kind: kind, hasOwner: hasOwner, currency: currency, balance: balance,
                allowNegative: allowNegative, status: status)

        then:
        def e = thrown(DataAccessException)
        e.sqlState() == CHECK_VIOLATION

        where:
        scenario                                    | kind       | hasOwner | currency | balance | allowNegative | status
        "an unsupported currency"                   | "CUSTOMER" | true     | "USD"    | 0L      | false         | "ACTIVE"
        "a negative CUSTOMER balance"               | "CUSTOMER" | true     | "EUR"    | -1L     | false         | "ACTIVE"
        "a negative SYSTEM balance without the flag"| "SYSTEM"   | false    | "EUR"    | -1L     | false         | "ACTIVE"
        "a CUSTOMER account allowed to go negative" | "CUSTOMER" | true     | "EUR"    | 0L      | true          | "ACTIVE"
        "a CUSTOMER account without an owner"       | "CUSTOMER" | false    | "EUR"    | 0L      | false         | "ACTIVE"
        "a SYSTEM account with an owner"            | "SYSTEM"   | true     | "EUR"    | 0L      | false         | "ACTIVE"
        "an unknown account kind"                   | "SAVINGS"  | true     | "EUR"    | 0L      | false         | "ACTIVE"
        "an unknown account status"                 | "CUSTOMER" | true     | "EUR"    | 0L      | false         | "DORMANT"
    }

    def "the schema rejects a caller with an unknown role"() {
        when:
        insertCaller("ADMIN")

        then:
        def e = thrown(DataAccessException)
        e.sqlState() == CHECK_VIOLATION
    }

    private UUID insertCaller(String role) {
        def caller = db.newRecord(CALLERS)
        caller.id = UUID.randomUUID()
        caller.name = "caller-${caller.id}".toString()
        caller.role = role
        caller.apiKeyHash = UUID.randomUUID().toString().bytes
        caller.insert()
        caller.id
    }

    private void insertAccount(Map args) {
        def account = db.newRecord(ACCOUNTS)
        account.id = UUID.randomUUID()
        account.ownerId = args.hasOwner ? insertCaller("CUSTOMER") : null
        account.kind = args.kind
        account.currency = args.currency ?: "EUR"
        account.balanceMinor = args.balance ?: 0L
        account.allowNegative = args.allowNegative ?: false
        account.status = args.status ?: "ACTIVE"
        account.insert()
    }
}
