package io.paymentslab.money

import spock.lang.Specification

class MoneySpec extends Specification {

    static final Currency EUR = Currency.getInstance("EUR")
    static final Currency USD = Currency.getInstance("USD")

    def "adds and subtracts amounts in the same currency"() {
        expect:
        Money.of(1_000, EUR).plus(Money.of(250, EUR)) == Money.of(1_250, EUR)
        Money.of(1_000, EUR).minus(Money.of(250, EUR)) == Money.of(750, EUR)
    }

    def "subtraction may go below zero, because SYSTEM account balances can be negative"() {
        expect:
        Money.of(100, EUR).minus(Money.of(250, EUR)) == Money.of(-150, EUR)
    }

    def "negates an amount"() {
        expect:
        Money.of(250, EUR).negate() == Money.of(-250, EUR)
    }

    def "rejects arithmetic that would overflow a long instead of wrapping: #scenario"() {
        when:
        operation.call()

        then:
        thrown(ArithmeticException)

        where:
        scenario            | operation
        "MAX + 1"           | { -> Money.of(Long.MAX_VALUE, EUR).plus(Money.of(1, EUR)) }
        "MIN - 1"           | { -> Money.of(Long.MIN_VALUE, EUR).minus(Money.of(1, EUR)) }
        "-MIN (no +MIN)"    | { -> Money.of(Long.MIN_VALUE, EUR).negate() }
    }

    def "rejects mixing currencies"() {
        when:
        Money.of(100, EUR).plus(Money.of(100, USD))

        then:
        thrown(CurrencyMismatchException)
    }

    def "requires a currency"() {
        when:
        Money.of(1, null)

        then:
        thrown(NullPointerException)
    }
}
