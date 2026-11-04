package io.paymentslab.support

import io.paymentslab.platform.AppConfig
import org.testcontainers.containers.GenericContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/** Container factories. Image tags come from gradle.properties via system properties. */
final class TestContainers {

    static final int REDIS_PORT = 6379

    private TestContainers() {}

    static PostgreSQLContainer postgres() {
        new PostgreSQLContainer(DockerImageName.parse(System.getProperty("postgres.image")))
    }

    static GenericContainer redis() {
        new GenericContainer(DockerImageName.parse(System.getProperty("redis.image")))
                .withExposedPorts(REDIS_PORT)
    }

    /** App config pointing at the given containers, on an ephemeral HTTP port. */
    static AppConfig appConfig(PostgreSQLContainer postgres, GenericContainer redis) {
        new AppConfig(
                "127.0.0.1",
                0,
                postgres.jdbcUrl,
                postgres.username,
                postgres.password,
                4,
                "redis://${redis.host}:${redis.getMappedPort(REDIS_PORT)}".toString())
    }
}
