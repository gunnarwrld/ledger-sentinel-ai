package io.paymentslab.payments

import io.paymentslab.support.CallerFixtures
import io.paymentslab.support.TestDatabase
import io.paymentslab.support.TestDatabases
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import spock.lang.Shared
import spock.lang.Specification

/**
 * Risk R3 (ADR 0007): the single-transaction idempotency protocol leans on PostgreSQL making a
 * concurrent INSERT of the same key wait for the first transaction. Plain SQL on two connections,
 * no application code, so this proves the database behaviour itself.
 */
class IdempotencyProtocolSpec extends Specification {

    static final String INSERT_KEY = """
            INSERT INTO idempotency_keys (caller_id, operation, idempotency_key, request_hash)
            VALUES (?::uuid, ?, ?, ?)
            ON CONFLICT (caller_id, operation, idempotency_key) DO NOTHING"""

    @Shared TestDatabase db = TestDatabases.create()
    @Shared UUID alice
    @Shared UUID bob
    def executor = Executors.newSingleThreadExecutor()
    List<Connection> connections = []

    def setupSpec() {
        alice = CallerFixtures.customer(db.dsl).id
        bob = CallerFixtures.customer(db.dsl).id
    }

    def cleanupSpec() {
        db?.close()
    }

    def cleanup() {
        executor.shutdownNow()
        connections.each { it.rollback(); it.close() }
    }

    def "a second insert of the same key waits for the first transaction, then sees its committed response"() {
        given:
        def first = connection()
        def second = connection()
        def key = newKey()
        assert insertKey(first, alice, "funding.create", key) == 1
        storeResponse(first, alice, "funding.create", key, 201, '{"made":"by the first"}')

        when: "the duplicate arrives while the first transaction is still open"
        def secondPid = backendPid(second)
        Future<Integer> duplicate = executor.submit({ insertKey(second, alice, "funding.create", key) } as java.util.concurrent.Callable)

        then: "PostgreSQL makes it wait on the first transaction"
        waitsOnLock(secondPid)
        !duplicate.done

        when:
        first.commit()

        then: "it inserts nothing, and its next statement reads the first transaction's response"
        duplicate.get(5, TimeUnit.SECONDS) == 0
        storedResponse(second, alice, "funding.create", key) == [201, '{"made":"by the first"}']
    }

    def "if the first transaction rolls back, the waiting insert goes ahead and owns the key"() {
        given:
        def first = connection()
        def second = connection()
        def key = newKey()
        assert insertKey(first, alice, "funding.create", key) == 1
        def secondPid = backendPid(second)
        Future<Integer> duplicate = executor.submit({ insertKey(second, alice, "funding.create", key) } as java.util.concurrent.Callable)
        assert waitsOnLock(secondPid)

        when:
        first.rollback()

        then:
        duplicate.get(5, TimeUnit.SECONDS) == 1

        when:
        storeResponse(second, alice, "funding.create", key, 201, '{"made":"by the second"}')
        second.commit()

        then:
        committedResponse(alice, "funding.create", key) == [201, '{"made":"by the second"}']
    }

    def "with lock_timeout set, the waiting insert gives up with lock_not_available (55P03)"() {
        given:
        def first = connection()
        def second = connection()
        def key = newKey()
        assert insertKey(first, alice, "funding.create", key) == 1
        second.createStatement().withCloseable { it.execute("SET LOCAL lock_timeout = '200ms'") }
        long started = System.nanoTime()

        when:
        insertKey(second, alice, "funding.create", key)

        then:
        def e = thrown(SQLException)
        e.SQLState == "55P03"
        def waited = Duration.ofNanos(System.nanoTime() - started)
        waited >= Duration.ofMillis(150)
        waited < Duration.ofSeconds(2)
    }

    def "the same key text for another caller or another operation doesn't wait"() {
        given:
        def first = connection()
        def second = connection()
        def key = newKey()
        assert insertKey(first, alice, "funding.create", key) == 1
        // Any wait at all would now fail fast with 55P03.
        second.createStatement().withCloseable { it.execute("SET LOCAL lock_timeout = '100ms'") }

        expect:
        insertKey(second, bob, "funding.create", key) == 1
        insertKey(second, alice, "account.create", key) == 1
    }

    def "a key row can't commit without a response, so nobody ever sees one half-finished"() {
        given:
        def first = connection()
        assert insertKey(first, alice, "funding.create", newKey()) == 1

        when:
        first.commit()

        then:
        def e = thrown(SQLException)
        e.SQLState == "23514"
    }

    private Connection connection() {
        def connection = db.dataSource.connection
        connection.autoCommit = false
        connections << connection
        connection
    }

    private static int insertKey(Connection connection, UUID caller, String operation, String key) {
        connection.prepareStatement(INSERT_KEY).withCloseable { statement ->
            statement.setString(1, caller.toString())
            statement.setString(2, operation)
            statement.setString(3, key)
            statement.setBytes(4, MessageDigest.getInstance("SHA-256").digest(key.bytes))
            statement.executeUpdate()
        }
    }

    private static void storeResponse(Connection connection, UUID caller, String operation, String key, int status, String body) {
        connection.prepareStatement("""
                UPDATE idempotency_keys SET response_status = ?, response_body = ?
                WHERE caller_id = ?::uuid AND operation = ? AND idempotency_key = ?""").withCloseable { statement ->
            statement.setInt(1, status)
            statement.setString(2, body)
            statement.setString(3, caller.toString())
            statement.setString(4, operation)
            statement.setString(5, key)
            assert statement.executeUpdate() == 1
        }
    }

    private static List storedResponse(Connection connection, UUID caller, String operation, String key) {
        connection.prepareStatement("""
                SELECT response_status, response_body FROM idempotency_keys
                WHERE caller_id = ?::uuid AND operation = ? AND idempotency_key = ?""").withCloseable { statement ->
            statement.setString(1, caller.toString())
            statement.setString(2, operation)
            statement.setString(3, key)
            statement.executeQuery().withCloseable { rows -> rows.next() ? [rows.getInt(1), rows.getString(2)] : null }
        }
    }

    private List committedResponse(UUID caller, String operation, String key) {
        db.dataSource.connection.withCloseable { storedResponse(it, caller, operation, key) }
    }

    private static int backendPid(Connection connection) {
        connection.createStatement().withCloseable { it.executeQuery("SELECT pg_backend_pid()").withCloseable { rows -> rows.next(); rows.getInt(1) } }
    }

    /** Polls pg_stat_activity until the backend is blocked on a lock (or 5 s pass). */
    private boolean waitsOnLock(int pid) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (System.nanoTime() < deadline) {
            def waitType = db.dsl.fetchValue("SELECT wait_event_type FROM pg_stat_activity WHERE pid = ?", pid)
            if (waitType == "Lock") {
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
