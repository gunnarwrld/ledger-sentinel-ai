package io.paymentslab.api

import static io.paymentslab.support.ApiClient.contentType
import static io.paymentslab.support.ApiClient.json

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import io.paymentslab.app.App
import io.paymentslab.support.ApiClient
import io.paymentslab.support.CallerFixtures
import io.paymentslab.support.CallerFixtures.TestCaller
import io.paymentslab.support.ContractSchemas
import io.paymentslab.support.LedgerFixtures
import io.paymentslab.support.LogCapture
import io.paymentslab.support.TestDatabase
import io.paymentslab.support.TestDatabases
import spock.lang.Specification

/** Errors are problem+json and never leak internals (threat T10). Each feature gets its own database. */
class ErrorResponsesApiSpec extends Specification {

    static final String UUID_TEXT = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/

    TestDatabase db = TestDatabases.create()
    App app
    ApiClient api
    TestCaller alice
    UUID aliceAccount

    def setup() {
        app = App.start(db.appConfig())
        api = new ApiClient(app.port())
        alice = CallerFixtures.customer(db.dsl)
        aliceAccount = LedgerFixtures.accountFor(db.dsl, alice.id)
    }

    def cleanup() {
        app?.close()
        db.close()
    }

    def "an unknown route (#path) gets a NOT_FOUND problem, not Spark's HTML page"() {
        when:
        def response = api.get(path, alice.bearer)

        then:
        response.statusCode() == 404
        contentType(response).startsWith("application/problem+json")
        json(response).code == "NOT_FOUND"
        ContractSchemas.violations(json(response), "Problem") == []

        where:
        path << ["/v1/no-such-route", "/no-such-route"]
    }

    def "an unexpected failure gets a generic 500; the details go to the log under the same correlation id"() {
        given: "a schema the code doesn't expect, so the account query fails with SQL in its message"
        db.dsl.execute("ALTER TABLE accounts RENAME COLUMN balance_minor TO balance_minor_renamed")
        def logs = new LogCapture()

        when:
        def response = api.get("/v1/accounts/$aliceAccount", alice.bearer)
        def body = json(response)

        then: "the client sees no internals"
        response.statusCode() == 500
        contentType(response).startsWith("application/problem+json")
        body.findAll { it.key != "correlationId" } == [type: "about:blank", title: "Internal Server Error", status: 500,
                                                       code: "INTERNAL", detail: "Unexpected error. Quote the correlationId if you report it."]
        body.correlationId ==~ UUID_TEXT
        !response.body().contains("balance_minor")
        ContractSchemas.violations(body, "Problem") == []

        and: "the log has the full error under that id"
        def logged = logs.events().find { it.level == Level.ERROR && it.formattedMessage.contains(body.correlationId) }
        logged != null
        ThrowableProxyUtil.asString(logged.throwableProxy).contains("balance_minor")

        cleanup:
        logs?.close()
    }
}
