package io.paymentslab.money

import spock.lang.Specification

class SupportedCurrenciesSpec extends Specification {

    def "EUR is supported"() {
        expect:
        SupportedCurrencies.parse("EUR") == Currency.getInstance("EUR")
        SupportedCurrencies.require(Currency.getInstance("EUR")) == Currency.getInstance("EUR")
    }

    def "rejects currency code '#code'"() {
        when:
        SupportedCurrencies.parse(code)

        then:
        thrown(UnsupportedCurrencyException)

        where:
        code << ["USD", "eur", "EURO", "", " EUR", null]
    }

    def "rejects a valid ISO currency the system does not support"() {
        when:
        SupportedCurrencies.require(Currency.getInstance("USD"))

        then:
        thrown(UnsupportedCurrencyException)
    }
}
