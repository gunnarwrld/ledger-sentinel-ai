package io.paymentslab.api

import groovy.json.JsonSlurper
import io.paymentslab.app.App
import io.paymentslab.support.TestContainers
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.testcontainers.containers.GenericContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import spock.lang.Specification

/**
 * Starts the whole app (same wiring as Main) against real containers. Each feature gets fresh
 * containers because some features stop them.
 */
class HealthApiSpec extends Specification {

    PostgreSQLContainer postgres = TestContainers.postgres()
    GenericContainer redis = TestContainers.redis()
    App app
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

    def setup() {
        postgres.start()
        redis.start()
        app = App.start(TestContainers.appConfig(postgres, redis))
    }

    def cleanup() {
        app?.close()
        redis.stop()
        postgres.stop()
    }

    def "live returns 200"() {
        when:
        def response = get("/health/live")

        then:
        response.statusCode() == 200
        json(response) == [status: "UP"]
    }

    def "ready returns 200 when PostgreSQL and Redis are reachable"() {
        when:
        def response = get("/health/ready")

        then:
        response.statusCode() == 200
        response.headers().firstValue("Content-Type").orElse("").startsWith("application/json")
        json(response) == [status: "UP", checks: [postgres: "UP", redis: "UP"]]
    }

    def "ready returns 503 within 2 seconds once PostgreSQL stops"() {
        given: "the pool already holds connections"
        assert get("/health/ready").statusCode() == 200

        when:
        postgres.stop()
        long started = System.nanoTime()
        def response = get("/health/ready")
        def elapsed = Duration.ofNanos(System.nanoTime() - started)

        then:
        response.statusCode() == 503
        json(response).status == "DOWN"
        json(response).checks.postgres == "DOWN"
        elapsed < Duration.ofSeconds(2)
    }

    def "ready stays 200 but reports Redis DOWN once Redis stops (Redis is advisory, DESIGN D5)"() {
        given:
        assert get("/health/ready").statusCode() == 200

        when:
        redis.stop()
        def response = get("/health/ready")

        then:
        response.statusCode() == 200
        json(response) == [status: "UP", checks: [postgres: "UP", redis: "DOWN"]]
    }

    def "live stays 200 when both dependencies are down"() {
        when:
        postgres.stop()
        redis.stop()
        def response = get("/health/live")

        then:
        response.statusCode() == 200
    }

    private HttpResponse<String> get(String path) {
        def request = HttpRequest.newBuilder(URI.create("http://localhost:${app.port()}$path"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build()
        http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private static Object json(HttpResponse<String> response) {
        new JsonSlurper().parseText(response.body())
    }
}
