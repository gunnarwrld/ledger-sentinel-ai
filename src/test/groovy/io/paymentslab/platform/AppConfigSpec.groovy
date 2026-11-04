package io.paymentslab.platform

import spock.lang.Specification

class AppConfigSpec extends Specification {

    def "defaults suit local development: loopback only, Compose ports"() {
        when:
        def config = AppConfig.fromEnv([:])

        then:
        config.httpBindAddress() == "127.0.0.1"
        config.httpPort() == 8080
        config.dbUrl() == "jdbc:postgresql://localhost:55432/payments"
        config.dbUser() == "payments"
        config.dbPoolSize() == 10
        config.redisUrl() == "redis://localhost:56379"
    }

    def "environment variables override the defaults"() {
        when:
        def config = AppConfig.fromEnv([
                HTTP_BIND_ADDRESS: "10.0.0.5", HTTP_PORT: "9090",
                DB_URL: "jdbc:postgresql://db:5432/p", DB_USER: "u", DB_PASSWORD: "p", DB_POOL_SIZE: "20",
                REDIS_URL: "redis://cache:6379"])

        then:
        config.httpBindAddress() == "10.0.0.5"
        config.httpPort() == 9090
        config.dbUrl() == "jdbc:postgresql://db:5432/p"
        config.dbUser() == "u"
        config.dbPassword() == "p"
        config.dbPoolSize() == 20
        config.redisUrl() == "redis://cache:6379"
    }

    def "blank values fall back to the defaults"() {
        when:
        def config = AppConfig.fromEnv([HTTP_BIND_ADDRESS: "  ", HTTP_PORT: "", DB_URL: ""])

        then:
        config.httpBindAddress() == "127.0.0.1"
        config.httpPort() == 8080
        config.dbUrl() == "jdbc:postgresql://localhost:55432/payments"
    }

    def "rejects #name=#value and names the variable"() {
        when:
        AppConfig.fromEnv([(name): value])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(name)

        where:
        name           | value
        "HTTP_PORT"    | "eighty"
        "HTTP_PORT"    | "-1"
        "HTTP_PORT"    | "65536"
        "DB_POOL_SIZE" | "lots"
        "DB_POOL_SIZE" | "0"
        "DB_POOL_SIZE" | "101"
    }

    def "toString never reveals the database password"() {
        when:
        def text = AppConfig.fromEnv([DB_PASSWORD: "s3cret-value"]).toString()

        then:
        !text.contains("s3cret-value")
        text.contains("dbPassword=***")
    }
}
