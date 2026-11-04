package io.paymentslab.api

import java.nio.charset.StandardCharsets
import spock.lang.Specification

/** The opaque keyset cursor for account statements. */
class EntryCursorSpec extends Specification {

    def "decoding an encoded cursor gives back posting id #postingId"() {
        expect:
        EntryCursor.decode(EntryCursor.encode(postingId)) == postingId

        where:
        postingId << [1L, 42L, Long.MAX_VALUE]
    }

    def "a cursor is opaque and safe to put in a URL"() {
        when:
        def cursor = EntryCursor.encode(42)

        then:
        cursor ==~ /[A-Za-z0-9_-]+/
        !cursor.contains("42")
    }

    def "posting id #postingId can't be encoded"() {
        when:
        EntryCursor.encode(postingId)

        then:
        thrown(IllegalArgumentException)

        where:
        postingId << [0L, -1L, Long.MIN_VALUE]
    }

    def "a cursor this API could not have produced is rejected: #why"() {
        when:
        EntryCursor.decode(cursor)

        then:
        thrown(IllegalArgumentException)

        where:
        why                                    | cursor
        "null"                                 | null
        "empty"                                | ""
        "not base64url"                        | "a*b"
        "no version prefix"                    | b64("42")
        "an unknown version"                   | b64("v2:42")
        "zero"                                 | b64("v1:0")
        "negative"                             | b64("v1:-5")
        "leading zeros"                        | b64("v1:042")
        "not a number"                         | b64("v1:abc")
        "beyond the largest long"              | b64("v1:9223372036854775808")
        "trailing data"                        | b64("v1:42 ")
        "longer than the contract's 200 chars" | "QUFB" * 51
    }

    private static String b64(String text) {
        Base64.urlEncoder.withoutPadding().encodeToString(text.getBytes(StandardCharsets.US_ASCII))
    }
}
