package io.paymentslab.api

import static io.paymentslab.support.ApiClient.contentType
import static io.paymentslab.support.ApiClient.json
import static io.paymentslab.support.ApiClient.replayed

import io.paymentslab.app.App
import io.paymentslab.support.ApiClient
import io.paymentslab.support.CallerFixtures
import io.paymentslab.support.CallerFixtures.TestCaller
import io.paymentslab.support.ContractSchemas
import io.paymentslab.support.TestDatabase
import io.paymentslab.support.TestDatabases
import spock.lang.Shared
import spock.lang.Specification

/** POST /v1/accounts: customers open their own accounts. */
class AccountCreationApiSpec extends Specification {

    @Shared TestDatabase db = TestDatabases.create()
    @Shared App app
    @Shared ApiClient api
    @Shared TestCaller alice
    @Shared TestCaller bob
    @Shared TestCaller operator

    def setupSpec() {
        app = App.start(db.appConfig())
        api = new ApiClient(app.port())
        alice = CallerFixtures.customer(db.dsl)
        bob = CallerFixtures.customer(db.dsl)
        operator = CallerFixtures.operator(db.dsl)
    }

    def cleanupSpec() {
        app?.close()
        db?.close()
    }

    def "a customer opens an empty, active EUR account that only they can read"() {
        when:
        def response = api.post("/v1/accounts", alice.bearer, newKey(), '{"currency":"EUR"}')
        def created = json(response)

        then:
        response.statusCode() == 201
        contentType(response).startsWith("application/json")
        !replayed(response)
        created.currency == "EUR"
        created.status == "ACTIVE"
        created.balanceMinor == 0
        ContractSchemas.violations(created, "Account") == []

        and: "the owner reads back exactly what was returned; another customer gets 404"
        def readBack = api.get("/v1/accounts/$created.id", alice.bearer)
        def asBob = api.get("/v1/accounts/$created.id", bob.bearer)
        json(readBack) == created
        asBob.statusCode() == 404
    }

    def "an operator can't open accounts: 403 FORBIDDEN, nothing created"() {
        given:
        def before = accountCount()

        when:
        def response = api.post("/v1/accounts", operator.bearer, newKey(), '{"currency":"EUR"}')

        then:
        response.statusCode() == 403
        contentType(response).startsWith("application/problem+json")
        json(response).code == "FORBIDDEN"
        ContractSchemas.violations(json(response), "Problem") == []
        accountCount() == before
    }

    def "a body with #why is rejected with VALIDATION_FAILED naming #fields, nothing created"() {
        given:
        def before = accountCount()

        when:
        def response = api.post("/v1/accounts", alice.bearer, newKey(), body)

        then:
        response.statusCode() == 400
        json(response).code == "VALIDATION_FAILED"
        json(response).errors*.field == fields
        ContractSchemas.violations(json(response), "Problem") == []
        accountCount() == before

        where:
        why                                    | body                                             | fields
        "no currency"                          | '{}'                                             | ["currency"]
        "a lower-case code"                    | '{"currency":"eur"}'                             | ["currency"]
        "an unsupported currency"              | '{"currency":"USD"}'                             | ["currency"]
        "a numeric code"                       | '{"currency":978}'                               | ["currency"]
        "an owner field (mass assignment)"     | '{"currency":"EUR","ownerId":"someone-else"}'    | ["ownerId"]
        "a balance field (mass assignment)"    | '{"currency":"EUR","balanceMinor":1000000}'      | ["balanceMinor"]
        "form encoding instead of JSON"        | 'currency=EUR'                                   | ["body"]
    }

    private long accountCount() {
        db.dsl.fetchValue("SELECT count(*) FROM accounts") as long
    }

    private static String newKey() {
        UUID.randomUUID().toString()
    }
}
