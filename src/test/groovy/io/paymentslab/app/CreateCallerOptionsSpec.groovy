package io.paymentslab.app

import io.paymentslab.api.CallerRole
import spock.lang.Specification

/** Argument parsing for {@code create-caller --role CUSTOMER|OPERATOR --name NAME}. */
class CreateCallerOptionsSpec extends Specification {

    def "parses #args"() {
        expect:
        CreateCallerCommand.parse(args) == new CreateCallerCommand.Options(role, name)

        where:
        args                                      | role                | name
        ["--role", "CUSTOMER", "--name", "alice"] | CallerRole.CUSTOMER | "alice"
        ["--name", "ops", "--role", "OPERATOR"]   | CallerRole.OPERATOR | "ops"
    }

    def "rejects #args, saying '#hint'"() {
        when:
        CreateCallerCommand.parse(args)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(hint)

        where:
        args                                                          | hint
        []                                                            | "--role is required"
        ["--role", "CUSTOMER"]                                        | "--name is required"
        ["--name", "alice"]                                           | "--role is required"
        ["--role", "ADMIN", "--name", "alice"]                        | "CUSTOMER or OPERATOR"
        ["--role", "customer", "--name", "alice"]                     | "CUSTOMER or OPERATOR"
        ["--role"]                                                    | "--role needs a value"
        ["--role", "CUSTOMER", "--name"]                              | "--name needs a value"
        ["--role", "CUSTOMER", "--role", "OPERATOR", "--name", "a"]   | "--role given twice"
        ["--role", "CUSTOMER", "--name", "a", "--admin", "yes"]       | "unknown option --admin"
        ["--role", "CUSTOMER", "--name", "a", "extra"]                | "unknown option extra"
    }
}
