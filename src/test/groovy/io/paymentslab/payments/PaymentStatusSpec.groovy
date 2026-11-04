package io.paymentslab.payments

import static io.paymentslab.payments.PaymentStatus.DECLINED
import static io.paymentslab.payments.PaymentStatus.POSTED
import static io.paymentslab.payments.PaymentStatus.REVERSED
import static io.paymentslab.payments.PaymentStatus.REVIEW_REQUIRED

import spock.lang.Specification

/** The payment state machine (DESIGN §4.3, invariant I12). */
class PaymentStatusSpec extends Specification {

    static final Set<List<PaymentStatus>> ALLOWED = [
            [REVIEW_REQUIRED, POSTED],
            [REVIEW_REQUIRED, DECLINED],
            [POSTED, REVERSED],
    ] as Set

    def "#from → #to is #verdict"() {
        expect:
        from.canBecome(to) == ([from, to] in ALLOWED)

        where:
        [from, to] << [PaymentStatus.values(), PaymentStatus.values()].combinations()
        verdict = [from, to] in ALLOWED ? "allowed" : "refused"
    }

    def "a new payment can start as #status: #expected"() {
        expect:
        status.canStart() == expected

        where:
        status          | expected
        POSTED          | true
        DECLINED        | true
        REVIEW_REQUIRED | true
        REVERSED        | false
    }

    def "only POSTED and REVERSED payments have a journal entry (money moved)"() {
        expect:
        PaymentStatus.values().findAll { it.movedMoney() } as Set == [POSTED, REVERSED] as Set
    }
}
