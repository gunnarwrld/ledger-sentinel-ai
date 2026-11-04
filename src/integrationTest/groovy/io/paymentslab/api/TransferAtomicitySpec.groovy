package io.paymentslab.api

import static io.paymentslab.support.ApiClient.json
import static io.paymentslab.support.ApiClient.replayed

import io.paymentslab.app.App
import io.paymentslab.ledger.FundingService
import io.paymentslab.ledger.InvariantChecker
import io.paymentslab.ledger.LedgerService
import io.paymentslab.money.TransferAmount
import io.paymentslab.support.ApiClient
import io.paymentslab.support.ArmedFaults
import io.paymentslab.support.CallerFixtures
import io.paymentslab.support.CallerFixtures.TestCaller
import io.paymentslab.support.LedgerFixtures
import io.paymentslab.support.TestDatabase
import io.paymentslab.support.TestDatabases
import spock.lang.Shared
import spock.lang.Specification

/**
 * Invariant I10 and crash point C1: payment, entry, postings, balances, outbox event and idempotency
 * record commit together or not at all. The fault is injected in-process (DESIGN R6: a real kill -9
 * comes in Phase 3).
 */
class TransferAtomicitySpec extends Specification {

    static final Currency EUR = Currency.getInstance("EUR")

    @Shared TestDatabase db = TestDatabases.create()
    @Shared ArmedFaults faults = new ArmedFaults()
    @Shared App app
    @Shared ApiClient api
    @Shared TestCaller alice
    @Shared TestCaller bob
    @Shared TestCaller operator

    def setupSpec() {
        app = App.start(db.appConfig(), faults)
        api = new ApiClient(app.port())
        alice = CallerFixtures.customer(db.dsl)
        bob = CallerFixtures.customer(db.dsl)
        operator = CallerFixtures.operator(db.dsl)
    }

    def cleanupSpec() {
        app?.close()
        db?.close()
    }

    def "a fault after the postings, before commit, leaves no trace; the same key then posts exactly once"() {
        given:
        def source = LedgerFixtures.accountFor(db.dsl, alice.id)
        def destination = LedgerFixtures.accountFor(db.dsl, bob.id)
        db.inTransaction { tx -> new FundingService(new LedgerService()).fund(tx, source, TransferAmount.of(100_00, EUR)) }
        def key = UUID.randomUUID().toString()
        def body = """{"sourceAccountId":"$source","destinationAccountId":"$destination","amountMinor":2500,"currency":"EUR"}"""
        def before = snapshot(source, destination)
        faults.arm("transfer.before-commit")

        when:
        def failed = api.post("/v1/transfers", alice.bearer, key, body)

        then: "the fault fired at the last step, and the client got a generic error"
        faults.fired == ["transfer.before-commit"]
        failed.statusCode() == 500
        json(failed).code == "INTERNAL"

        and: "nothing of the transfer survived: no payment, entry, postings, balance change, outbox event or key"
        snapshot(source, destination) == before
        new InvariantChecker().check(db.dsl) == []

        when: "the client retries with the same key"
        def retried = api.post("/v1/transfers", alice.bearer, key, body)
        def again = api.post("/v1/transfers", alice.bearer, key, body)

        then: "it runs once, as if the first attempt never happened, and later repeats replay it"
        retried.statusCode() == 201
        !replayed(retried)
        json(retried).status == "POSTED"
        replayed(again)
        snapshot(source, destination) == before + [payments: before.payments + 1, entries: before.entries + 1,
                                                   postings: before.postings + 2, outbox: before.outbox + 1, keys: before.keys + 1,
                                                   sourceBalance: 75_00, destinationBalance: 25_00]
        new InvariantChecker().check(db.dsl) == []
    }

    def "a fault after the reversal's writes, before commit, leaves the transfer POSTED; the same key then reverses once"() {
        given:
        def source = LedgerFixtures.accountFor(db.dsl, alice.id)
        def destination = LedgerFixtures.accountFor(db.dsl, bob.id)
        db.inTransaction { tx -> new FundingService(new LedgerService()).fund(tx, source, TransferAmount.of(100_00, EUR)) }
        def created = api.post("/v1/transfers", alice.bearer, UUID.randomUUID().toString(),
                """{"sourceAccountId":"$source","destinationAccountId":"$destination","amountMinor":2500,"currency":"EUR"}""")
        assert created.statusCode() == 201
        def transferId = json(created).id
        def key = UUID.randomUUID().toString()
        def before = snapshot(source, destination)
        faults.arm("reversal.before-commit")

        when:
        def failed = api.post("/v1/transfers/$transferId/reversal", operator.bearer, key, "")

        then: "the fault fired at the reversal's last step, and the client got a generic error"
        faults.fired.last() == "reversal.before-commit"
        failed.statusCode() == 500
        json(failed).code == "INTERNAL"

        and: "nothing of the reversal survived: no entry, postings, balance change, status change, event or key"
        snapshot(source, destination) == before
        paymentStatus(transferId) == "POSTED"
        new InvariantChecker().check(db.dsl) == []

        when: "the operator retries with the same key"
        def retried = api.post("/v1/transfers/$transferId/reversal", operator.bearer, key, "")

        then:
        retried.statusCode() == 201
        !replayed(retried)
        json(retried).status == "REVERSED"
        paymentStatus(transferId) == "REVERSED"
        snapshot(source, destination) == before + [entries: before.entries + 1, postings: before.postings + 2,
                                                   outbox: before.outbox + 1, keys: before.keys + 1,
                                                   sourceBalance: 100_00, destinationBalance: 0]
        new InvariantChecker().check(db.dsl) == []
    }

    private String paymentStatus(Object transferId) {
        db.dsl.fetchValue("SELECT status FROM payments WHERE id = ?::uuid", transferId.toString()) as String
    }

    private Map snapshot(UUID source, UUID destination) {
        [payments          : count("payments"),
         entries           : count("journal_entries"),
         postings          : count("postings"),
         outbox            : count("outbox_events"),
         keys              : count("idempotency_keys"),
         sourceBalance     : LedgerFixtures.balance(db.dsl, source),
         destinationBalance: LedgerFixtures.balance(db.dsl, destination)]
    }

    private long count(String table) {
        db.dsl.fetchValue("SELECT count(*) FROM " + table) as long
    }
}
