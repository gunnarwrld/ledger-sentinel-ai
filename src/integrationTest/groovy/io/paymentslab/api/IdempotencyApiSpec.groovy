package io.paymentslab.api

import static io.paymentslab.support.ApiClient.json
import static io.paymentslab.support.ApiClient.replayed

import io.paymentslab.app.App
import io.paymentslab.ledger.InvariantChecker
import io.paymentslab.payments.RequestFingerprint
import io.paymentslab.support.ApiClient
import io.paymentslab.support.CallerFixtures
import io.paymentslab.support.CallerFixtures.TestCaller
import io.paymentslab.support.Concurrency
import io.paymentslab.support.ContractSchemas
import io.paymentslab.support.LedgerFixtures
import io.paymentslab.support.TestDatabase
import io.paymentslab.support.TestDatabases
import java.net.http.HttpResponse
import java.sql.Connection
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import spock.lang.Shared
import spock.lang.Specification

/** The idempotency protocol through the API (ADR 0007, DESIGN §6, invariant I11). Fundings make money movement visible. */
class IdempotencyApiSpec extends Specification {

    @Shared TestDatabase db = TestDatabases.create()
    @Shared App app
    @Shared ApiClient api
    @Shared TestCaller operator
    @Shared TestCaller otherOperator
    @Shared TestCaller alice
    @Shared TestCaller bob
    InvariantChecker invariants = new InvariantChecker()
    def executor = Executors.newSingleThreadExecutor()
    List<Connection> holders = []

    def setupSpec() {
        // Concurrent duplicates each hold a connection while they wait, as ADR 0007 notes.
        app = App.start(db.appConfig(10))
        api = new ApiClient(app.port())
        operator = CallerFixtures.operator(db.dsl)
        otherOperator = CallerFixtures.operator(db.dsl)
        alice = CallerFixtures.customer(db.dsl)
        bob = CallerFixtures.customer(db.dsl)
    }

    def cleanupSpec() {
        app?.close()
        db?.close()
    }

    def cleanup() {
        executor.shutdownNow()
        holders.each { if (!it.closed) { it.rollback(); it.close() } }
        assert invariants.check(db.dsl) == []
    }

    def "a repeat with the same key and payload gets the stored response, and money moves once"() {
        given:
        def account = newAccount()
        def key = newKey()

        when:
        def first = fund(operator, key, funding(account, 10_00))
        def repeat = fund(operator, key, funding(account, 10_00))

        then:
        first.statusCode() == 201
        !replayed(first)
        repeat.statusCode() == 201
        replayed(repeat)
        repeat.body() == first.body()
        balance(account) == 10_00
    }

    def "the same request written differently is still a repeat (#variation)"() {
        given:
        def account = newAccount()
        def key = newKey()
        def first = fund(operator, key, funding(account, 5_00))

        when:
        def repeat = fund(operator, key, rewrite(account))

        then:
        first.statusCode() == 201
        replayed(repeat)
        repeat.body() == first.body()
        balance(account) == 5_00

        where:
        variation               | rewrite
        "other field order"     | { id -> """{"currency":"EUR","amountMinor":500,"accountId":"$id"}""".toString() }
        "extra whitespace"      | { id -> """{\n  "accountId" : "$id",\n  "amountMinor" : 500,\n  "currency" : "EUR"\n}""".toString() }
        "an upper-case UUID"    | { id -> """{"accountId":"${id.toString().toUpperCase()}","amountMinor":500,"currency":"EUR"}""".toString() }
    }

    def "the same key with a different payload is refused (422), and the original stays replayable"() {
        given:
        def account = newAccount()
        def key = newKey()
        def first = fund(operator, key, funding(account, 5_00))

        when:
        def reused = fund(operator, key, funding(account, 6_00))

        then:
        reused.statusCode() == 422
        json(reused).code == "IDEMPOTENCY_KEY_REUSED"
        ContractSchemas.violations(json(reused), "Problem") == []
        balance(account) == 5_00

        and:
        def original = fund(operator, key, funding(account, 5_00))
        replayed(original)
        original.body() == first.body()
    }

    def "a key belongs to one caller: the same key from someone else is a new request"() {
        given:
        def account = newAccount()
        def key = newKey()

        when:
        def mine = fund(operator, key, funding(account, 1_00))
        def theirs = fund(otherOperator, key, funding(account, 1_00))
        def aliceOpens = api.post("/v1/accounts", alice.bearer, key, '{"currency":"EUR"}')
        def bobOpens = api.post("/v1/accounts", bob.bearer, key, '{"currency":"EUR"}')

        then:
        [mine, theirs, aliceOpens, bobOpens]*.statusCode() == [201, 201, 201, 201]
        [mine, theirs, aliceOpens, bobOpens].count { replayed(it) } == 0
        balance(account) == 2_00
        json(aliceOpens).id != json(bobOpens).id
    }

    def "a key belongs to one operation: an operator's funding key used again for a reversal is a new request"() {
        given: "the operator funds alice with key K, and alice sends 2.00 to bob"
        def source = newAccount()
        def key = newKey()
        def funded = fund(operator, key, funding(source, 10_00))
        def destination = LedgerFixtures.accountFor(db.dsl, bob.id)
        def sent = api.post("/v1/transfers", alice.bearer, newKey(),
                """{"sourceAccountId":"$source","destinationAccountId":"$destination","amountMinor":200,"currency":"EUR"}""")

        when: "the operator reverses that transfer with the same key K"
        def reversal = api.post("/v1/transfers/${json(sent).id}/reversal", operator.bearer, key, "")

        then: "neither a replay of the funding nor a reused-key conflict: it runs"
        funded.statusCode() == 201
        reversal.statusCode() == 201
        !replayed(reversal)
        json(reversal).status == "REVERSED"
        balance(source) == 10_00
        balance(destination) == 0
    }

    def "a request refused by validation isn't stored: the same key with a fixed body runs"() {
        given:
        def account = newAccount()
        def key = newKey()

        when:
        def refused = fund(operator, key, funding(account, 0))
        def fixed = fund(operator, key, funding(account, 7_00))

        then:
        refused.statusCode() == 400
        fixed.statusCode() == 201
        !replayed(fixed)
        balance(account) == 7_00
    }

    def "a refused funding isn't stored either: once the account is active again, the same key funds it"() {
        given:
        def account = newAccount()
        def key = newKey()
        db.dsl.execute("UPDATE accounts SET status = 'FROZEN' WHERE id = ?::uuid", account)
        def refused = fund(operator, key, funding(account, 3_00))
        db.dsl.execute("UPDATE accounts SET status = 'ACTIVE' WHERE id = ?::uuid", account)

        when:
        def retried = fund(operator, key, funding(account, 3_00))

        then:
        json(refused).code == "ACCOUNT_NOT_ACTIVE"
        retried.statusCode() == 201
        !replayed(retried)
        balance(account) == 3_00
    }

    def "an Idempotency-Key that is #why gets #code, and nothing moves"() {
        given:
        def account = newAccount()

        when:
        def response = api.post("/v1/admin/fundings", operator.bearer, key, funding(account, 1_00))

        then:
        response.statusCode() == 400
        json(response).code == code
        ContractSchemas.violations(json(response), "Problem") == []
        code != "VALIDATION_FAILED" || json(response).errors*.field == ["Idempotency-Key"]
        balance(account) == 0

        where:
        why                     | key            | code
        "missing"               | null           | "IDEMPOTENCY_KEY_MISSING"
        "7 characters long"     | "1234567"      | "VALIDATION_FAILED"
        "256 characters long"   | "k" * 256      | "VALIDATION_FAILED"
        "split by a space"      | "has a space"  | "VALIDATION_FAILED"
    }

    def "20 concurrent duplicates fund once, and all get the same response"() {
        given:
        def account = newAccount()
        def key = newKey()

        when:
        def outcome = Concurrency.run(20) { fund(operator, key, funding(account, 9_00)) }
        List<HttpResponse<String>> responses = outcome.results

        then:
        outcome.failures == []
        responses*.statusCode().unique() == [201]
        responses*.body().unique().size() == 1
        responses.count { !replayed(it) } == 1
        balance(account) == 9_00
    }

    def "a duplicate arriving mid-flight waits, then replays what the first transaction committed"() {
        given: "a transaction holding the key, as a request in flight would"
        def account = newAccount()
        def key = newKey()
        def holder = holdKey(operator, key, account, 4_00)

        when:
        Future<HttpResponse<String>> duplicate = inBackground { fund(operator, key, funding(account, 4_00)) }
        assert serverWaitsOnLock()
        storeResponse(holder, operator, key, '{"from":"the first request"}')
        holder.commit()

        then:
        def response = duplicate.get(10, TimeUnit.SECONDS)
        response.statusCode() == 201
        replayed(response)
        response.body() == '{"from":"the first request"}'
        balance(account) == 0
    }

    def "a duplicate arriving mid-flight runs itself if the first transaction rolls back"() {
        given:
        def account = newAccount()
        def key = newKey()
        def holder = holdKey(operator, key, account, 4_00)

        when:
        Future<HttpResponse<String>> duplicate = inBackground { fund(operator, key, funding(account, 4_00)) }
        assert serverWaitsOnLock()
        holder.rollback()

        then:
        def response = duplicate.get(10, TimeUnit.SECONDS)
        response.statusCode() == 201
        !replayed(response)
        balance(account) == 4_00
    }

    def "a duplicate that waits past lock_timeout gets 409 IDEMPOTENCY_REQUEST_IN_PROGRESS with Retry-After"() {
        given:
        def account = newAccount()
        def key = newKey()
        def holder = holdKey(operator, key, account, 4_00)
        long started = System.nanoTime()

        when:
        def response = fund(operator, key, funding(account, 4_00))
        def waited = Duration.ofNanos(System.nanoTime() - started)
        holder.rollback()

        then:
        response.statusCode() == 409
        json(response).code == "IDEMPOTENCY_REQUEST_IN_PROGRESS"
        response.headers().firstValue("Retry-After").orElse(null) == "1"
        ContractSchemas.violations(json(response), "Problem") == []
        waited >= Duration.ofMillis(1_500)
        waited < Duration.ofSeconds(5)
        balance(account) == 0
    }

    private HttpResponse<String> fund(TestCaller caller, String key, String body) {
        api.post("/v1/admin/fundings", caller.bearer, key, body)
    }

    private static String funding(UUID account, long amount) {
        """{"accountId":"$account","amountMinor":$amount,"currency":"EUR"}"""
    }

    private UUID newAccount() {
        LedgerFixtures.accountFor(db.dsl, alice.id)
    }

    private long balance(UUID account) {
        LedgerFixtures.balance(db.dsl, account)
    }

    private <T> Future<T> inBackground(Closure<T> work) {
        executor.submit(work as Callable<T>)
    }

    /**
     * Inserts the key row in an open transaction, with the fingerprint the server computes for the same
     * funding (production code: the fingerprint isn't what these features test).
     */
    private Connection holdKey(TestCaller caller, String key, UUID account, long amount) {
        def connection = db.dataSource.connection
        connection.autoCommit = false
        holders << connection
        connection.prepareStatement("""
                INSERT INTO idempotency_keys (caller_id, operation, idempotency_key, request_hash)
                VALUES (?::uuid, 'funding.create', ?, ?)""").withCloseable { statement ->
            statement.setString(1, caller.id.toString())
            statement.setString(2, key)
            statement.setBytes(3, RequestFingerprint.of("funding.create").add(account).add(amount).add("EUR").hash())
            assert statement.executeUpdate() == 1
        }
        connection
    }

    private static void storeResponse(Connection holder, TestCaller caller, String key, String body) {
        holder.prepareStatement("""
                UPDATE idempotency_keys SET response_status = 201, response_body = ?
                WHERE caller_id = ?::uuid AND operation = 'funding.create' AND idempotency_key = ?""").withCloseable { statement ->
            statement.setString(1, body)
            statement.setString(2, caller.id.toString())
            statement.setString(3, key)
            assert statement.executeUpdate() == 1
        }
    }

    /** True once some session in this database is blocked on a lock (polls for up to 5 s). */
    private boolean serverWaitsOnLock() {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (System.nanoTime() < deadline) {
            if ((db.dsl.fetchValue("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND wait_event_type = 'Lock'""") as long) > 0) {
                return true
            }
            Thread.sleep(20)
        }
        false
    }

    private static String newKey() {
        UUID.randomUUID().toString()
    }
}
