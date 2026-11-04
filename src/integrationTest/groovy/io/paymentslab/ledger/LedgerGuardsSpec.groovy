package io.paymentslab.ledger

import static io.paymentslab.support.LedgerFixtures.customerAccount

import io.paymentslab.support.TestDatabase
import io.paymentslab.support.TestDatabases
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import spock.lang.Shared
import spock.lang.Specification

/**
 * Attacks the ledger tables with plain SQL, bypassing the application, to prove the database
 * itself enforces the journal rules (I3, I5, I9). Features that change data run last.
 */
class LedgerGuardsSpec extends Specification {

    static final String CHECK_VIOLATION = "23514"
    static final String FOREIGN_KEY_VIOLATION = "23503"
    static final String UNIQUE_VIOLATION = "23505"
    static final String NOT_ALLOWED = "42501" // raised by the append-only triggers

    @Shared TestDatabase db = TestDatabases.create()
    @Shared UUID alice
    @Shared UUID committedEntry

    def setupSpec() {
        alice = customerAccount(db.dsl)
        committedEntry = db.inTransaction { tx -> fundDirectly(tx, alice, 500) }
    }

    def cleanupSpec() {
        db.close()
    }

    def "a balanced entry with matching balance updates commits (positive control)"() {
        when:
        def entry = db.inTransaction { tx -> fundDirectly(tx, alice, 250) }

        then:
        db.dsl.fetchCount(db.dsl.selectFrom("postings").where("journal_entry_id = ?::uuid", entry)) == 2
    }

    def "an unbalanced entry is rejected when the transaction commits, not before"() {
        given:
        UUID entry = null
        boolean insertsAccepted = false

        when:
        db.inTransaction { tx ->
            entry = insertEntry(tx, "FUNDING", null)
            insertPosting(tx, entry, SystemAccounts.FUNDING, -500)
            insertPosting(tx, entry, alice, 400)
            insertsAccepted = true
        }

        then:
        def e = thrown(DataAccessException)
        e.sqlState() == CHECK_VIOLATION
        insertsAccepted
        !entryExists(entry)
    }

    def "an entry with no postings is rejected when the transaction commits"() {
        given:
        UUID entry = null

        when:
        db.inTransaction { tx -> entry = insertEntry(tx, "FUNDING", null) }

        then:
        def e = thrown(DataAccessException)
        e.sqlState() == CHECK_VIOLATION
        !entryExists(entry)
    }

    def "a posting in a different currency from its account is rejected"() {
        when:
        db.inTransaction { tx ->
            def entry = insertEntry(tx, "FUNDING", null)
            insertPosting(tx, entry, SystemAccounts.FUNDING, -500, "USD")
            insertPosting(tx, entry, alice, 500, "USD")
        }

        then:
        def e = thrown(DataAccessException)
        e.sqlState() == FOREIGN_KEY_VIOLATION
    }

    def "a zero posting is rejected"() {
        when:
        db.inTransaction { tx ->
            def entry = insertEntry(tx, "FUNDING", null)
            insertPosting(tx, entry, SystemAccounts.FUNDING, 0)
            insertPosting(tx, entry, alice, 0)
        }

        then:
        def e = thrown(DataAccessException)
        e.sqlState() == CHECK_VIOLATION
    }

    def "a reversal must name the entry it reverses"() {
        when:
        db.inTransaction { tx ->
            def entry = insertEntry(tx, "REVERSAL", null)
            insertPosting(tx, entry, alice, -500)
            insertPosting(tx, entry, SystemAccounts.FUNDING, 500)
        }

        then:
        def e = thrown(DataAccessException)
        e.sqlState() == CHECK_VIOLATION
    }

    def "an entry can be reversed only once"() {
        when:
        db.inTransaction { tx ->
            2.times {
                def reversal = insertEntry(tx, "REVERSAL", committedEntry)
                insertPosting(tx, reversal, alice, -500)
                insertPosting(tx, reversal, SystemAccounts.FUNDING, 500)
            }
        }

        then:
        def e = thrown(DataAccessException)
        e.sqlState() == UNIQUE_VIOLATION
    }

    def "ledger history can't be changed: #statement"() {
        when:
        db.dsl.execute(statement.replace(":entry", committedEntry.toString()))

        then:
        def e = thrown(DataAccessException)
        e.sqlState() == NOT_ALLOWED

        where:
        statement << [
                "UPDATE postings SET amount_minor = amount_minor * 2 WHERE journal_entry_id = ':entry'",
                "DELETE FROM postings WHERE journal_entry_id = ':entry'",
                "UPDATE journal_entries SET kind = 'TRANSFER' WHERE id = ':entry'",
                "DELETE FROM journal_entries WHERE id = ':entry'",
                "TRUNCATE postings",
                "TRUNCATE journal_entries CASCADE",
                "TRUNCATE accounts CASCADE",
        ]
    }

    /** A complete, correct funding written by hand: entry, two postings, both balances. */
    private static UUID fundDirectly(DSLContext tx, UUID account, long minor) {
        def entry = insertEntry(tx, "FUNDING", null)
        insertPosting(tx, entry, SystemAccounts.FUNDING, -minor)
        insertPosting(tx, entry, account, minor)
        tx.execute("UPDATE accounts SET balance_minor = balance_minor - ? WHERE id = ?::uuid", minor, SystemAccounts.FUNDING)
        tx.execute("UPDATE accounts SET balance_minor = balance_minor + ? WHERE id = ?::uuid", minor, account)
        entry
    }

    private static UUID insertEntry(DSLContext tx, String kind, UUID reverses) {
        def id = UUID.randomUUID()
        tx.execute("INSERT INTO journal_entries (id, kind, currency, reverses_id) VALUES (?::uuid, ?, 'EUR', ?::uuid)",
                id, kind, reverses)
        id
    }

    private static void insertPosting(DSLContext tx, UUID entry, UUID account, long minor, String currency = "EUR") {
        tx.execute("INSERT INTO postings (journal_entry_id, account_id, currency, amount_minor) VALUES (?::uuid, ?::uuid, ?, ?)",
                entry, account, currency, minor)
    }

    private boolean entryExists(UUID entry) {
        db.dsl.fetchExists(db.dsl.selectOne().from("journal_entries").where("id = ?::uuid", entry))
    }
}
