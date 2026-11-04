package io.paymentslab.model

import io.paymentslab.app.App
import io.paymentslab.ledger.InvariantChecker
import io.paymentslab.support.ApiClient
import io.paymentslab.support.TestDatabase
import io.paymentslab.support.TestDatabases
import java.security.SecureRandom
import spock.lang.Requires
import spock.lang.Shared
import spock.lang.Specification

/**
 * Model-based runs (DESIGN §11, the last Phase 2 exit criterion). Each seed drives 200 random
 * operations through the real API: fundings, valid and invalid transfers, exact retries, reused keys
 * (an operator's key also moves between fundings and reversals), reversals, account status changes and
 * reads. Every answer must match {@link LedgerModel}; afterwards, balances, statuses and row counts must
 * match it too, and every ledger invariant must hold.
 *
 * <p>The default 50 seeds (1 to 50) make the suite repeatable. Replay one seed exactly with
 * {@code -PmodelSeeds=17}, or explore new ones with {@code -PmodelRandomSeeds=200}: the seeds appear in
 * the test names.
 */
class LedgerModelSpec extends Specification {

    static final int OPERATIONS = 200
    static final int DEFAULT_SEEDS = 50

    /** Kinds of answer the default seeds must produce at least once, or the runs prove less than they claim. */
    static final List<String> MUST_OCCUR = [
            "funding.create → 201",
            "funding.create → 422 ACCOUNT_NOT_ACTIVE",
            "transfer.create → 201 POSTED",
            "transfer.create → 201 DECLINED INSUFFICIENT_FUNDS",
            "transfer.create → 201 DECLINED ACCOUNT_NOT_ACTIVE",
            "transfer.create → 422 INVALID_ACCOUNT_COMBINATION",
            "transfer.create → 404 NOT_FOUND",
            "transfer.create → 403 FORBIDDEN",
            "transfer.create → 400 VALIDATION_FAILED",
            "transfer.reverse → 201 REVERSED",
            "transfer.reverse → 409 INVALID_STATE_TRANSITION",
            "transfer.reverse → 422 INSUFFICIENT_FUNDS",
            "transfer.reverse → 422 ACCOUNT_NOT_ACTIVE",
            "transfer.reverse → 404 NOT_FOUND",
            "replay of the stored 201",
            "422 IDEMPOTENCY_KEY_REUSED",
            "same key, other operation → 201",
            "boundary amount → 201 POSTED",
            "boundary amount → 201 DECLINED INSUFFICIENT_FUNDS",
            "status change → FROZEN",
            "status change → CLOSED",
    ]

    @Shared TestDatabase db = TestDatabases.create()
    @Shared App app
    @Shared ApiClient api
    @Shared Map<String, Integer> outcomes = [:].withDefault { 0 }

    def setupSpec() {
        app = App.start(db.appConfig())
        api = new ApiClient(app.port())
    }

    def cleanupSpec() {
        println "model outcomes over ${seeds().size()} seeds:\n" + outcomes.sort().collect { "  ${it.value.toString().padLeft(5)}  ${it.key}" }.join("\n")
        app?.close()
        db?.close()
    }

    def "seed #seed: 200 random operations agree with the model, and every invariant holds afterwards"() {
        given:
        def run = new ModelRun(seed, db, api)

        when:
        run.play(OPERATIONS)

        then:
        run.differencesFromDatabase() == []
        new InvariantChecker().check(db.dsl) == []

        cleanup:
        run?.outcomes?.each { kind, count -> outcomes[kind] += count }

        where:
        seed << seeds()
    }

    // Features run in declaration order, so this sees the tallies of every seed above.
    @Requires({ !System.getProperty("model.seeds") && !System.getProperty("model.randomSeeds") })
    def "the default seeds produce every kind of answer at least once"() {
        expect:
        MUST_OCCUR.findAll { kind -> !outcomes.keySet().any { it.contains(kind) } } == []
    }

    static List<Long> seeds() {
        String explicit = System.getProperty("model.seeds")
        if (explicit) {
            return explicit.split(",").collect { it.trim() as long }
        }
        String random = System.getProperty("model.randomSeeds")
        if (random) {
            def generator = new SecureRandom()
            return (1..(random as int)).collect { generator.nextLong() }
        }
        (1L..DEFAULT_SEEDS).toList()
    }
}
