package io.paymentslab.api

import static io.paymentslab.support.ApiClient.contentType
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
import io.paymentslab.support.ContractSchemas
import io.paymentslab.support.LedgerFixtures
import io.paymentslab.support.LogCapture
import io.paymentslab.support.TestDatabase
import io.paymentslab.support.TestDatabases
import java.net.http.HttpResponse
import spock.lang.Shared
import spock.lang.Specification

/** POST and GET /v1/transfers (D4 / ADR 0009: a business decline is 201 with status DECLINED). */
class TransferApiSpec extends Specification {

    static final Currency EUR = Currency.getInstance("EUR")

    @Shared TestDatabase db = TestDatabases.create()
    @Shared App app
    @Shared ApiClient api
    @Shared TestCaller alice
    @Shared TestCaller bob
    @Shared TestCaller operator
    @Shared UUID bobAccount
    InvariantChecker invariants = new InvariantChecker()

    def setupSpec() {
        app = App.start(db.appConfig())
        api = new ApiClient(app.port())
        alice = CallerFixtures.customer(db.dsl)
        bob = CallerFixtures.customer(db.dsl)
        operator = CallerFixtures.operator(db.dsl)
        bobAccount = LedgerFixtures.accountFor(db.dsl, bob.id)
    }

    def cleanupSpec() {
        app?.close()
        db?.close()
    }

    def cleanup() {
        assert invariants.check(db.dsl) == []
    }

    def "a transfer posts one entry, moves both balances, and the creator reads back the same transfer"() {
        given:
        def source = fundedAccount(alice, 100_00)
        def destination = LedgerFixtures.accountFor(db.dsl, bob.id)

        when:
        def response = transfer(alice, newKey(), body(source, destination, 25_00, "rent share"))
        def posted = json(response)

        then:
        response.statusCode() == 201
        contentType(response).startsWith("application/json")
        !replayed(response)
        posted.status == "POSTED"
        posted.declineReason == null
        posted.sourceAccountId == source.toString()
        posted.destinationAccountId == destination.toString()
        posted.amountMinor == 25_00
        posted.currency == "EUR"
        posted.reference == "rent share"
        ContractSchemas.violations(posted, "Transfer") == []

        and: "both statements show the same entry, signed from each side"
        def sourceLine = json(api.get("/v1/accounts/$source/entries", alice.bearer)).items[0]
        def destinationLine = json(api.get("/v1/accounts/$destination/entries", bob.bearer)).items[0]
        [sourceLine.kind, sourceLine.amountMinor] == ["TRANSFER", -25_00]
        [destinationLine.kind, destinationLine.amountMinor] == ["TRANSFER", 25_00]
        sourceLine.journalEntryId == destinationLine.journalEntryId
        balance(source) == 75_00
        balance(destination) == 25_00

        and: "the transfer reads back identically, and one event waits in the outbox"
        json(api.get("/v1/transfers/$posted.id", alice.bearer)) == posted
        outboxEventTypes(posted.id) == ["transfer.posted"]
    }

    def "insufficient funds: 201 DECLINED(INSUFFICIENT_FUNDS), stored, with no entry and no money moved"() {
        given:
        def source = fundedAccount(alice, 10_00)
        def before = counts()

        when:
        def response = transfer(alice, newKey(), body(source, bobAccount, 25_00))
        def declined = json(response)

        then:
        response.statusCode() == 201
        declined.status == "DECLINED"
        declined.declineReason == "INSUFFICIENT_FUNDS"
        ContractSchemas.violations(declined, "Transfer") == []
        balance(source) == 10_00
        counts().entries == before.entries
        counts().payments == before.payments + 1

        and:
        json(api.get("/v1/transfers/$declined.id", alice.bearer)) == declined
        outboxEventTypes(declined.id) == ["transfer.declined"]
    }

    def "the overdraft boundary: one cent more than the balance is declined; exactly the balance posts and leaves 0"() {
        given:
        def source = fundedAccount(alice, 25_00)
        def destination = LedgerFixtures.accountFor(db.dsl, bob.id)

        when:
        def overByOne = transfer(alice, newKey(), body(source, destination, 25_01))
        def exact = transfer(alice, newKey(), body(source, destination, 25_00))

        then:
        overByOne.statusCode() == 201
        json(overByOne).status == "DECLINED"
        json(overByOne).declineReason == "INSUFFICIENT_FUNDS"
        json(exact).status == "POSTED"
        balance(source) == 0
        balance(destination) == 25_00
    }

    def "a frozen #side account: 201 DECLINED(ACCOUNT_NOT_ACTIVE), no money moved"() {
        given:
        def source = fundedAccount(alice, 10_00)
        def destination = LedgerFixtures.accountFor(db.dsl, bob.id)
        db.dsl.execute("UPDATE accounts SET status = 'FROZEN' WHERE id = ?::uuid", side == "source" ? source : destination)

        when:
        def declined = json(transfer(alice, newKey(), body(source, destination, 1_00)))

        then:
        declined.status == "DECLINED"
        declined.declineReason == "ACCOUNT_NOT_ACTIVE"
        balance(source) == 10_00
        balance(destination) == 0

        where:
        side << ["source", "destination"]
    }

    def "a decline is final for its key: after a top-up the same key replays DECLINED, and a new key posts"() {
        given:
        def source = fundedAccount(alice, 5_00)
        def key = newKey()
        def first = transfer(alice, key, body(source, bobAccount, 8_00))
        fund(source, 5_00)

        when:
        def sameKey = transfer(alice, key, body(source, bobAccount, 8_00))
        def anotherKey = transfer(alice, newKey(), body(source, bobAccount, 8_00))

        then:
        json(first).status == "DECLINED"
        replayed(sameKey)
        sameKey.body() == first.body()
        json(anotherKey).status == "POSTED"
        balance(source) == 2_00
    }

    def "#why gets the same 404 as any unknown account, and nothing is stored"() {
        given: "a control: alice's transfers work, so a 404 below can't mean a missing route"
        def mine = fundedAccount(alice, 10_00)
        def control = transfer(alice, newKey(), body(mine, bobAccount, 1_00))
        assert control.statusCode() == 201
        def before = counts()
        def reference = transfer(alice, newKey(), body(UUID.randomUUID(), bobAccount, 1_00))

        when:
        def from = source == "bob's" ? bobAccount : mine
        def to = destination == "unknown" ? UUID.randomUUID() : mine
        def response = transfer(alice, newKey(), body(from, to, 1_00))

        then:
        reference.statusCode() == 404
        response.statusCode() == 404
        json(response).code == "NOT_FOUND"
        response.body() == reference.body()
        counts() == before

        where:
        why                                  | source  | destination
        "a source owned by someone else"     | "bob's" | "own"
        "an unknown destination"             | "own"   | "unknown"
    }

    def "the same account on both sides is refused: 422 INVALID_ACCOUNT_COMBINATION, nothing stored"() {
        given:
        def account = fundedAccount(alice, 10_00)
        def before = counts()

        when:
        def response = transfer(alice, newKey(), body(account, account, 1_00))

        then:
        response.statusCode() == 422
        json(response).code == "INVALID_ACCOUNT_COMBINATION"
        ContractSchemas.violations(json(response), "Problem") == []
        counts() == before
    }

    def "operators can't create transfers: 403 FORBIDDEN, nothing stored"() {
        given:
        def before = counts()

        when:
        def response = transfer(operator, newKey(), body(bobAccount, UUID.randomUUID(), 1_00))

        then:
        response.statusCode() == 403
        json(response).code == "FORBIDDEN"
        counts() == before
    }

    def "a body with #why is rejected (400 naming #fields), and nothing is stored"() {
        given:
        def source = fundedAccount(alice, 10_00)
        def before = counts()

        when:
        def response = transfer(alice, newKey(), raw.replace("SRC", source.toString()).replace("DST", bobAccount.toString()))

        then:
        response.statusCode() == 400
        json(response).code == "VALIDATION_FAILED"
        json(response).errors*.field == fields
        counts() == before

        where:
        why                           | raw                                                                                                             | fields
        "a zero amount"               | '{"sourceAccountId":"SRC","destinationAccountId":"DST","amountMinor":0,"currency":"EUR"}'                       | ["amountMinor"]
        "a negative amount"           | '{"sourceAccountId":"SRC","destinationAccountId":"DST","amountMinor":-1,"currency":"EUR"}'                      | ["amountMinor"]
        "an amount above the max"     | '{"sourceAccountId":"SRC","destinationAccountId":"DST","amountMinor":100000001,"currency":"EUR"}'               | ["amountMinor"]
        "a fractional amount"         | '{"sourceAccountId":"SRC","destinationAccountId":"DST","amountMinor":1.5,"currency":"EUR"}'                     | ["amountMinor"]
        "an amount as a string"       | '{"sourceAccountId":"SRC","destinationAccountId":"DST","amountMinor":"10","currency":"EUR"}'                    | ["amountMinor"]
        "an amount beyond a long"     | '{"sourceAccountId":"SRC","destinationAccountId":"DST","amountMinor":9223372036854775808,"currency":"EUR"}'     | ["amountMinor"]
        "another currency"            | '{"sourceAccountId":"SRC","destinationAccountId":"DST","amountMinor":100,"currency":"USD"}'                     | ["currency"]
        "a 141-character reference"   | '{"sourceAccountId":"SRC","destinationAccountId":"DST","amountMinor":100,"currency":"EUR","reference":"' + 'r' * 141 + '"}' | ["reference"]
        "a status field"              | '{"sourceAccountId":"SRC","destinationAccountId":"DST","amountMinor":100,"currency":"EUR","status":"POSTED"}'  | ["status"]
        "no destination"              | '{"sourceAccountId":"SRC","amountMinor":100,"currency":"EUR"}'                                                  | ["destinationAccountId"]
    }

    def "the creator and operators can read a transfer; other customers get the same 404 as an unknown id"() {
        given: "a transfer that really exists (otherwise every response below is the same 404)"
        def source = fundedAccount(alice, 10_00)
        def created = transfer(alice, newKey(), body(source, bobAccount, 1_00))
        assert created.statusCode() == 201
        def posted = json(created)

        when:
        def asAlice = api.get("/v1/transfers/$posted.id", alice.bearer)
        def asOperator = api.get("/v1/transfers/$posted.id", operator.bearer)
        def asBob = api.get("/v1/transfers/$posted.id", bob.bearer)
        def unknown = api.get("/v1/transfers/${UUID.randomUUID()}", bob.bearer)

        then:
        json(asAlice) == posted
        json(asOperator) == posted
        asBob.statusCode() == 404
        asBob.body() == unknown.body()
    }

    def "the reference is stored and returned, but never logged"() {
        given:
        def source = fundedAccount(alice, 10_00)
        def reference = "synthetic-reference-" + UUID.randomUUID()
        def logs = new LogCapture("io.paymentslab")

        when:
        def response = transfer(alice, newKey(), body(source, bobAccount, 1_00, reference))

        then:
        json(response).reference == reference
        !logs.text().contains(reference)

        cleanup:
        logs.close()
    }

    private HttpResponse<String> transfer(TestCaller caller, String key, String body) {
        api.post("/v1/transfers", caller.bearer, key, body)
    }

    private static String body(UUID source, UUID destination, long amount, String reference = null) {
        def fields = [sourceAccountId: source.toString(), destinationAccountId: destination.toString(), amountMinor: amount, currency: "EUR"]
        if (reference != null) {
            fields.reference = reference
        }
        groovy.json.JsonOutput.toJson(fields)
    }

    private UUID fundedAccount(TestCaller owner, long amount) {
        def account = LedgerFixtures.accountFor(db.dsl, owner.id)
        fund(account, amount)
        account
    }

    private void fund(UUID account, long amount) {
        db.inTransaction { tx -> new FundingService(new LedgerService()).fund(tx, account, TransferAmount.of(amount, EUR)) }
    }

    private long balance(UUID account) {
        LedgerFixtures.balance(db.dsl, account)
    }

    private Map counts() {
        [payments: count("payments"), entries: count("journal_entries"), keys: count("idempotency_keys"), outbox: count("outbox_events")]
    }

    private long count(String table) {
        db.dsl.fetchValue("SELECT count(*) FROM " + table) as long
    }

    private List<String> outboxEventTypes(String transferId) {
        db.dsl.fetch("SELECT event_type FROM outbox_events WHERE aggregate_id = ?::uuid ORDER BY id", transferId).getValues(0, String)
    }

    private static String newKey() {
        UUID.randomUUID().toString()
    }
}
