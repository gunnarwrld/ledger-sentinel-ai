package io.paymentslab.money

import spock.lang.Specification

class TransferAmountSpec extends Specification {

    static final Currency EUR = Currency.getInstance("EUR")

    def "the maximum single transfer is 1,000,000.00 EUR"() {
        expect:
        TransferAmount.MAX_MINOR == 100_000_000L
    }

    def "accepts #minor minor units"() {
        expect:
        TransferAmount.of(minor, EUR).money() == Money.of(minor, EUR)

        where:
        minor << [1L, 2_500L, TransferAmount.MAX_MINOR]
    }

    def "rejects #minor minor units"() {
        when:
        TransferAmount.of(minor, EUR)

        then:
        thrown(InvalidAmountException)

        where:
        minor << [0L, -1L, TransferAmount.MAX_MINOR + 1, Long.MIN_VALUE, Long.MAX_VALUE]
    }

    def "rejects a currency the system does not support"() {
        when:
        TransferAmount.of(100, Currency.getInstance("USD"))

        then:
        thrown(UnsupportedCurrencyException)
    }
}
