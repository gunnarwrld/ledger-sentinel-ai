package io.paymentslab.api

import spock.lang.Specification

/** {@code Authorization: Bearer <token>} parsing (RFC 6750 §2.1; scheme names are case-insensitive, RFC 9110 §11.1). */
class BearerTokenSpec extends Specification {

    def "'#header' carries the token '#token'"() {
        expect:
        BearerToken.from(header) == Optional.of(token)

        where:
        header                   | token
        "Bearer abc"             | "abc"
        "bearer abc"             | "abc"
        "BEARER abc"             | "abc"
        "Bearer   abc"           | "abc"
        "Bearer pl_test_a-b_c"   | "pl_test_a-b_c"
        "Bearer abc.de~f+g/h=="  | "abc.de~f+g/h=="
    }

    def "#why carries no token"() {
        expect:
        BearerToken.from(header) == Optional.empty()

        where:
        why                           | header
        "no header"                   | null
        "an empty header"             | ""
        "the scheme alone"            | "Bearer"
        "the scheme and a space"      | "Bearer "
        "another scheme"              | "Basic abc"
        "no space after the scheme"   | "Bearerabc"
        "two tokens"                  | "Bearer abc def"
        "'=' inside the token"        | "Bearer a=b"
        "a tab instead of a space"    | "Bearer\tabc"
        "a leading space"             | " Bearer abc"
        "a trailing space"            | "Bearer abc "
    }
}
