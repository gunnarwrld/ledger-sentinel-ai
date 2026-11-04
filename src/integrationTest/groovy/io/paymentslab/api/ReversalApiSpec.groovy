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
import io.paymentslab.support.ContractSchemas
import io.paymentslab.support.LedgerFixtures
import io.paymentslab.support.TestDatabase
import io.paymentslab.support.TestDatabases
import java.net.http.HttpResponse
import spock.lang.Shared
import spock.lang.Specification

/**
 * POST /v1/transfers/{transferId}/reversal (D7 / ADR 0010): operators only, idempotent, and history is
 * never edited: a reversal is a new entry that mirrors the original.
 */
class ReversalApiSpec extends Specification {

    static final Currency EUR = Currency.getInstance("EUR")

    @Shared TestDatabase db = TestDatabases.create()
    @Shared App app
    @Shared ApiClient api
    @Shared TestCaller operator
    @Shared TestCaller otherOperator
    @Shared TestCaller alice
    @Shared TestCaller bob
    @Shared TestCaller carol
    InvariantChecker invariants = new InvariantChecker()

    def setupSpec() {
        // Concurrent reversals each hold a connection while they wait for the payment's row lock.
        app = App.start(db.appConfig(10))
        api = new ApiClient(app.port())
        operator = CallerFixtures.operator(db.dsl)
        otherOperator = CallerFixtures.operator(db.dsl)
        alice = CallerFixtures.customer(db.dsl)
        bob = CallerFixtures.customer(db.dsl)
        carol = CallerFixtures.customer(db.dsl)
    }

    def cleanupSpec() {
        app?.close()
        db?.close()
    }

    def cleanup() {
        assert invariants.check(db.dsl) == []
    }

    def "an operator reverses a POSTED transfer: a mirroring entry, balances restored, the transfer reads back REVERSED"() {
        given:
        def source = fundedAccount(alice, 100_00)
        def destination = LedgerFixtures.accountFor(db.dsl, bob.id)
        def posted = postTransfer(alice, source, destination, 40_00)

        when:
        def response = reverse(operator, posted.id)
        def reversed = json(response)

        then:
        response.statusCode() == 201
        !replayed(response)
        reversed.status == "REVERSED"
        reversed.findAll { it.key != "status" } == posted.findAll { it.key != "status" }
        ContractSchemas.violations(reversed, "Transfer") == []
        balance(source) == 100_00
        balance(destination) == 0

        and: "both statements show the original and its mirror; history is not edited"
        def sourceLines = json(api.get("/v1/accounts/$source/entries", alice.bearer)).items
        def destinationLines = json(api.get("/v1/accounts/$destination/entries", bob.bearer)).items
        sourceLines[0..1].collect { [it.kind, it.amountMinor] } == [["REVERSAL", 40_00], ["TRANSFER", -40_00]]
        destinationLines.collect { [it.kind, it.amountMinor] } == [["REVERSAL", -40_00], ["TRANSFER", 40_00]]
        sourceLines[0].journalEntryId == destinationLines[0].journalEntryId

        and: "the transfer reads back REVERSED, the operator is on record, and one more event waits in the outbox"
        json(api.get("/v1/transfers/$posted.id", alice.bearer)) == reversed
        reversedBy(posted.id) == operator.id
        outboxEventTypes(posted.id) == ["transfer.posted", "transfer.reversed"]
    }

    def "the same key replays the reversal; any other attempt gets 409 INVALID_STATE_TRANSITION and changes nothing"() {
        given:
        def source = fundedAccount(alice, 50_00)
        def destination = LedgerFixtures.accountFor(db.dsl, bob.id)
        def posted = postTransfer(alice, source, destination, 20_00)
        def key = newKey()
        def first = reverse(operator, posted.id, key)

        when:
        def sameKey = reverse(operator, posted.id, key)
        def anotherKey = reverse(operator, posted.id, newKey())
        def anotherOperator = reverse(otherOperator, posted.id, newKey())

        then:
        first.statusCode() == 201
        replayed(sameKey)
        sameKey.body() == first.body()
        [anotherKey, anotherOperator]*.statusCode() == [409, 409]
        [anotherKey, anotherOperator].collect { json(it).code } == ["INVALID_STATE_TRANSITION", "INVALID_STATE_TRANSITION"]
        ContractSchemas.violations(json(anotherKey), "Problem") == []
        reversalsOf(posted.id) == 1
        reversedBy(posted.id) == operator.id
        balance(source) == 50_00
    }

    def "a DECLINED transfer can't be reversed: 409 INVALID_STATE_TRANSITION, nothing written"() {
        given:
        def source = fundedAccount(alice, 10_00)
        def declined = postTransfer(alice, source, LedgerFixtures.accountFor(db.dsl, bob.id), 50_00)
        assert declined.status == "DECLINED"
        def before = counts()

        when:
        def response = reverse(operator, declined.id)

        then:
        response.statusCode() == 409
        json(response).code == "INVALID_STATE_TRANSITION"
        counts() == before
        statusOf(declined.id) == "DECLINED"
    }

    def "a receiver who spent the money: 422 INSUFFICIENT_FUNDS, nothing written; the same key works once it's covered"() {
        given: "bob received 100.00 and spent 70.00 of it"
        def source = fundedAccount(alice, 100_00)
        def receiver = LedgerFixtures.accountFor(db.dsl, bob.id)
        def posted = postTransfer(alice, source, receiver, 100_00)
        postTransfer(bob, receiver, LedgerFixtures.accountFor(db.dsl, carol.id), 70_00)
        def key = newKey()
        def before = counts()

        when:
        def refused = reverse(operator, posted.id, key)

        then:
        refused.statusCode() == 422
        json(refused).code == "INSUFFICIENT_FUNDS"
        ContractSchemas.violations(json(refused), "Problem") == []
        counts() == before
        statusOf(posted.id) == "POSTED"
        balance(receiver) == 30_00

        when: "bob's account is topped up and the operator retries with the same key"
        fund(receiver, 70_00)
        def retried = reverse(operator, posted.id, key)

        then: "the refusal wasn't stored, so the reversal runs now"
        retried.statusCode() == 201
        !replayed(retried)
        json(retried).status == "REVERSED"
        balance(source) == 100_00
        balance(receiver) == 0
    }

    def "a FROZEN #side account can still be reversed (ADR 0010)"() {
        given:
        def source = fundedAccount(alice, 100_00)
        def destination = LedgerFixtures.accountFor(db.dsl, bob.id)
        def posted = postTransfer(alice, source, destination, 40_00)
        setStatus(side == "source" ? source : destination, "FROZEN")

        when:
        def response = reverse(operator, posted.id)

        then:
        response.statusCode() == 201
        json(response).status == "REVERSED"
        balance(source) == 100_00
        balance(destination) == 0

        where:
        side << ["source", "destination"]
    }

    def "a CLOSED #side account blocks the reversal: 422 ACCOUNT_NOT_ACTIVE, nothing written"() {
        given:
        def source = fundedAccount(alice, 100_00)
        def destination = LedgerFixtures.accountFor(db.dsl, bob.id)
        def posted = postTransfer(alice, source, destination, 40_00)
        setStatus(side == "source" ? source : destination, "CLOSED")
        def before = counts()

        when:
        def response = reverse(operator, posted.id)

        then:
        response.statusCode() == 422
        json(response).code == "ACCOUNT_NOT_ACTIVE"
        counts() == before
        statusOf(posted.id) == "POSTED"
        balance(source) == 60_00
        balance(destination) == 40_00

        where:
        side << ["source", "destination"]
    }

    def "customers can't reverse transfers, not even their own: 403 FORBIDDEN, nothing written"() {
        given:
        def posted = postTransfer(alice, fundedAccount(alice, 10_00), LedgerFixtures.accountFor(db.dsl, bob.id), 5_00)
        def before = counts()

        when:
        def asSender = reverse(alice, posted.id)
        def asReceiver = reverse(bob, posted.id)

        then:
        [asSender, asReceiver]*.statusCode() == [403, 403]
        json(asSender).code == "FORBIDDEN"
        counts() == before
        statusOf(posted.id) == "POSTED"
    }

    def "#why gets 404, nothing written"() {
        given: "a control: a real transfer reverses, so a 404 below can't mean the route is missing"
        def posted = postTransfer(alice, fundedAccount(alice, 10_00), LedgerFixtures.accountFor(db.dsl, bob.id), 1_00)
        def control = reverse(operator, posted.id)
        assert control.statusCode() == 201
        def before = counts()

        when:
        def response = reverse(operator, transferId)

        then:
        response.statusCode() == 404
        json(response).code == "NOT_FOUND"
        counts() == before

        where:
        why                     | transferId
        "an unknown transfer"   | UUID.randomUUID().toString()
        "an id that isn't a UUID" | "not-a-uuid"
    }

    def "a reversal takes no parameters: a body of #why → #expected"() {
        given:
        def posted = postTransfer(alice, fundedAccount(alice, 10_00), LedgerFixtures.accountFor(db.dsl, bob.id), 1_00)

        when:
        def response = api.post("/v1/transfers/$posted.id/reversal", operator.bearer, newKey(), body)

        then:
        response.statusCode() == expected
        expected == 201 || json(response).errors*.field == fields
        statusOf(posted.id) == (expected == 201 ? "REVERSED" : "POSTED")

        where:
        why                                  | body                  | expected | fields
        "nothing"                            | ""                    | 201      | null
        "an empty object"                    | "{}"                  | 201      | null
        "an amount (no partial reversals)"   | '{"amountMinor":50}'  | 400      | ["amountMinor"]
        "something that isn't JSON"          | "not json"            | 400      | ["body"]
    }

    def "a reversal without an Idempotency-Key is refused: 400 IDEMPOTENCY_KEY_MISSING, nothing written"() {
        given:
        def posted = postTransfer(alice, fundedAccount(alice, 10_00), LedgerFixtures.accountFor(db.dsl, bob.id), 1_00)
        def before = counts()

        when:
        def response = api.post("/v1/transfers/$posted.id/reversal", operator.bearer, null, "")

        then:
        response.statusCode() == 400
        json(response).code == "IDEMPOTENCY_KEY_MISSING"
        counts() == before
    }

    def "ten concurrent reversals of one transfer: exactly one succeeds, nine get 409, and one mirror entry exists"() {
        given:
        def source = fundedAccount(alice, 50_00)
        def destination = LedgerFixtures.accountFor(db.dsl, bob.id)
        def posted = postTransfer(alice, source, destination, 20_00)

        when:
        def outcome = Concurrency.run(10) { reverse(operator, posted.id, newKey()) }
        List<HttpResponse<String>> responses = outcome.results

        then:
        outcome.failures == []
        responses*.statusCode().countBy { it } == [201: 1, 409: 9]
        responses.findAll { it.statusCode() == 409 }.collect { json(it).code }.unique() == ["INVALID_STATE_TRANSITION"]
        reversalsOf(posted.id) == 1
        balance(source) == 50_00
        balance(destination) == 0
    }

    def "a reversal racing the receiver's own spending: exactly one of them gets the money, in every round"() {
        when: "20 rounds: bob receives 1.00, then the operator reverses it while bob sends it on"
        def rounds = (1..20).collect {
            def source = fundedAccount(alice, 1_00)
            def receiver = LedgerFixtures.accountFor(db.dsl, bob.id)
            def elsewhere = LedgerFixtures.accountFor(db.dsl, carol.id)
            def posted = postTransfer(alice, source, receiver, 1_00)
            def race = Concurrency.run(2) { int worker ->
                worker == 0 ? reverse(operator, posted.id, newKey())
                        : api.post("/v1/transfers", bob.bearer, newKey(), transferBody(receiver, elsewhere, 1_00))
            }
            assert race.failures == []
            HttpResponse<String> reversal = race.results[0]
            HttpResponse<String> spend = race.results[1]
            [reversal.statusCode(), json(spend).status, balance(receiver), balance(source)]
        }
        println "reversal race outcomes: ${rounds.countBy { it[0..1] }}"

        then: "either the reversal won and the spend was declined, or the spend won and the reversal was refused"
        rounds.every { it in [[201, "DECLINED", 0L, 1_00L], [422, "POSTED", 0L, 0L]] }
    }

    private Map postTransfer(TestCaller owner, UUID source, UUID destination, long amount) {
        def response = api.post("/v1/transfers", owner.bearer, newKey(), transferBody(source, destination, amount))
        assert response.statusCode() == 201
        json(response) as Map
    }

    private static String transferBody(UUID source, UUID destination, long amount) {
        """{"sourceAccountId":"$source","destinationAccountId":"$destination","amountMinor":$amount,"currency":"EUR"}"""
    }

    private HttpResponse<String> reverse(TestCaller caller, Object transferId, String key = newKey()) {
        api.post("/v1/transfers/$transferId/reversal", caller.bearer, key, "")
    }

    private UUID fundedAccount(TestCaller owner, long amount) {
        def account = LedgerFixtures.accountFor(db.dsl, owner.id)
        fund(account, amount)
        account
    }

    private void fund(UUID account, long amount) {
        db.inTransaction { tx -> new FundingService(new LedgerService()).fund(tx, account, TransferAmount.of(amount, EUR)) }
    }

    private void setStatus(UUID account, String status) {
        db.dsl.execute("UPDATE accounts SET status = ? WHERE id = ?::uuid", status, account)
    }

    private long balance(UUID account) {
        LedgerFixtures.balance(db.dsl, account)
    }

    private String statusOf(Object transferId) {
        db.dsl.fetchValue("SELECT status FROM payments WHERE id = ?::uuid", transferId.toString()) as String
    }

    private UUID reversedBy(Object transferId) {
        db.dsl.fetchValue("SELECT reversed_by FROM payments WHERE id = ?::uuid", transferId.toString()) as UUID
    }

    /** REVERSAL entries that point at the transfer's entry. */
    private long reversalsOf(Object transferId) {
        db.dsl.fetchValue("""
                SELECT count(*) FROM journal_entries r JOIN payments p ON r.reverses_id = p.journal_entry_id
                WHERE p.id = ?::uuid""", transferId.toString()) as long
    }

    private List<String> outboxEventTypes(Object transferId) {
        db.dsl.fetch("SELECT event_type FROM outbox_events WHERE aggregate_id = ?::uuid ORDER BY id", transferId.toString())
                .getValues(0, String)
    }

    private Map counts() {
        [payments: count("payments"), entries: count("journal_entries"), keys: count("idempotency_keys"),
         outbox: count("outbox_events"), reversed: count("payments WHERE status = 'REVERSED'")]
    }

    private long count(String from) {
        db.dsl.fetchValue("SELECT count(*) FROM " + from) as long
    }

    private static String newKey() {
        UUID.randomUUID().toString()
    }
}
