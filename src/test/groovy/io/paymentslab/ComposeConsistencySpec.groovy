package io.paymentslab

import spock.lang.Specification

/**
 * docker-compose.yml repeats the image tags from gradle.properties (which codegen and the
 * integration specs use). Fail the build if they drift apart.
 */
class ComposeConsistencySpec extends Specification {

    def "Compose runs the same #service image as codegen and the integration specs"() {
        given:
        def compose = new File("docker-compose.yml").text

        expect:
        composeImage(compose, service) == System.getProperty(property)

        where:
        service    | property
        "postgres" | "postgres.image"
        "redis"    | "redis.image"
    }

    /** The image of a service, assuming the simple layout used in docker-compose.yml. */
    private static String composeImage(String compose, String service) {
        def matcher = compose =~ /(?ms)^  ${service}:\s*\n\s+image:\s*(\S+)/
        matcher.find() ? matcher.group(1) : null
    }
}
