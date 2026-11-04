package io.paymentslab.ledger

import static io.paymentslab.db.Tables.JOURNAL_ENTRIES
import static io.paymentslab.db.Tables.POSTINGS
import static io.paymentslab.support.LedgerFixtures.balance
import static io.paymentslab.support.LedgerFixtures.customerAccount

import io.paymentslab.money.Money
import io.paymentslab.money.TransferAmount
import io.paymentslab.support.Concurrency
import io.paymentslab.support.TestDatabase
import io.paymentslab.support.TestDatabases
import spock.lang.Specification

/** LedgerService against real PostgreSQL. Every feature gets its own database copy. */
class LedgerServiceSpec extends Specification {

    static final Currency EUR = Currency.getInstance("EUR")

    TestDatabase db = TestDatabases.create()
    LedgerService ledger = new LedgerService()
    FundingService funding = new FundingService(ledger)
    InvariantChecker invariants = new InvariantChecker()

    def cleanup() {
        db.close()
    }

    def "funding posts one balanced entry and moves both balances"() {
        given:
        def alice = customerAccount(db.dsl)

        when:
        def posted = db.inTransaction { tx -> funding.fund(tx, alice, TransferAmount.of(100_00, EUR)) }

        then:
        balance(db.dsl, alice) == 100_00
        balance(db.dsl, SystemAccounts.FUNDING) == -100_00
        postingsOf(posted.entryId()) == [(SystemAccounts.FUNDING): -100_00L, (alice): 100_00L]
        entryKind(posted.entryId()) == "FUNDING"
        invariants.check(db.dsl) == []
    }

    def "posts a transfer between two customer accounts"() {
        given:
        def alice = customerAccount(db.dsl)
        def bob = customerAccount(db.dsl)
        db.inTransaction { tx -> funding.fund(tx, alice, TransferAmount.of(100_00, EUR)) }

        when:
        def posted = db.inTransaction { tx -> ledger.post(tx, entry(EntryKind.TRANSFER, alice, bob, 25_00)) }

        then:
        posted.balancesAfter() == [(alice): 75_00L, (bob): 25_00L]
        balance(db.dsl, alice) == 75_00
        balance(db.dsl, bob) == 25_00
        invariants.check(db.dsl) == []
    }

    def "a posting that would overdraw a customer account is refused before anything is written"() {
        given:
        def alice = customerAccount(db.dsl)
        def bob = customerAccount(db.dsl)
        db.inTransaction { tx -> funding.fund(tx, alice, TransferAmount.of(10_00, EUR)) }
        InsufficientFundsException refused = null
        int entriesBefore = 0
        int entriesAfter = 0
        boolean transactionStillUsable = false

        when: "the refusal is caught inside the same transaction"
        db.inTransaction { tx ->
            entriesBefore = tx.fetchCount(JOURNAL_ENTRIES)
            try {
                ledger.post(tx, entry(EntryKind.TRANSFER, alice, bob, 25_00))
            } catch (InsufficientFundsException e) {
                refused = e
            }
            entriesAfter = tx.fetchCount(JOURNAL_ENTRIES)
            // No SQL error happened, so the caller can still use the transaction (e.g. to record a decline).
            transactionStillUsable = tx.fetchValue("SELECT 1") == 1
        }

        then:
        refused?.accountId() == alice
        entriesAfter == entriesBefore
        transactionStillUsable
        balance(db.dsl, alice) == 10_00
        balance(db.dsl, bob) == 0
    }

    def "a posting to an unknown account is refused"() {
        given:
        def alice = customerAccount(db.dsl)
        def ghost = UUID.randomUUID()

        when:
        db.inTransaction { tx -> ledger.post(tx, entry(EntryKind.FUNDING, SystemAccounts.FUNDING, ghost, 100)) }

        then:
        def e = thrown(AccountNotFoundException)
        e.accountId() == ghost
        db.dsl.fetchCount(JOURNAL_ENTRIES) == 0
    }

    def "a posting to an account that isn't ACTIVE is refused"() {
        given:
        def alice = customerAccount(db.dsl)
        def bob = customerAccount(db.dsl)
        db.inTransaction { tx -> funding.fund(tx, alice, TransferAmount.of(10_00, EUR)) }
        db.dsl.execute("UPDATE accounts SET status = 'FROZEN' WHERE id = ?::uuid", bob)

        when:
        db.inTransaction { tx -> ledger.post(tx, entry(EntryKind.TRANSFER, alice, bob, 1_00)) }

        then:
        def e = thrown(AccountNotActiveException)
        e.accountId() == bob
        balance(db.dsl, alice) == 10_00
    }

    def "a balance overflow is refused instead of wrapping"() {
        given: "an account funded to just below the largest long"
        def alice = customerAccount(db.dsl)
        db.inTransaction { tx -> ledger.post(tx, entry(EntryKind.FUNDING, SystemAccounts.FUNDING, alice, Long.MAX_VALUE - 5)) }

        when:
        db.inTransaction { tx -> ledger.post(tx, entry(EntryKind.FUNDING, SystemAccounts.FUNDING, alice, 10)) }

        then:
        thrown(ArithmeticException)
        balance(db.dsl, alice) == Long.MAX_VALUE - 5
        invariants.check(db.dsl) == []
    }

    def "postings in opposite directions never deadlock"() {
        given:
        def alice = customerAccount(db.dsl)
        def bob = customerAccount(db.dsl)
        [alice, bob].each { account ->
            db.inTransaction { tx -> funding.fund(tx, account, TransferAmount.of(1_000_00, EUR)) }
        }

        when: "four threads, two per direction, each post 200 one-cent transfers, released together"
        def outcome = Concurrency.run(4) { int worker ->
            def (from, to) = worker % 2 == 0 ? [alice, bob] : [bob, alice]
            200.times { db.inTransaction { tx -> ledger.post(tx, entry(EntryKind.TRANSFER, from, to, 1)) } }
        }

        then:
        outcome.failures == []
        balance(db.dsl, alice) + balance(db.dsl, bob) == 2_000_00
        invariants.check(db.dsl) == []
    }

    def "reverse posts the exact mirror of an entry, linked to it, and restores both balances"() {
        given:
        def alice = customerAccount(db.dsl)
        def bob = customerAccount(db.dsl)
        db.inTransaction { tx -> funding.fund(tx, alice, TransferAmount.of(100_00, EUR)) }
        def original = db.inTransaction { tx -> ledger.post(tx, entry(EntryKind.TRANSFER, alice, bob, 40_00)) }

        when:
        def reversal = db.inTransaction { tx -> ledger.reverse(tx, original.entryId()) }

        then:
        entryKind(reversal.entryId()) == "REVERSAL"
        reversedEntryOf(reversal.entryId()) == original.entryId()
        postingsOf(reversal.entryId()) == [(alice): 40_00L, (bob): -40_00L]
        reversal.balancesAfter() == [(alice): 100_00L, (bob): 0L]
        balance(db.dsl, alice) == 100_00
        balance(db.dsl, bob) == 0
        invariants.check(db.dsl) == []
    }

    def "a reversal may take the money back from a FROZEN account (ADR 0010)"() {
        given:
        def alice = customerAccount(db.dsl)
        def bob = customerAccount(db.dsl)
        db.inTransaction { tx -> funding.fund(tx, alice, TransferAmount.of(100_00, EUR)) }
        def original = db.inTransaction { tx -> ledger.post(tx, entry(EntryKind.TRANSFER, alice, bob, 40_00)) }
        db.dsl.execute("UPDATE accounts SET status = 'FROZEN' WHERE id = ?::uuid", bob)

        when:
        db.inTransaction { tx -> ledger.reverse(tx, original.entryId()) }

        then:
        balance(db.dsl, alice) == 100_00
        balance(db.dsl, bob) == 0
        invariants.check(db.dsl) == []
    }

    def "nothing posts to a CLOSED account, not even a reversal"() {
        given:
        def alice = customerAccount(db.dsl)
        def bob = customerAccount(db.dsl)
        db.inTransaction { tx -> funding.fund(tx, alice, TransferAmount.of(100_00, EUR)) }
        def original = db.inTransaction { tx -> ledger.post(tx, entry(EntryKind.TRANSFER, alice, bob, 40_00)) }
        db.dsl.execute("UPDATE accounts SET status = 'CLOSED' WHERE id = ?::uuid", bob)
        def entriesBefore = db.dsl.fetchCount(JOURNAL_ENTRIES)

        when:
        db.inTransaction { tx -> ledger.reverse(tx, original.entryId()) }

        then:
        def e = thrown(AccountNotActiveException)
        e.accountId() == bob
        db.dsl.fetchCount(JOURNAL_ENTRIES) == entriesBefore
        balance(db.dsl, bob) == 40_00
    }

    def "a reversal the receiver can't cover is refused before anything is written"() {
        given: "bob received 40.00 and spent 30.00 of it"
        def alice = customerAccount(db.dsl)
        def bob = customerAccount(db.dsl)
        def carol = customerAccount(db.dsl)
        db.inTransaction { tx -> funding.fund(tx, alice, TransferAmount.of(100_00, EUR)) }
        def original = db.inTransaction { tx -> ledger.post(tx, entry(EntryKind.TRANSFER, alice, bob, 40_00)) }
        db.inTransaction { tx -> ledger.post(tx, entry(EntryKind.TRANSFER, bob, carol, 30_00)) }
        InsufficientFundsException refused = null
        int entriesBefore = 0
        int entriesAfter = 0
        boolean transactionStillUsable = false

        when:
        db.inTransaction { tx ->
            entriesBefore = tx.fetchCount(JOURNAL_ENTRIES)
            try {
                ledger.reverse(tx, original.entryId())
            } catch (InsufficientFundsException e) {
                refused = e
            }
            entriesAfter = tx.fetchCount(JOURNAL_ENTRIES)
            transactionStillUsable = tx.fetchValue("SELECT 1") == 1
        }

        then:
        refused?.accountId() == bob
        entriesAfter == entriesBefore
        transactionStillUsable
        balance(db.dsl, bob) == 10_00
    }

    def "an entry can't be reversed twice, and a reversal can't be reversed"() {
        given:
        def alice = customerAccount(db.dsl)
        def bob = customerAccount(db.dsl)
        db.inTransaction { tx -> funding.fund(tx, alice, TransferAmount.of(100_00, EUR)) }
        def original = db.inTransaction { tx -> ledger.post(tx, entry(EntryKind.TRANSFER, alice, bob, 40_00)) }
        def reversal = db.inTransaction { tx -> ledger.reverse(tx, original.entryId()) }

        when:
        db.inTransaction { tx -> ledger.reverse(tx, original.entryId()) }

        then:
        def twice = thrown(InvalidEntryException)
        twice.message.contains("already reversed")

        when:
        db.inTransaction { tx -> ledger.reverse(tx, reversal.entryId()) }

        then:
        def ofReversal = thrown(InvalidEntryException)
        ofReversal.message.contains("can't be reversed")
        balance(db.dsl, alice) == 100_00
    }

    private UUID reversedEntryOf(UUID entryId) {
        db.dsl.select(JOURNAL_ENTRIES.REVERSES_ID).from(JOURNAL_ENTRIES)
                .where(JOURNAL_ENTRIES.ID.eq(entryId))
                .fetchOne(JOURNAL_ENTRIES.REVERSES_ID)
    }

    private static JournalEntryRequest entry(EntryKind kind, UUID from, UUID to, long minor) {
        new JournalEntryRequest(kind, EUR, null, [
                new Posting(from, Money.of(-minor, EUR)),
                new Posting(to, Money.of(minor, EUR))])
    }

    private Map<UUID, Long> postingsOf(UUID entryId) {
        db.dsl.select(POSTINGS.ACCOUNT_ID, POSTINGS.AMOUNT_MINOR)
                .from(POSTINGS)
                .where(POSTINGS.JOURNAL_ENTRY_ID.eq(entryId))
                .fetchMap(POSTINGS.ACCOUNT_ID, POSTINGS.AMOUNT_MINOR)
    }

    private String entryKind(UUID entryId) {
        db.dsl.select(JOURNAL_ENTRIES.KIND).from(JOURNAL_ENTRIES)
                .where(JOURNAL_ENTRIES.ID.eq(entryId))
                .fetchOne(JOURNAL_ENTRIES.KIND)
    }
}
