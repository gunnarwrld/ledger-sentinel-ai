package io.paymentslab.api

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import spock.lang.Specification

/** Key format and hashing from ADR 0008. */
class ApiKeysSpec extends Specification {

    def "a new key is pl_test_ plus 32 random bytes in unpadded base64url"() {
        when:
        def key = ApiKeys.generate()

        then:
        key ==~ /pl_test_[A-Za-z0-9_-]{43}/
        Base64.urlDecoder.decode(key.substring("pl_test_".length())).length == 32
    }

    def "keys don't repeat"() {
        expect:
        (1..1000).collect { ApiKeys.generate() }.toSet().size() == 1000
    }

    def "the stored hash is SHA-256 of the whole key"() {
        given:
        def key = ApiKeys.generate()

        expect: "the published SHA-256 test vector for 'abc' (FIPS 180-2)"
        ApiKeys.hash("abc").encodeHex().toString() == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

        and: "the prefix is part of what is hashed"
        ApiKeys.hash(key) == MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))
    }

    def "a generated key is well formed"() {
        expect:
        ApiKeys.isWellFormed(ApiKeys.generate())
    }

    def "#why is not a well-formed key"() {
        expect:
        !ApiKeys.isWellFormed(candidate)

        where:
        why                              | candidate
        "null"                           | null
        "an empty string"                | ""
        "the prefix alone"               | "pl_test_"
        "another prefix"                 | "pl_live_" + "A" * 43
        "the prefix in upper case"       | "PL_TEST_" + "A" * 43
        "one character short"            | "pl_test_" + "A" * 42
        "one character long"             | "pl_test_" + "A" * 44
        "standard base64 ('+')"          | "pl_test_" + "A" * 42 + "+"
        "base64 padding"                 | "pl_test_" + "A" * 42 + "="
        "a space inside"                 | "pl_test_" + "A" * 21 + " " + "A" * 21
        "a leading space"                | " pl_test_" + "A" * 43
    }
}
