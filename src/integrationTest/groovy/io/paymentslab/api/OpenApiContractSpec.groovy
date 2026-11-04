package io.paymentslab.api

import groovy.yaml.YamlSlurper
import io.paymentslab.platform.HealthService
import spock.lang.Shared
import spock.lang.Specification

/**
 * Keeps docs/api/openapi.yaml honest: every route the server registers must be documented, and
 * every documented operation must exist unless it is marked {@code x-status: planned}.
 */
class OpenApiContractSpec extends Specification {

    static final List<String> HTTP_METHODS = ["get", "put", "post", "delete", "patch", "head", "options"]

    @Shared Map contract
    @Shared Set<String> implemented

    def setupSpec() {
        def file = new File("docs/api/openapi.yaml")
        contract = file.exists() ? new YamlSlurper().parse(file) as Map : [:]

        // Routes are registered by start() and cleared by stop(); no route is called.
        def api = new HttpApi("127.0.0.1", 0, new HealthService(null, null), null)
        api.start()
        implemented = api.routes() as Set
        api.stop()
    }

    def "every implemented route is documented and not marked planned"() {
        expect:
        implemented - operations(false) == [] as Set
    }

    def "every documented operation not marked planned is implemented"() {
        expect:
        operations(false) - implemented == [] as Set
    }

    def "the contract is OpenAPI 3.1 and defines the shared error and security building blocks"() {
        expect:
        contract.openapi == "3.1.0"
        contract.components?.schemas?.Problem
        contract.components?.securitySchemes?.apiKey
        contract.components?.parameters?.IdempotencyKey
    }

    /** "GET /v1/transfers/{transferId}" for each operation whose planned marker equals the argument. */
    private Set<String> operations(boolean planned) {
        Map paths = contract.paths ?: [:]
        paths.collectMany { String path, Map item ->
            item.findAll { method, operation ->
                method in HTTP_METHODS && ((operation as Map)["x-status"] == "planned") == planned
            }.collect { method, operation -> "${method.toUpperCase()} ${path}".toString() }
        } as Set
    }
}
