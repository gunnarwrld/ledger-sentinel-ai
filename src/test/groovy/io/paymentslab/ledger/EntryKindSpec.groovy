package io.paymentslab.ledger

import spock.lang.Specification

/** Which account statuses each kind of entry may touch (ADR 0010: corrections may reach FROZEN accounts). */
class EntryKindSpec extends Specification {

    def "a #kind entry may post to a #status account: #allowed"() {
        expect:
        kind.allowsPostingTo(status) == allowed

        where:
        kind               | status   | allowed
        EntryKind.FUNDING  | "ACTIVE" | true
        EntryKind.FUNDING  | "FROZEN" | false
        EntryKind.FUNDING  | "CLOSED" | false
        EntryKind.TRANSFER | "ACTIVE" | true
        EntryKind.TRANSFER | "FROZEN" | false
        EntryKind.TRANSFER | "CLOSED" | false
        EntryKind.REVERSAL | "ACTIVE" | true
        EntryKind.REVERSAL | "FROZEN" | true
        EntryKind.REVERSAL | "CLOSED" | false
    }
}
