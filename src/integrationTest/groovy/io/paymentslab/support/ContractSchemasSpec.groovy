package io.paymentslab.support

import spock.lang.Specification

/** The response validator must be able to fail, or every contract check built on it is hollow. */
class ContractSchemasSpec extends Specification {

    static final Map ACCOUNT = [id: "3f1c7a52-8f0e-4b8a-9c6d-2a1e5b7f9d01", currency: "EUR", status: "ACTIVE",
                                balanceMinor: 123_45, createdAt: "2026-10-30T09:15:30.123456Z"]
    static final Map ENTRY = [journalEntryId: ACCOUNT.id, kind: "FUNDING", amountMinor: -5_00, currency: "EUR",
                              createdAt: ACCOUNT.createdAt]

    def "a conforming Account passes"() {
        expect:
        ContractSchemas.violations(ACCOUNT, "Account") == []
    }

    def "an Account with #flaw is rejected"() {
        expect:
        ContractSchemas.violations(body, "Account").size() == 1

        where:
        flaw                                  | body
        "a missing required property"         | ACCOUNT.findAll { it.key != "balanceMinor" }
        "an undeclared property"              | ACCOUNT + [ownerId: "3f1c7a52-8f0e-4b8a-9c6d-2a1e5b7f9d02"]
        "a value outside its enum"            | ACCOUNT + [status: "OPEN"]
        "a string where an integer belongs"   | ACCOUNT + [balanceMinor: "12345"]
        "a decimal where an integer belongs"  | ACCOUNT + [balanceMinor: 1.5]
        "an id that isn't a UUID"             | ACCOUNT + [id: "12345"]
        "a timestamp that isn't RFC 3339"     | ACCOUNT + [createdAt: "30/10/2026"]
        "a currency code in lower case"       | ACCOUNT + [currency: "eur"]
    }

    def "array items and nullable properties are checked too"() {
        expect:
        ContractSchemas.violations([items: [ENTRY], nextCursor: null], "EntryPage") == []
        ContractSchemas.violations([items: [ENTRY], nextCursor: "djE6NDI"], "EntryPage") == []
        ContractSchemas.violations([items: [ENTRY.findAll { it.key != "kind" }], nextCursor: null], "EntryPage") ==
                ['$.items[0]: missing kind']
        ContractSchemas.violations([items: [ENTRY]], "EntryPage") == ['$: missing nextCursor']
    }

    def "a Problem must use one of the documented codes"() {
        expect:
        ContractSchemas.violations([type: "about:blank", title: "Not Found", status: 404, code: "NOT_FOUND"], "Problem") == []
        ContractSchemas.violations([type: "about:blank", title: "I'm a teapot", status: 418, code: "TEAPOT"], "Problem").size() == 1
    }
}
