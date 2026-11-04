package io.paymentslab.app

import io.paymentslab.api.CallerRole
import io.paymentslab.api.Callers
import io.paymentslab.support.CallerFixtures
import io.paymentslab.support.LogCapture
import io.paymentslab.support.TestDatabase
import io.paymentslab.support.TestDatabases
import java.nio.charset.StandardCharsets
import spock.lang.Shared
import spock.lang.Specification

/** {@code create-caller} against a real database (ADR 0008: the key is shown once, only its hash is stored). */
class CreateCallerCommandSpec extends Specification {

    static final String KEY = /pl_test_[A-Za-z0-9_-]{43}/

    @Shared TestDatabase db = TestDatabases.create()
    ByteArrayOutputStream out = new ByteArrayOutputStream()
    ByteArrayOutputStream err = new ByteArrayOutputStream()

    def cleanupSpec() {
        db?.close()
    }

    def "creates a caller and prints a working API key exactly once"() {
        when:
        def exit = run(CallerRole.OPERATOR, "ops-1")

        then:
        exit == 0
        printedKeys().size() == 1

        and: "the key authenticates as the new caller"
        def caller = new Callers().findByApiKey(db.dsl, printedKeys()[0]).orElseThrow()
        caller.name() == "ops-1"
        caller.role() == CallerRole.OPERATOR

        and: "only the SHA-256 hash is stored; no column holds the key"
        storedHash("ops-1") == CallerFixtures.sha256(printedKeys()[0])
        !rowAsText("ops-1").contains(printedKeys()[0].substring("pl_test_".length()))
    }

    def "the key goes to standard output only, never to the log"() {
        given:
        def logs = new LogCapture("io.paymentslab")

        when:
        run(CallerRole.CUSTOMER, "customer-log-check")

        then: "the creation is logged..."
        logs.text().contains("customer-log-check")

        and: "...without the key"
        !logs.text().contains(printedKeys()[0])

        cleanup:
        logs.close()
    }

    def "a taken name is refused: no second caller and no key printed"() {
        given:
        run(CallerRole.CUSTOMER, "taken")
        out.reset()

        when:
        def exit = run(CallerRole.OPERATOR, "taken")

        then:
        exit == 1
        err.toString(StandardCharsets.UTF_8).contains("already exists")
        printedKeys() == []
        db.dsl.fetchValue("SELECT count(*) FROM callers WHERE name = 'taken'") == 1
    }

    def "a name with #why is refused before anything is stored"() {
        when:
        def exit = run(CallerRole.CUSTOMER, name)

        then:
        exit == 1
        err.toString(StandardCharsets.UTF_8).contains("name must be")
        printedKeys() == []
        db.dsl.fetchValue("SELECT count(*) FROM callers WHERE name = ?", name) == 0

        where:
        why                     | name
        "no characters"         | ""
        "a space"               | "has space"
        "a line break"          | "line\nbreak"
        "more than 64 characters" | "x" * 65
        "non-ASCII letters"     | "ünïcode"
        "a semicolon"           | "semi;colon"
    }

    def "the created record never prints its key"() {
        when:
        def created = new Callers().create(db.dsl, "to-string-check", CallerRole.CUSTOMER)

        then:
        !created.toString().contains(created.apiKey())
        created.toString().contains("apiKey=***")
    }

    private int run(CallerRole role, String name) {
        CreateCallerCommand.run(db.dsl, new CreateCallerCommand.Options(role, name),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8))
    }

    private List<String> printedKeys() {
        (out.toString(StandardCharsets.UTF_8) =~ KEY).collect { it as String }
    }

    private byte[] storedHash(String name) {
        db.dsl.fetchValue("SELECT api_key_hash FROM callers WHERE name = ?", name) as byte[]
    }

    private String rowAsText(String name) {
        db.dsl.fetchValue("SELECT row_to_json(c)::text FROM callers c WHERE name = ?", name) as String
    }
}
