package io.paymentslab.platform

import io.paymentslab.support.Concurrency
import io.paymentslab.support.LedgerFixtures
import io.paymentslab.support.TestDatabase
import io.paymentslab.support.TestDatabases
import java.sql.SQLException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.LongConsumer
import org.jooq.exception.DataAccessException
import spock.lang.Shared
import spock.lang.Specification

/** Transactions with bounded retries on transient conflicts (DESIGN §6, internal retries), on real PostgreSQL. */
class TxRunnerSpec extends Specification {

    @Shared TestDatabase db = TestDatabases.create()
    List<Long> pauses = Collections.synchronizedList([])
    TxRunner runner = new TxRunner(db.dsl, { long millis -> pauses << millis } as LongConsumer)

    def setupSpec() {
        db.dsl.execute("CREATE TABLE attempts (attempt int NOT NULL)")
    }

    def cleanupSpec() {
        db?.close()
    }

    def setup() {
        db.dsl.execute("DELETE FROM attempts")
    }

    def "every transaction runs with a 2 s lock_timeout"() {
        expect:
        runner.inTransaction { tx -> tx.fetchValue("SHOW lock_timeout") } == "2s"
    }

    def "a #sqlState failure is retried, and the failed attempt's writes are rolled back"() {
        given:
        int attempts = 0

        when:
        def result = runner.inTransaction { tx ->
            attempts++
            tx.execute("INSERT INTO attempts VALUES (?)", attempts)
            if (attempts == 1) {
                throw transientFailure(sqlState)
            }
            "done"
        }

        then:
        result == "done"
        attempts == 2
        committedAttempts() == [2]
        pauses.size() == 1
        pauses[0] in 5L..15L // 10 ms ± 50 %

        where:
        sqlState << ["40P01", "40001", "55P03"]
    }

    def "after three failed attempts it stops with ContentionException and nothing is committed"() {
        given:
        int attempts = 0

        when:
        runner.inTransaction { tx ->
            attempts++
            tx.execute("INSERT INTO attempts VALUES (?)", attempts)
            throw transientFailure("40P01")
        }

        then:
        thrown(ContentionException)
        attempts == 3
        committedAttempts() == []
        pauses.size() == 2
        pauses[0] in 5L..15L
        pauses[1] in 10L..30L // 20 ms ± 50 %
    }

    def "#what is not retried"() {
        given:
        int attempts = 0

        when:
        runner.inTransaction { tx ->
            attempts++
            throw failure
        }

        then:
        def e = thrown(Exception)
        e.is(failure)
        attempts == 1
        pauses == []

        where:
        what                         | failure
        "a unique violation (23505)" | new DataAccessException("simulated", new SQLException("simulated", "23505"))
        "an application exception"   | new IllegalStateException("simulated")
    }

    def "a real deadlock is broken by PostgreSQL, the victim is retried, and both transactions finish"() {
        given:
        def a = LedgerFixtures.customerAccount(db.dsl)
        def b = LedgerFixtures.customerAccount(db.dsl)
        def bothHoldTheirFirstLock = new CyclicBarrier(2)
        def attempts = new AtomicInteger()

        when: "each worker locks one row, waits for the other, then asks for the other's row"
        def outcome = Concurrency.run(2) { int worker ->
            def (first, second) = worker == 0 ? [a, b] : [b, a]
            boolean firstAttempt = true
            runner.inTransaction { tx ->
                attempts.incrementAndGet()
                lock(tx, first)
                if (firstAttempt) {
                    firstAttempt = false
                    bothHoldTheirFirstLock.await(5, TimeUnit.SECONDS)
                }
                lock(tx, second)
                "worker $worker done".toString()
            }
        }

        then:
        outcome.failures == []
        outcome.results as Set == ["worker 0 done", "worker 1 done"] as Set
        attempts.get() == 3
        pauses.size() == 1
    }

    private static DataAccessException transientFailure(String sqlState) {
        new DataAccessException("simulated", new SQLException("simulated transient failure", sqlState))
    }

    private static void lock(tx, UUID account) {
        tx.fetch("SELECT id FROM accounts WHERE id = ?::uuid FOR UPDATE", account)
    }

    private List<Integer> committedAttempts() {
        db.dsl.fetch("SELECT attempt FROM attempts ORDER BY attempt").getValues(0, Integer)
    }
}
