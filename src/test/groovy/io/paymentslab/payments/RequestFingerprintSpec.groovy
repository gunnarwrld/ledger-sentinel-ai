package io.paymentslab.payments

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import spock.lang.Specification

/** The canonical request hash that tells a retry from a different request under the same key (DESIGN §6). */
class RequestFingerprintSpec extends Specification {

    static final UUID ACCOUNT = UUID.fromString("3f1c7a52-8f0e-4b8a-9c6d-2a1e5b7f9d01")

    def "the same fields give the same 32-byte fingerprint"() {
        when:
        def first = funding(ACCOUNT, 500, "EUR")
        def second = funding(ACCOUNT, 500, "EUR")

        then:
        first.length == 32
        first == second
    }

    def "it is SHA-256 over each part as a 4-byte length followed by its UTF-8 bytes"() {
        given:
        def expected = MessageDigest.getInstance("SHA-256")
        ["funding.create", ACCOUNT.toString(), "500", "EUR"].each { part ->
            def bytes = part.getBytes(StandardCharsets.UTF_8)
            expected.update(ByteBuffer.allocate(4).putInt(bytes.length).array())
            expected.update(bytes)
        }

        expect:
        funding(ACCOUNT, 500, "EUR") == expected.digest()
    }

    def "changing #what changes the fingerprint"() {
        expect:
        changed != funding(ACCOUNT, 500, "EUR")

        where:
        what            | changed
        "the operation" | RequestFingerprint.of("transfer.create").add(ACCOUNT).add(500L).add("EUR").hash()
        "an id"         | funding(UUID.fromString("3f1c7a52-8f0e-4b8a-9c6d-2a1e5b7f9d02"), 500, "EUR")
        "an amount"     | funding(ACCOUNT, 501, "EUR")
        "a currency"    | funding(ACCOUNT, 500, "USD")
    }

    def "length prefixes keep parts apart: ('ab', 'c') differs from ('a', 'bc')"() {
        expect:
        RequestFingerprint.of("x.y").add("ab").add("c").hash() != RequestFingerprint.of("x.y").add("a").add("bc").hash()
    }

    def "an absent part differs from an empty one"() {
        expect:
        RequestFingerprint.of("x.y").add((String) null).hash() != RequestFingerprint.of("x.y").add("").hash()
    }

    private static byte[] funding(UUID account, long amount, String currency) {
        RequestFingerprint.of("funding.create").add(account).add(amount).add(currency).hash()
    }
}
