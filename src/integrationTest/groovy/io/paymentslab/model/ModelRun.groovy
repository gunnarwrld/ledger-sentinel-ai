package io.paymentslab.model

import static io.paymentslab.support.ApiClient.json
import static io.paymentslab.support.ApiClient.replayed

import groovy.json.JsonOutput
import io.paymentslab.model.LedgerModel.Call
import io.paymentslab.model.LedgerModel.Expected
import io.paymentslab.support.ApiClient
import io.paymentslab.support.CallerFixtures
import io.paymentslab.support.CallerFixtures.TestCaller
import io.paymentslab.support.LedgerFixtures
import io.paymentslab.support.TestDatabase
import java.net.http.HttpResponse

/**
 * One seeded sequence: a world of one operator and three customers with two accounts each, then random
 * operations sent through the API one at a time. Every answer is checked against {@link LedgerModel}
 * at once, so a failure names the first step where the system and the model disagree. Every random
 * choice comes from the seed and the model's state, never from ids the server generated, so a seed
 * replays exactly.
 */
final class ModelRun {

    private final long seed
    private final Random random
    private final TestDatabase db
    private final ApiClient api
    private final LedgerModel model = new LedgerModel()
    private final TestCaller operator
    private final List<TestCaller> customers
    private final List<UUID> accounts = []
    private final Map<UUID, String> names = [:]
    private final List<Call> history = []
    private final List<String> trace = []
    private final Map<String, Long> countsBefore
    private final long systemBefore
    /** How often each kind of answer came up, such as "transfer.create → 201 DECLINED INSUFFICIENT_FUNDS". */
    final Map<String, Integer> outcomes = [:].withDefault { 0 }

    ModelRun(long seed, TestDatabase db, ApiClient api) {
        this.seed = seed
        this.random = new Random(seed)
        this.db = db
        this.api = api
        this.operator = CallerFixtures.operator(db.dsl)
        this.customers = (0..2).collect { CallerFixtures.customer(db.dsl) }
        this.countsBefore = counts()
        this.systemBefore = LedgerFixtures.balance(db.dsl, LedgerModel.SYSTEM_FUNDING)
        customers.eachWithIndex { customer, c -> (0..1).each { a -> openAccount(customer, "c${c}a${a}") } }
    }

    void play(int operations) {
        for (int step = 1; step <= operations; step++) {
            int roll = random.nextInt(100)
            if (roll < 15) {
                post(step, funding())
            } else if (roll < 50) {
                post(step, transfer())
            } else if (roll < 58) {
                post(step, invalidTransfer())
            } else if (roll < 72) {
                post(step, history ? pick(history) : transfer())
            } else if (roll < 78) {
                post(step, history ? reuseKey() : transfer())
            } else if (roll < 88) {
                post(step, reversal())
            } else if (roll < 93) {
                changeStatus(step)
            } else {
                read(step)
            }
        }
    }

    /** Every way the database differs from the model after the run; empty when they agree. */
    List<String> differencesFromDatabase() {
        List<String> differences = []
        accounts.each { account ->
            def row = db.dsl.fetchOne("SELECT balance_minor, status FROM accounts WHERE id = ?::uuid", account)
            if ((row.get(0) as long) != model.balances[account] || row.get(1) != model.statuses[account]) {
                differences << "account ${names[account]}: database ${row.get(0)} ${row.get(1)}, model ${model.balances[account]} ${model.statuses[account]}"
            }
        }
        model.payments.each { id, payment ->
            def status = db.dsl.fetchValue("SELECT status FROM payments WHERE id = ?::uuid", id)
            if (status != payment.status) {
                differences << "payment $id: database $status, model ${payment.status}"
            }
        }
        def now = counts()
        [journal_entries: model.entries, outbox_events: model.events, payments: model.payments.size(),
         idempotency_keys: model.stored.size()].each { table, expected ->
            if (now[table] - countsBefore[table] != expected) {
                differences << "$table: ${now[table] - countsBefore[table]} new rows, model $expected"
            }
        }
        def system = LedgerFixtures.balance(db.dsl, LedgerModel.SYSTEM_FUNDING)
        if (system != systemBefore - model.funded) {
            differences << "SYSTEM funding account moved ${system - systemBefore}, model ${-model.funded}"
        }
        differences.collect { "seed $seed: $it".toString() }
    }

    private void openAccount(TestCaller customer, String name) {
        def key = newKey()
        def response = api.post("/v1/accounts", customer.bearer, key, '{"currency":"EUR"}')
        if (response.statusCode() != 201) {
            throw new AssertionError("seed $seed: opening an account returned ${response.statusCode()} ${response.body()}")
        }
        UUID account = UUID.fromString(json(response).id as String)
        accounts << account
        names[account] = name
        model.openAccount(customer.id, account)
        model.store(customer, "account.create", key, ["EUR"], response.body())
    }

    private void post(int step, Call call) {
        Expected expected = model.expect(call)
        HttpResponse<String> response = api.post(call.path, call.caller.bearer, call.key, call.body)
        Map body = parse(response)
        trace << "$step  ${describe(call)} → ${response.statusCode()} ${body?.status ?: body?.code ?: ''}${replayed(response) ? ' (replayed)' : ''}".toString()
        boolean agrees
        if (expected.replay != null) {
            agrees = response.statusCode() == expected.replay.status && replayed(response) && response.body() == expected.replay.body
        } else if (expected.status == 201) {
            agrees = response.statusCode() == 201 && !replayed(response) &&
                    body?.status == expected.transferStatus && body?.declineReason == expected.declineReason
        } else {
            agrees = response.statusCode() == expected.status && !replayed(response) && body?.code == expected.code
        }
        require(agrees, step, describe(call), expected.toString(), response)
        outcomes["${call.operation} → $expected".toString()]++
        if (call.tag != null) {
            outcomes["${call.tag} amount → $expected".toString()]++
        }
        if (history.any { it.caller.is(call.caller) && it.key == call.key && it.operation != call.operation }) {
            outcomes["same key, other operation → $expected".toString()]++
        }
        model.commit(call, expected, response.body(), body?.id as String)
        history << call
    }

    private Call funding() {
        int variant = random.nextInt(20)
        TestCaller caller = variant == 0 ? pick(customers) : operator
        UUID account = variant == 1 ? LedgerModel.SYSTEM_FUNDING : variant == 2 ? randomUuid() : pick(accounts)
        long amount = variant == 3 ? invalidAmount() : 1 + random.nextInt(5_000)
        fundingCall(caller, newKey(), account, amount)
    }

    private Call transfer() {
        TestCaller customer = pick(customers)
        UUID source = pick(ownedBy(customer))
        UUID destination = pick(accounts - source)
        long balance = model.balances[source]
        int roll = random.nextInt(12)
        long amount = 1 + random.nextInt(3_000)
        String tag = null
        if (roll == 0) {
            amount = invalidAmount()
        } else if (roll <= 2 && balance > 0) {
            // Uniform amounts almost never land on the edge (mutation 3 slipped past 49 of 50 seeds):
            // exactly the balance must post, one cent more must be declined.
            amount = roll == 1 ? balance : balance + 1
            tag = "boundary"
        }
        String reference = random.nextInt(6) == 0 ? "ref-" + random.nextInt(10_000) : null
        transferCall(customer, newKey(), source, destination, amount, reference, tag)
    }

    private Call invalidTransfer() {
        TestCaller customer = pick(customers)
        UUID own = pick(ownedBy(customer))
        long amount = 1 + random.nextInt(100)
        switch (random.nextInt(4)) {
            case 0: return transferCall(customer, newKey(), own, own, amount, null)                            // 422
            case 1: return transferCall(customer, newKey(), pick(accounts - ownedBy(customer)), own, amount, null) // 404
            case 2: return transferCall(customer, newKey(), own, randomUuid(), amount, null)                   // 404
            default: return transferCall(operator, newKey(), own, pick(accounts - own), amount, null)          // 403
        }
    }

    private Call reversal() {
        int variant = random.nextInt(20)
        String transferId = model.payments.isEmpty() || variant == 0 ? randomUuid().toString() : pick(model.payments.keySet().toList())
        TestCaller caller = variant == 1 ? pick(customers) : operator
        reversalCall(caller, newKey(), transferId)
    }

    /** An earlier caller's key with a different request; an operator's key may move to their other operation. */
    private Call reuseKey() {
        Call earlier = pick(history)
        boolean otherOperation = earlier.caller.role == "OPERATOR" && random.nextBoolean()
        String operation = otherOperation ? (earlier.operation == "transfer.reverse" ? "funding.create" : "transfer.reverse") : earlier.operation
        if (operation == "funding.create") {
            UUID account = otherOperation ? pick(accounts) : earlier.payload[0] as UUID
            long amount = otherOperation ? 1 + random.nextInt(5_000) : (earlier.payload[1] as long) + 1
            return fundingCall(earlier.caller, earlier.key, account, amount)
        }
        if (operation == "transfer.create") {
            return transferCall(earlier.caller, earlier.key, earlier.payload[0] as UUID, earlier.payload[1] as UUID,
                    (earlier.payload[2] as long) + 1, earlier.payload[4] as String)
        }
        String transferId = model.payments.isEmpty() ? randomUuid().toString() : pick(model.payments.keySet().toList())
        reversalCall(earlier.caller, earlier.key, transferId)
    }

    private void changeStatus(int step) {
        UUID account = pick(accounts)
        int roll = random.nextInt(20)
        String status = roll < 10 ? "ACTIVE" : roll < 17 ? "FROZEN" : "CLOSED"
        db.dsl.execute("UPDATE accounts SET status = ? WHERE id = ?::uuid", status, account)
        model.statuses[account] = status
        outcomes["status change → $status".toString()]++
        trace << "$step  status of ${names[account]} → $status".toString()
    }

    private void read(int step) {
        if (!model.payments.isEmpty() && random.nextBoolean()) {
            String id = pick(model.payments.keySet().toList())
            def payment = model.payments[id]
            def response = api.get("/v1/transfers/$id", customers.find { it.id == payment.creator }.bearer)
            outcomes["read transfer"]++
            trace << "$step  read transfer ${id.take(8)} → ${response.statusCode()} ${parse(response)?.status}".toString()
            require(response.statusCode() == 200 && parse(response)?.status == payment.status, step,
                    "read transfer ${id.take(8)}", "200 ${payment.status}", response)
        } else {
            UUID account = pick(accounts)
            def response = api.get("/v1/accounts/$account", customers.find { it.id == model.owners[account] }.bearer)
            def body = parse(response)
            outcomes["read account"]++
            trace << "$step  read ${names[account]} → ${response.statusCode()} ${body?.balanceMinor} ${body?.status}".toString()
            require(response.statusCode() == 200 && body?.balanceMinor == model.balances[account] && body?.status == model.statuses[account],
                    step, "read ${names[account]}", "200 ${model.balances[account]} ${model.statuses[account]}", response)
        }
    }

    private Call fundingCall(TestCaller caller, String key, UUID account, long amount) {
        new Call(caller, "funding.create", key, [account, amount, "EUR"], "/v1/admin/fundings",
                JsonOutput.toJson([accountId: account.toString(), amountMinor: amount, currency: "EUR"]))
    }

    private Call transferCall(TestCaller caller, String key, UUID source, UUID destination, long amount, String reference,
                              String tag = null) {
        Map fields = [sourceAccountId: source.toString(), destinationAccountId: destination.toString(), amountMinor: amount, currency: "EUR"]
        if (reference != null) {
            fields.reference = reference
        }
        new Call(caller, "transfer.create", key, [source, destination, amount, "EUR", reference], "/v1/transfers",
                JsonOutput.toJson(fields), tag)
    }

    private Call reversalCall(TestCaller caller, String key, String transferId) {
        new Call(caller, "transfer.reverse", key, [transferId], "/v1/transfers/$transferId/reversal".toString(), "")
    }

    private String describe(Call call) {
        String who = call.caller.is(operator) ? "operator" : "customer c${customers.indexOf(call.caller)}"
        String what = call.payload.collect { it instanceof UUID ? (names[it] ?: (it == LedgerModel.SYSTEM_FUNDING ? "SYSTEM" : "unknown")) : it }.toString()
        "${call.operation} by $who $what key …${call.key.takeRight(4)}"
    }

    private void require(boolean agrees, int step, String what, String expected, HttpResponse<String> response) {
        if (agrees) {
            return
        }
        throw new AssertionError("""
            |seed $seed, step $step: $what
            |  expected: $expected
            |  actual:   ${response.statusCode()}${replayed(response) ? ' (replayed)' : ''} ${response.body()}
            |  last steps:
            |    ${trace.takeRight(12).join('\n    ')}
            |  replay: ./gradlew integrationTest --tests '*LedgerModelSpec' -PmodelSeeds=$seed""".stripMargin())
    }

    private List<UUID> ownedBy(TestCaller customer) {
        accounts.findAll { model.owners[it] == customer.id }
    }

    private <T> T pick(List<T> items) {
        items[random.nextInt(items.size())]
    }

    private long invalidAmount() {
        [0L, -1L, LedgerModel.MAX_AMOUNT + 1][random.nextInt(3)]
    }

    private UUID randomUuid() {
        new UUID(random.nextLong(), random.nextLong())
    }

    private String newKey() {
        randomUuid().toString()
    }

    private static Map parse(HttpResponse<String> response) {
        try {
            json(response) as Map
        } catch (Exception notJson) {
            null
        }
    }

    private Map<String, Long> counts() {
        ["journal_entries", "outbox_events", "payments", "idempotency_keys"].collectEntries { table ->
            [table, db.dsl.fetchValue("SELECT count(*) FROM " + table) as long]
        }
    }
}
