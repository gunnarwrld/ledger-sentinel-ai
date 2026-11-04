package io.paymentslab.ledger

import static io.paymentslab.ledger.EntryKind.REVERSAL
import static io.paymentslab.ledger.EntryKind.TRANSFER

import io.paymentslab.money.Money
import io.paymentslab.money.UnsupportedCurrencyException
import spock.lang.Specification

/** The shape rules every journal entry must satisfy before it reaches the database (I3, I5). */
class JournalEntryRequestSpec extends Specification {

    static final Currency EUR = Currency.getInstance("EUR")
    static final Currency USD = Currency.getInstance("USD")
    static final UUID A = UUID.randomUUID()
    static final UUID B = UUID.randomUUID()
    static final UUID C = UUID.randomUUID()

    def "accepts a balanced two-posting entry"() {
        when:
        def entry = new JournalEntryRequest(TRANSFER, EUR, null, [posting(A, -2500), posting(B, 2500)])

        then:
        entry.postings() == [posting(A, -2500), posting(B, 2500)]
    }

    def "accepts more than two postings when they balance"() {
        when:
        new JournalEntryRequest(TRANSFER, EUR, null, [posting(A, -300), posting(B, 100), posting(C, 200)])

        then:
        noExceptionThrown()
    }

    def "accepts a reversal that names the entry it reverses"() {
        when:
        new JournalEntryRequest(REVERSAL, EUR, UUID.randomUUID(), [posting(A, 100), posting(B, -100)])

        then:
        noExceptionThrown()
    }

    def "rejects #scenario"() {
        when:
        new JournalEntryRequest(kind, EUR, reverses, postings)

        then:
        thrown(InvalidEntryException)

        where:
        scenario                                   | kind     | reverses          | postings
        "a single posting"                         | TRANSFER | null              | [posting(A, 100)]
        "no postings"                              | TRANSFER | null              | []
        "postings that don't sum to zero"          | TRANSFER | null              | [posting(A, -100), posting(B, 99)]
        "a zero posting"                           | TRANSFER | null              | [posting(A, 0), posting(B, 0)]
        "the same account twice"                   | TRANSFER | null              | [posting(A, -100), posting(A, 100)]
        "a posting in another currency"            | TRANSFER | null              | [posting(A, -100), new Posting(B, Money.of(100, USD))]
        "a reversal that doesn't name its target"  | REVERSAL | null              | [posting(A, -100), posting(B, 100)]
        "a non-reversal that names a target"       | TRANSFER | UUID.randomUUID() | [posting(A, -100), posting(B, 100)]
    }

    def "rejects an entry in a currency the system doesn't support"() {
        when:
        new JournalEntryRequest(TRANSFER, USD, null, [new Posting(A, Money.of(-100, USD)), new Posting(B, Money.of(100, USD))])

        then:
        thrown(UnsupportedCurrencyException)
    }

    def "rejects postings whose sum would overflow instead of wrapping"() {
        when:
        new JournalEntryRequest(TRANSFER, EUR, null, [posting(A, Long.MAX_VALUE), posting(B, Long.MAX_VALUE), posting(C, -1)])

        then:
        thrown(ArithmeticException)
    }

    def "keeps its own unmodifiable copy of the postings"() {
        given:
        def postings = [posting(A, -100), posting(B, 100)]
        def entry = new JournalEntryRequest(TRANSFER, EUR, null, postings)

        when: "the caller's list changes afterwards"
        postings.clear()

        then:
        entry.postings().size() == 2

        when:
        entry.postings().add(posting(C, 1))

        then:
        thrown(UnsupportedOperationException)
    }

    private static Posting posting(UUID account, long minor) {
        new Posting(account, Money.of(minor, EUR))
    }
}
