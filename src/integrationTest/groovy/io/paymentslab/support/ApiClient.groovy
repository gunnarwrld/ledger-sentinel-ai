package io.paymentslab.support

import groovy.json.JsonSlurperClassic
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Plain HTTP calls against an app started on 127.0.0.1, for API specs. */
final class ApiClient {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private final int port

    ApiClient(int port) {
        this.port = port
    }

    /** {@code authorization} is the whole header value, or null to send no Authorization header. */
    HttpResponse<String> get(String path, String authorization) {
        send("GET", path, authorization)
    }

    HttpResponse<String> send(String method, String path, String authorization) {
        def request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .timeout(Duration.ofSeconds(5))
                .method(method, HttpRequest.BodyPublishers.noBody())
        if (authorization != null) {
            request.header("Authorization", authorization)
        }
        http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    /** A JSON POST. Null {@code authorization} or {@code idempotencyKey} leaves that header out. */
    HttpResponse<String> post(String path, String authorization, String idempotencyKey, String body) {
        def request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
        if (authorization != null) {
            request.header("Authorization", authorization)
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey)
        }
        http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    static boolean replayed(HttpResponse<String> response) {
        response.headers().firstValue("Idempotent-Replayed").orElse(null) == "true"
    }

    /**
     * Parsed eagerly into plain maps and lists. {@code JsonSlurper}'s default maps build themselves
     * lazily on first read and aren't thread-safe, so concurrent specs reading one result broke them.
     */
    static Object json(HttpResponse<String> response) {
        new JsonSlurperClassic().parseText(response.body())
    }

    static String contentType(HttpResponse<String> response) {
        response.headers().firstValue("Content-Type").orElse("")
    }
}
