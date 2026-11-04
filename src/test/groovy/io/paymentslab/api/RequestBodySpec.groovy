package io.paymentslab.api

import spock.lang.Specification

/** Strict reading of JSON request bodies (DESIGN §4.1, threat T6): every problem is collected, then reported at once. */
class RequestBodySpec extends Specification {

    static final Set<String> FUNDING = ["accountId", "amountMinor", "currency"] as Set
    static final String ACCOUNT = "3f1c7a52-8f0e-4b8a-9c6d-2a1e5b7f9d01"
    static final String AMOUNT_RULE = "must be an integer from 1 to 100000000"

    List<Problem.FieldError> errors = []

    def "reads a valid funding body"() {
        when:
        def body = RequestBody.parse("""{"accountId":"$ACCOUNT","amountMinor":2500,"currency":"EUR"}""", FUNDING, errors)

        then:
        body.uuid("accountId") == UUID.fromString(ACCOUNT)
        body.amountMinor("amountMinor") == 2500L
        body.currency("currency") == Currency.getInstance("EUR")
        errors == []
    }

    def "an upper-case UUID reads as the same id"() {
        expect:
        RequestBody.parse("""{"accountId":"${ACCOUNT.toUpperCase()}"}""", FUNDING, errors).uuid("accountId") == UUID.fromString(ACCOUNT)
        errors == []
    }

    def "amountMinor #raw is rejected"() {
        when:
        def amount = RequestBody.parse("""{"amountMinor":$raw}""", FUNDING, errors).amountMinor("amountMinor")

        then:
        amount == null
        errors == [new Problem.FieldError("amountMinor", AMOUNT_RULE)]

        where:
        raw << ["0", "-1", "100000001", "1.5", "1.0", "1e3", '"10"', "9223372036854775808", "null", "true", "[5]"]
    }

    def "amountMinor #raw is accepted"() {
        expect:
        RequestBody.parse("""{"amountMinor":$raw}""", FUNDING, errors).amountMinor("amountMinor") == raw as long
        errors == []

        where:
        raw << ["1", "100000000"]
    }

    def "currency #raw is rejected"() {
        when:
        def currency = RequestBody.parse("""{"currency":$raw}""", FUNDING, errors).currency("currency")

        then:
        currency == null
        errors == [new Problem.FieldError("currency", "must be a supported currency code")]

        where:
        raw << ['"eur"', '"USD"', '" EUR"', '"EURO"', "978", "null"]
    }

    def "accountId #raw is rejected"() {
        when:
        def id = RequestBody.parse("""{"accountId":$raw}""", FUNDING, errors).uuid("accountId")

        then:
        id == null
        errors == [new Problem.FieldError("accountId", "must be a UUID")]

        where:
        raw << ['"nope"', "42", '"1-1-1-1-1"', "null", '"' + ACCOUNT + ' "']
    }

    def "an optional text field may be absent, or a string of up to the limit in characters (#why)"() {
        expect:
        RequestBody.parse(raw, ["reference"] as Set, errors).optionalText("reference", 140) == expected
        errors == []

        where:
        why                                  | raw                                        | expected
        "absent"                             | '{}'                                       | null
        "empty"                              | '{"reference":""}'                         | ""
        "140 characters"                     | """{"reference":"${'r' * 140}"}"""         | "r" * 140
        "140 accented characters"            | """{"reference":"${'é' * 140}"}"""         | "é" * 140
        "140 emoji (two UTF-16 units each)"  | """{"reference":"${'😀' * 140}"}"""         | "😀" * 140
    }

    def "an optional text field that is #why is rejected"() {
        when:
        def text = RequestBody.parse(raw, ["reference"] as Set, errors).optionalText("reference", 140)

        then:
        text == null
        errors == [new Problem.FieldError("reference", "must be a string of at most 140 characters")]

        where:
        why               | raw
        "141 characters"  | """{"reference":"${'r' * 141}"}"""
        "a number"        | '{"reference":42}'
        "null"            | '{"reference":null}'
        "an object"       | '{"reference":{"text":"x"}}'
    }

    def "a missing field is reported as required"() {
        when:
        RequestBody.parse("{}", FUNDING, errors).uuid("accountId")

        then:
        errors == [new Problem.FieldError("accountId", "is required")]
    }

    def "a field the operation doesn't take is rejected, never ignored"() {
        when:
        RequestBody.parse("""{"currency":"EUR","fee":0,"ownerId":"$ACCOUNT"}""", FUNDING, errors)

        then:
        errors*.field as Set == ["fee", "ownerId"] as Set
        errors*.message.unique() == ["is not allowed"]
    }

    def "a body that isn't exactly one JSON object is rejected: #why"() {
        when:
        def body = RequestBody.parse(raw, FUNDING, errors)

        then: "one error for the body, and the fields add none of their own"
        body.currency("currency") == null
        errors == [new Problem.FieldError("body", "must be one JSON object")]

        where:
        why                        | raw
        "empty"                    | ""
        "not JSON"                 | "currency=EUR"
        "an array"                 | '[{"currency":"EUR"}]'
        "a string"                 | '"EUR"'
        "truncated"                | '{"currency":"EUR"'
        "trailing data"            | '{"currency":"EUR"} {"currency":"USD"}'
        "a duplicated field"       | '{"currency":"EUR","currency":"USD"}'
    }

    def "every problem is reported at once, unknown fields first, then fields in the order they are read"() {
        when:
        def body = RequestBody.parse('{"amountMinor":0,"currency":"usd","extra":1}', FUNDING, errors)
        body.uuid("accountId")
        body.amountMinor("amountMinor")
        body.currency("currency")

        then:
        errors*.field == ["extra", "accountId", "amountMinor", "currency"]
    }
}
