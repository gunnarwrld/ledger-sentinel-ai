package io.paymentslab.api

import static io.paymentslab.support.ApiClient.contentType
import static io.paymentslab.support.ApiClient.json

import groovy.yaml.YamlSlurper
import io.paymentslab.app.App
import io.paymentslab.support.ApiClient
import io.paymentslab.support.CallerFixtures
import io.paymentslab.support.CallerFixtures.TestCaller
import io.paymentslab.support.ContractSchemas
import io.paymentslab.support.LedgerFixtures
import io.paymentslab.support.LogCapture
import io.paymentslab.support.TestDatabase
import io.paymentslab.support.TestDatabases
import spock.lang.Shared
import spock.lang.Specification

/** API keys on /v1 (ADR 0008, threat T1), through the whole app on a real database. */
class AuthApiSpec extends Specification {

    static final Map UNAUTHENTICATED = [type: "about:blank", title: "Unauthorized", status: 401,
                                        code: "UNAUTHENTICATED", detail: "A valid API key is required."]

    @Shared TestDatabase db = TestDatabases.create()
    @Shared App app
    @Shared ApiClient api
    @Shared TestCaller alice
    @Shared UUID aliceAccount
    @Shared List<String> protectedOperations
    @Shared Map<String, String> badCredentials

    def setupSpec() {
        app = App.start(db.appConfig())
        api = new ApiClient(app.port())
        alice = CallerFixtures.customer(db.dsl)
        aliceAccount = LedgerFixtures.accountFor(db.dsl, alice.id)
        // Planned operations count too: the check must cover routes before they are written.
        protectedOperations = (documentedOperations() + app.routes() + ["GET /v1/no-such-route"])
                .findAll { it.split(" ")[1].startsWith("/v1/") }
                .unique()
        badCredentials = [
                "no Authorization header"        : null,
                "an empty Authorization header"  : "",
                "Basic credentials"              : "Basic dXNlcjpwYXNz",
                "a valid key under Basic"        : "Basic $alice.apiKey".toString(),
                "Bearer without a token"         : "Bearer",
                "a malformed key"                : "Bearer not-a-key",
                "a valid key plus one character" : "Bearer ${alice.apiKey}x".toString(),
                "a valid key plus a second token": "Bearer $alice.apiKey extra".toString(),
                "an unknown well-formed key"     : "Bearer pl_test_" + "A" * 43,
        ]
    }

    def cleanupSpec() {
        app?.close()
        db?.close()
    }

    def "health endpoints stay public (#path)"() {
        expect:
        api.get(path, null).statusCode() == 200

        where:
        path << ["/health/live", "/health/ready"]
    }

    def "the coverage check includes the implemented account routes"() {
        expect:
        protectedOperations.containsAll(["GET /v1/accounts/{accountId}", "GET /v1/accounts/{accountId}/entries"])
    }

    def "#operation with #credentials gets the same 401 problem"() {
        given:
        def (method, template) = operation.split(" ")
        def path = template.replaceAll(/\{\w+\}/, UUID.randomUUID().toString())

        when:
        def response = api.send(method, path, badCredentials[credentials])

        then:
        response.statusCode() == 401
        contentType(response).startsWith("application/problem+json")
        response.headers().firstValue("WWW-Authenticate").orElse(null) == 'Bearer realm="payments-lab"'
        json(response) == UNAUTHENTICATED
        ContractSchemas.violations(json(response), "Problem") == []

        where:
        [operation, credentials] << [protectedOperations, badCredentials.keySet().toList()].combinations()
    }

    def "a valid key is accepted whatever the case of the scheme (#scheme)"() {
        // Requests stay outside conditions: Spock prints every sub-expression of a failed condition, keys included.
        when:
        def response = api.get("/v1/accounts/$aliceAccount", "$scheme $alice.apiKey")

        then:
        response.statusCode() == 200

        where:
        scheme << ["Bearer", "bearer", "BEARER"]
    }

    def "API keys never reach the log, even with our own loggers at TRACE"() {
        given:
        def logs = new LogCapture("io.paymentslab")
        def unknownKey = CallerFixtures.newKey()

        when:
        api.get("/v1/accounts/$aliceAccount", alice.bearer)
        api.get("/v1/accounts/$aliceAccount", "Bearer $unknownKey")
        api.get("/v1/accounts/$aliceAccount", "Bearer ${alice.apiKey}x")
        api.get("/v1/no-such-route", alice.bearer)

        then: "both failed authentications were logged..."
        logs.text().count("authentication failed") == 2

        and: "...without any key, valid or not"
        !logs.text().contains(alice.apiKey)
        !logs.text().contains(unknownKey)

        cleanup:
        logs.close()
    }

    /** Every operation in docs/api/openapi.yaml, as "GET /v1/accounts/{accountId}". */
    private static List<String> documentedOperations() {
        Map contract = new YamlSlurper().parse(new File("docs/api/openapi.yaml")) as Map
        (contract.paths as Map).collectMany { String path, Map item ->
            item.keySet().findAll { it in ["get", "put", "post", "delete", "patch"] }
                    .collect { "${it.toUpperCase()} $path".toString() }
        }
    }
}
