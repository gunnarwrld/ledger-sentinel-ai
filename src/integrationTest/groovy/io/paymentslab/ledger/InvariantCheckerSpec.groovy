package io.paymentslab.ledger

import static io.paymentslab.support.LedgerFixtures.customerAccount

import io.paymentslab.money.Money
import io.paymentslab.money.TransferAmount
import io.paymentslab.support.TestDatabase
import io.paymentslab.support.TestDatabases
import spock.lang.Specification

/**
 * The checker must report every kind of damage it claims to detect. Each feature breaks its own
 * database copy on purpose (disabling guards where needed) and expects the matching violation.
 */
class InvariantCheckerSpec extends Specification {

    static final Currency EUR = Currency.getInstance("EUR")

    TestDatabase db = TestDatabases.create()
    LedgerService ledger = new LedgerService()
    InvariantChecker invariants = new InvariantChecker()
    UUID alice = customerAccount(db.dsl)
    UUID bob = customerAccount(db.dsl)

    def cleanup() {
        db.close()
    }

    def "a freshly migrated database has no violations"() {
        expect:
        invariants.check(db.dsl) == []
    }

    def "a ledger written by the service has no violations"() {
        given:
        db.inTransaction { tx -> new FundingService(ledger).fund(tx, alice, TransferAmount.of(50_00, EUR)) }
        db.inTransaction { tx -> ledger.post(tx, transfer(alice, bob, 20_00)) }

        expect:
        invariants.check(db.dsl) == []
    }

    def "reports a balance that no longer matches its postings (I7) and the broken total (I6)"() {
        given:
        db.inTransaction { tx -> new FundingService(ledger).fund(tx, alice, TransferAmount.of(50_00, EUR)) }

        when:
        db.dsl.execute("UPDATE accounts SET balance_minor = balance_minor + 1 WHERE id = ?::uuid", alice)
        def found = invariants.check(db.dsl)

        then:
        found*.invariant() as Set == ["I6", "I7"] as Set
        found.find { it.invariant() == "I7" }.detail().contains(alice.toString())
    }

    def "reports an unbalanced entry (I5) when the balance triggers have been bypassed"() {
        given:
        db.dsl.execute("ALTER TABLE postings DISABLE TRIGGER postings_entry_balanced")
        db.dsl.execute("ALTER TABLE journal_entries DISABLE TRIGGER journal_entries_balanced")
        def entry = UUID.randomUUID()

        when:
        db.inTransaction { tx ->
            tx.execute("INSERT INTO journal_entries (id, kind, currency) VALUES (?::uuid, 'FUNDING', 'EUR')", entry)
            tx.execute("INSERT INTO postings (journal_entry_id, account_id, currency, amount_minor) VALUES (?::uuid, ?::uuid, 'EUR', 100)", entry, alice)
        }
        def found = invariants.check(db.dsl)

        then:
        found.any { it.invariant() == "I5" && it.detail().contains(entry.toString()) }
        found.any { it.invariant() == "I5" && it.detail().contains("postings_entry_balanced") }
    }

    def "reports a customer account below zero (I8) when the CHECK constraint has been dropped"() {
        given:
        db.dsl.execute("ALTER TABLE accounts DROP CONSTRAINT accounts_no_overdraft")

        when:
        db.dsl.execute("UPDATE accounts SET balance_minor = -1 WHERE id = ?::uuid", alice)

        then:
        invariants.check(db.dsl).any { it.invariant() == "I8" && it.detail().contains(alice.toString()) }
    }

    def "reports a disabled append-only trigger (I9)"() {
        when:
        db.dsl.execute("ALTER TABLE postings DISABLE TRIGGER postings_append_only")

        then:
        invariants.check(db.dsl).any { it.invariant() == "I9" && it.detail().contains("postings_append_only") }
    }

    def "reports a reversal that doesn't mirror the entry it reverses (I9)"() {
        given:
        db.inTransaction { tx -> new FundingService(ledger).fund(tx, alice, TransferAmount.of(50_00, EUR)) }
        def original = db.inTransaction { tx -> ledger.post(tx, transfer(alice, bob, 20_00)) }.entryId()
        def wrong = UUID.randomUUID()

        when: "a balanced reversal for the wrong amount, written with plain SQL that keeps every balance consistent"
        db.inTransaction { tx ->
            tx.execute("INSERT INTO journal_entries (id, kind, currency, reverses_id) VALUES (?::uuid, 'REVERSAL', 'EUR', ?::uuid)", wrong, original)
            tx.execute("""INSERT INTO postings (journal_entry_id, account_id, currency, amount_minor)
                          VALUES (?::uuid, ?::uuid, 'EUR', -500), (?::uuid, ?::uuid, 'EUR', 500)""", wrong, bob, wrong, alice)
            tx.execute("UPDATE accounts SET balance_minor = balance_minor - 500 WHERE id = ?::uuid", bob)
            tx.execute("UPDATE accounts SET balance_minor = balance_minor + 500 WHERE id = ?::uuid", alice)
        }
        def found = invariants.check(db.dsl)

        then: "only the mirror check notices: every entry balances and every balance matches its postings"
        found*.invariant() == ["I9"]
        found[0].detail().contains(wrong.toString())
    }

    private static JournalEntryRequest transfer(UUID from, UUID to, long minor) {
        new JournalEntryRequest(EntryKind.TRANSFER, EUR, null, [
                new Posting(from, Money.of(-minor, EUR)),
                new Posting(to, Money.of(minor, EUR))])
    }
}
