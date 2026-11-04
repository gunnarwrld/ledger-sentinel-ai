package io.paymentslab.api

import static io.paymentslab.support.ApiClient.contentType
import static io.paymentslab.support.ApiClient.json

import io.paymentslab.app.App
import io.paymentslab.ledger.InvariantChecker
import io.paymentslab.ledger.SystemAccounts
import io.paymentslab.support.ApiClient
import io.paymentslab.support.CallerFixtures
import io.paymentslab.support.CallerFixtures.TestCaller
import io.paymentslab.support.ContractSchemas
import io.paymentslab.support.LedgerFixtures
import io.paymentslab.support.TestDatabase
import io.paymentslab.support.TestDatabases
import spock.lang.Shared
import spock.lang.Specification

/** POST /v1/admin/fundings: operators issue synthetic money into customer accounts (threat T18 for the role check). */
class FundingApiSpec extends Specification {

    @Shared TestDatabase db = TestDatabases.create()
    @Shared App app
    @Shared ApiClient api
    @Shared TestCaller operator
    @Shared TestCaller alice
    @Shared UUID aliceAccount
    @Shared UUID frozenAccount
    InvariantChecker invariants = new InvariantChecker()

    def setupSpec() {
        app = App.start(db.appConfig())
        api = new ApiClient(app.port())
        operator = CallerFixtures.operator(db.dsl)
        alice = CallerFixtures.customer(db.dsl)
        aliceAccount = LedgerFixtures.accountFor(db.dsl, alice.id)
        frozenAccount = LedgerFixtures.accountFor(db.dsl, alice.id)
        db.dsl.execute("UPDATE accounts SET status = 'FROZEN' WHERE id = ?::uuid", frozenAccount)
    }

    def cleanupSpec() {
        app?.close()
        db?.close()
    }

    def cleanup() {
        assert invariants.check(db.dsl) == []
    }

    def "an operator funds an account: one FUNDING entry, which the owner sees in their statement"() {
        given:
        def account = LedgerFixtures.accountFor(db.dsl, alice.id)

        when:
        def response = fund(operator, funding(account, 250_00))
        def funded = json(response)

        then:
        response.statusCode() == 201
        contentType(response).startsWith("application/json")
        funded.accountId == account.toString()
        funded.amountMinor == 250_00
        funded.currency == "EUR"
        ContractSchemas.violations(funded, "Funding") == []

        and:
        def statement = json(api.get("/v1/accounts/$account/entries", alice.bearer))
        statement.items*.journalEntryId == [funded.journalEntryId]
        statement.items*.kind == ["FUNDING"]
        statement.items*.amountMinor == [250_00]
        json(api.get("/v1/accounts/$account", alice.bearer)).balanceMinor == 250_00
    }

    def "a customer can't fund accounts: 403 FORBIDDEN, nothing posted"() {
        given:
        def before = entryCount()

        when:
        def response = fund(alice, funding(aliceAccount, 1_00))

        then:
        response.statusCode() == 403
        json(response).code == "FORBIDDEN"
        ContractSchemas.violations(json(response), "Problem") == []
        entryCount() == before
    }

    def "funding #why → #status #code, nothing posted"() {
        given: "a control: the route works, so a 404 below can't mean the route is missing"
        def control = fund(operator, funding(aliceAccount, 1_00))
        assert control.statusCode() == 201
        def before = entryCount()

        when:
        def response = fund(operator, funding(account, 1_00))

        then:
        response.statusCode() == status
        contentType(response).startsWith("application/problem+json")
        json(response).code == code
        ContractSchemas.violations(json(response), "Problem") == []
        entryCount() == before

        where:
        why                                 | account                | status | code
        "an account that doesn't exist"     | UUID.randomUUID()      | 404    | "NOT_FOUND"
        "the SYSTEM funding account itself" | SystemAccounts.FUNDING | 422    | "INVALID_ACCOUNT_COMBINATION"
        "a frozen account"                  | frozenAccount          | 422    | "ACCOUNT_NOT_ACTIVE"
    }

    def "a body with #why is rejected with VALIDATION_FAILED naming #fields, nothing posted"() {
        given:
        def before = entryCount()

        when:
        def response = fund(operator, body.replace("ACCOUNT", aliceAccount.toString()))

        then:
        response.statusCode() == 400
        json(response).code == "VALIDATION_FAILED"
        json(response).errors*.field == fields
        entryCount() == before

        where:
        why                       | body                                                                       | fields
        "a zero amount"           | '{"accountId":"ACCOUNT","amountMinor":0,"currency":"EUR"}'                 | ["amountMinor"]
        "a fractional amount"     | '{"accountId":"ACCOUNT","amountMinor":1.5,"currency":"EUR"}'               | ["amountMinor"]
        "an amount as a string"   | '{"accountId":"ACCOUNT","amountMinor":"10","currency":"EUR"}'              | ["amountMinor"]
        "an amount above the max" | '{"accountId":"ACCOUNT","amountMinor":100000001,"currency":"EUR"}'         | ["amountMinor"]
        "an amount beyond a long" | '{"accountId":"ACCOUNT","amountMinor":9223372036854775808,"currency":"EUR"}' | ["amountMinor"]
        "another currency"        | '{"accountId":"ACCOUNT","amountMinor":100,"currency":"USD"}'               | ["currency"]
        "an id that isn't a UUID" | '{"accountId":"nope","amountMinor":100,"currency":"EUR"}'                  | ["accountId"]
        "an extra field"          | '{"accountId":"ACCOUNT","amountMinor":100,"currency":"EUR","fee":0}'       | ["fee"]
        "nothing in it"           | '{}'                                                                       | ["accountId", "amountMinor", "currency"]
    }

    private def fund(TestCaller caller, String body) {
        api.post("/v1/admin/fundings", caller.bearer, UUID.randomUUID().toString(), body)
    }

    private static String funding(UUID account, long amount) {
        """{"accountId":"$account","amountMinor":$amount,"currency":"EUR"}"""
    }

    private long entryCount() {
        db.dsl.fetchValue("SELECT count(*) FROM journal_entries") as long
    }
}
