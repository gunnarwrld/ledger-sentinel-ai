package io.paymentslab.api

import static io.paymentslab.support.ApiClient.contentType
import static io.paymentslab.support.ApiClient.json

import io.paymentslab.app.App
import io.paymentslab.ledger.EntryKind
import io.paymentslab.ledger.FundingService
import io.paymentslab.ledger.InvariantChecker
import io.paymentslab.ledger.JournalEntryRequest
import io.paymentslab.ledger.LedgerService
import io.paymentslab.ledger.Posting
import io.paymentslab.ledger.SystemAccounts
import io.paymentslab.money.Money
import io.paymentslab.money.TransferAmount
import io.paymentslab.support.ApiClient
import io.paymentslab.support.CallerFixtures
import io.paymentslab.support.CallerFixtures.TestCaller
import io.paymentslab.support.ContractSchemas
import io.paymentslab.support.LedgerFixtures
import io.paymentslab.support.TestDatabase
import io.paymentslab.support.TestDatabases
import spock.lang.Shared
import spock.lang.Specification

/** Account reads (threat T8, BOLA) through the whole app. Ledger data is written with LedgerService. */
class AccountApiSpec extends Specification {

    static final Currency EUR = Currency.getInstance("EUR")

    @Shared TestDatabase db = TestDatabases.create()
    @Shared App app
    @Shared ApiClient api
    @Shared TestCaller alice
    @Shared TestCaller bob
    @Shared TestCaller operator
    @Shared UUID aliceAccount

    LedgerService ledger = new LedgerService()
    FundingService funding = new FundingService(ledger)
    InvariantChecker invariants = new InvariantChecker()

    def setupSpec() {
        app = App.start(db.appConfig())
        api = new ApiClient(app.port())
        alice = CallerFixtures.customer(db.dsl)
        bob = CallerFixtures.customer(db.dsl)
        operator = CallerFixtures.operator(db.dsl)
        aliceAccount = LedgerFixtures.accountFor(db.dsl, alice.id)
        LedgerFixtures.accountFor(db.dsl, bob.id)
        db.inTransaction { tx -> new FundingService(new LedgerService()).fund(tx, aliceAccount, TransferAmount.of(123_45, EUR)) }
    }

    def cleanupSpec() {
        app?.close()
        db?.close()
    }

    def cleanup() {
        assert invariants.check(db.dsl) == []
    }

    def "the owner reads balance and status, and nothing the contract doesn't declare"() {
        when:
        def response = api.get("/v1/accounts/$aliceAccount", alice.bearer)
        def body = json(response)

        then:
        response.statusCode() == 200
        contentType(response).startsWith("application/json")
        body.id == aliceAccount.toString()
        body.currency == "EUR"
        body.status == "ACTIVE"
        body.balanceMinor == 123_45
        ContractSchemas.violations(body, "Account") == []
    }

    def "an unknown id gets a NOT_FOUND problem shaped like the contract's Problem"() {
        when:
        def response = api.get("/v1/accounts/${UUID.randomUUID()}", alice.bearer)

        then:
        response.statusCode() == 404
        contentType(response).startsWith("application/problem+json")
        json(response).code == "NOT_FOUND"
        ContractSchemas.violations(json(response), "Problem") == []
    }

    def "#why: the same 404 as an unknown id, on both account routes"() {
        expect:
        ["", "/entries"].each { route ->
            // Control: the route is live, so a 404 below can't mean "no such route".
            def control = api.get("/v1/accounts/$aliceAccount$route", alice.bearer)
            assert control.statusCode() == 200
            def response = api.get("/v1/accounts/$account$route", viewer.bearer)
            def unknown = api.get("/v1/accounts/${UUID.randomUUID()}$route", viewer.bearer)
            assert response.statusCode() == 404
            assert json(response).code == "NOT_FOUND"
            assert response.body() == unknown.body()
        }

        where:
        why                                          | viewer   | account
        "another customer's account"                 | bob      | aliceAccount
        "an operator (account reads are owner-only)" | operator | aliceAccount
        "the SYSTEM funding account (it has no owner)" | alice  | SystemAccounts.FUNDING
        "an id that isn't a UUID"                    | alice    | "not-a-uuid"
    }

    def "a statement lists the account's postings newest first, signed from its point of view"() {
        given:
        def carol = CallerFixtures.customer(db.dsl)
        def carolAccount = LedgerFixtures.accountFor(db.dsl, carol.id)
        def daveAccount = LedgerFixtures.accountFor(db.dsl, CallerFixtures.customer(db.dsl).id)
        def funded = fund(carolAccount, 100_00)
        def paid = transfer(carolAccount, daveAccount, 25_00)
        def repaid = transfer(daveAccount, carolAccount, 5_00)

        when:
        def response = api.get("/v1/accounts/$carolAccount/entries", carol.bearer)
        def body = json(response)

        then:
        response.statusCode() == 200
        contentType(response).startsWith("application/json")
        body.items*.journalEntryId == [repaid, paid, funded]*.toString()
        body.items*.kind == ["TRANSFER", "TRANSFER", "FUNDING"]
        body.items*.amountMinor == [5_00, -25_00, 100_00]
        body.items*.currency == ["EUR", "EUR", "EUR"]
        body.nextCursor == null
        ContractSchemas.violations(body, "EntryPage") == []

        and: "the balance agrees with the statement"
        json(api.get("/v1/accounts/$carolAccount", carol.bearer)).balanceMinor == 80_00
    }

    def "an account without postings has an empty statement"() {
        given:
        def owner = CallerFixtures.customer(db.dsl)
        def account = LedgerFixtures.accountFor(db.dsl, owner.id)

        expect:
        page(account, owner, "") == [items: [], nextCursor: null]
    }

    def "walking the pages returns every posting exactly once, then a null cursor"() {
        given: "seven fundings of 1 to 7 cents"
        def owner = CallerFixtures.customer(db.dsl)
        def account = LedgerFixtures.accountFor(db.dsl, owner.id)
        (1..7).each { fund(account, it) }

        when:
        def pages = walk(account, owner, 3)

        then:
        pages.collect { it.items.size() } == [3, 3, 1]
        pages.collectMany { it.items*.amountMinor } == [7, 6, 5, 4, 3, 2, 1]
        pages.last().nextCursor == null
    }

    def "postings that arrive mid-walk don't shift the pages still to come (keyset, not offset)"() {
        given: "six fundings of 1 to 6 cents"
        def owner = CallerFixtures.customer(db.dsl)
        def account = LedgerFixtures.accountFor(db.dsl, owner.id)
        (1..6).each { fund(account, it) }

        when: "page 1 is read, two postings arrive, then page 2 is read with page 1's cursor"
        def first = page(account, owner, "limit=3")
        fund(account, 7)
        fund(account, 8)
        def second = page(account, owner, "limit=3&cursor=$first.nextCursor")

        then: "page 2 continues exactly where page 1 stopped (an offset would repeat 4)"
        first.items*.amountMinor == [6, 5, 4]
        second.items*.amountMinor == [3, 2, 1]
        second.nextCursor == null

        and: "a walk that starts now sees the new postings first"
        page(account, owner, "limit=3").items*.amountMinor == [8, 7, 6]
    }

    def "without a limit, a page holds 50 entries"() {
        given:
        def owner = CallerFixtures.customer(db.dsl)
        def account = LedgerFixtures.accountFor(db.dsl, owner.id)
        db.inTransaction { tx -> (1..51).each { funding.fund(tx, account, TransferAmount.of(it, EUR)) } }

        when:
        def first = page(account, owner, "")
        def second = page(account, owner, "cursor=$first.nextCursor")

        then:
        first.items*.amountMinor == (51..2).toList()
        second.items*.amountMinor == [1]
        second.nextCursor == null
    }

    def "limit=#limit is accepted"() {
        when:
        def response = api.get("/v1/accounts/$aliceAccount/entries?limit=$limit", alice.bearer)

        then:
        response.statusCode() == 200

        where:
        limit << [1, 200]
    }

    def "?#query is rejected with VALIDATION_FAILED naming #fields"() {
        when:
        def response = api.get("/v1/accounts/$aliceAccount/entries?$query", alice.bearer)

        then:
        response.statusCode() == 400
        contentType(response).startsWith("application/problem+json")
        json(response).code == "VALIDATION_FAILED"
        json(response).errors*.field == fields
        ContractSchemas.violations(json(response), "Problem") == []

        where:
        query                         | fields
        "limit=0"                     | ["limit"]
        "limit=201"                   | ["limit"]
        "limit=-1"                    | ["limit"]
        "limit=abc"                   | ["limit"]
        "limit=1.5"                   | ["limit"]
        "limit="                      | ["limit"]
        "limit=99999999999"           | ["limit"]
        "cursor=not-a-cursor"         | ["cursor"]
        "cursor=${b64('v1:0')}"       | ["cursor"]
        "cursor=${b64('42')}"         | ["cursor"]
        "cursor="                     | ["cursor"]
        "cursor=${'A' * 201}"         | ["cursor"]
        "limit=0&cursor=not-a-cursor" | ["limit", "cursor"]
    }

    private UUID fund(UUID account, long minor) {
        db.inTransaction { tx -> funding.fund(tx, account, TransferAmount.of(minor, EUR)).entryId() }
    }

    private UUID transfer(UUID from, UUID to, long minor) {
        db.inTransaction { tx ->
            ledger.post(tx, new JournalEntryRequest(EntryKind.TRANSFER, EUR, null, [
                    new Posting(from, Money.of(-minor, EUR)),
                    new Posting(to, Money.of(minor, EUR))])).entryId()
        }
    }

    private Map page(UUID account, TestCaller owner, String query) {
        def response = api.get("/v1/accounts/$account/entries?$query", owner.bearer)
        assert response.statusCode() == 200
        json(response) as Map
    }

    /** Follows nextCursor from the newest page to the last one. */
    private List<Map> walk(UUID account, TestCaller owner, int limit) {
        List<Map> pages = [page(account, owner, "limit=$limit")]
        while (pages.last().nextCursor != null && pages.size() <= 100) {
            pages << page(account, owner, "limit=$limit&cursor=${pages.last().nextCursor}")
        }
        pages
    }

    private static String b64(String text) {
        Base64.urlEncoder.withoutPadding().encodeToString(text.bytes)
    }
}
