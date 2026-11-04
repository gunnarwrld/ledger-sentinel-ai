package io.paymentslab.api

import static io.paymentslab.support.ApiClient.json
import static io.paymentslab.support.ApiClient.replayed

import io.paymentslab.app.App
import io.paymentslab.ledger.FundingService
import io.paymentslab.ledger.InvariantChecker
import io.paymentslab.ledger.LedgerService
import io.paymentslab.money.TransferAmount
import io.paymentslab.support.ApiClient
import io.paymentslab.support.CallerFixtures
import io.paymentslab.support.CallerFixtures.TestCaller
import io.paymentslab.support.Concurrency
import io.paymentslab.support.LedgerFixtures
import io.paymentslab.support.TestDatabase
import io.paymentslab.support.TestDatabases
import java.net.http.HttpResponse
import spock.lang.Shared
import spock.lang.Specification

/**
 * Risk R4 through the API: ordered row locks prevent overdrafts and deadlocks under concurrency, and
 * duplicates never move money twice (I8, I11). Requests are released together by a latch. The app
 * runs with the production default pool of 10 connections.
 */
class TransferConcurrencySpec extends Specification {

    static final Currency EUR = Currency.getInstance("EUR")

    @Shared TestDatabase db = TestDatabases.create()
    @Shared App app
    @Shared ApiClient api
    @Shared TestCaller alice
    @Shared TestCaller bob
    InvariantChecker invariants = new InvariantChecker()

    def setupSpec() {
        app = App.start(db.appConfig(10))
        api = new ApiClient(app.port())
        alice = CallerFixtures.customer(db.dsl)
        bob = CallerFixtures.customer(db.dsl)
    }

    def cleanupSpec() {
        app?.close()
        db?.close()
    }

    def cleanup() {
        assert invariants.check(db.dsl) == []
    }

    def "50 concurrent duplicates make exactly one payment and one entry, and all get the same response"() {
        given:
        def source = fundedAccount(alice, 100_00)
        def destination = LedgerFixtures.accountFor(db.dsl, bob.id)
        def key = newKey()
        def payments = count("payments")

        when:
        def outcome = Concurrency.run(50) { transfer(alice, key, source, destination, 10_00) }
        List<HttpResponse<String>> responses = outcome.results

        then:
        outcome.failures == []
        responses*.statusCode().unique() == [201]
        responses*.body().unique().size() == 1
        responses.count { !replayed(it) } == 1
        count("payments") == payments + 1
        entriesOn(source) == 2 // the funding and this one transfer
        balance(source) == 90_00
        balance(destination) == 10_00
    }

    def "20 debits of 10 racing for a balance of 100: exactly 10 POSTED, 10 DECLINED, no errors, balance 0"() {
        given:
        def source = fundedAccount(alice, 100)
        def destinations = (1..20).collect { LedgerFixtures.accountFor(db.dsl, bob.id) }

        when:
        def outcome = Concurrency.run(20) { int worker -> transfer(alice, newKey(), source, destinations[worker], 10) }
        List<HttpResponse<String>> responses = outcome.results

        then:
        outcome.failures == []
        responses*.statusCode().unique() == [201]
        responses.collect { json(it).status }.countBy { it } == [POSTED: 10, DECLINED: 10]
        responses.findAll { json(it).status == "DECLINED" }.collect { json(it).declineReason }.unique() == ["INSUFFICIENT_FUNDS"]
        balance(source) == 0
        destinations.sum { balance(it) } == 100
    }

    def "transfers in opposite directions never surface a deadlock: every request posts"() {
        given:
        def a = fundedAccount(alice, 1_000)
        def b = fundedAccount(bob, 1_000)

        when: "four workers, two per direction, each send 25 one-cent transfers back to back"
        def outcome = Concurrency.run(4) { int worker ->
            def (from, owner, to) = worker % 2 == 0 ? [a, alice, b] : [b, bob, a]
            (1..25).collect { transfer(owner, newKey(), from, to, 1) }
        }
        List<HttpResponse<String>> responses = outcome.results.flatten()

        then:
        outcome.failures == []
        responses.size() == 100
        responses*.statusCode().unique() == [201]
        responses.collect { json(it).status }.unique() == ["POSTED"]
        balance(a) == 1_000
        balance(b) == 1_000
    }

    def "100 concurrent transfers into one hot account all post, and the balance is exact"() {
        given: "ten sources holding 10 cents each"
        def hot = LedgerFixtures.accountFor(db.dsl, bob.id)
        def sources = (1..10).collect { fundedAccount(alice, 10) }

        when:
        def outcome = Concurrency.run(100) { int worker -> transfer(alice, newKey(), sources[worker % 10], hot, 1) }
        List<HttpResponse<String>> responses = outcome.results

        then:
        outcome.failures == []
        responses*.statusCode().unique() == [201]
        responses.collect { json(it).status }.unique() == ["POSTED"]
        balance(hot) == 100
        sources.every { balance(it) == 0 }
    }

    private HttpResponse<String> transfer(TestCaller owner, String key, UUID source, UUID destination, long amount) {
        api.post("/v1/transfers", owner.bearer, key,
                """{"sourceAccountId":"$source","destinationAccountId":"$destination","amountMinor":$amount,"currency":"EUR"}""")
    }

    private UUID fundedAccount(TestCaller owner, long amount) {
        def account = LedgerFixtures.accountFor(db.dsl, owner.id)
        db.inTransaction { tx -> new FundingService(new LedgerService()).fund(tx, account, TransferAmount.of(amount, EUR)) }
        account
    }

    private long balance(UUID account) {
        LedgerFixtures.balance(db.dsl, account)
    }

    private long entriesOn(UUID account) {
        db.dsl.fetchValue("SELECT count(*) FROM postings WHERE account_id = ?::uuid", account) as long
    }

    private long count(String table) {
        db.dsl.fetchValue("SELECT count(*) FROM " + table) as long
    }

    private static String newKey() {
        UUID.randomUUID().toString()
    }
}
