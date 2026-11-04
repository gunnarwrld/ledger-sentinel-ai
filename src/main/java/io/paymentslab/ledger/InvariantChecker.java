package io.paymentslab.ledger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.Record;

/**
 * Re-checks the ledger invariants (DESIGN §5) with plain SQL, so the same queries can be pasted
 * into psql. Run after integration specs, property runs, load tests and crash demos. An empty
 * result means every check passed.
 */
public final class InvariantChecker {

    public record Violation(String invariant, String detail) {}

    private record Check(String invariant, String description, String sql) {}

    private static final List<Check> CHECKS = List.of(
            new Check("I5", "journal entry is not balanced", """
                    SELECT e.id::text
                    FROM journal_entries e LEFT JOIN postings p ON p.journal_entry_id = e.id
                    GROUP BY e.id
                    HAVING count(p.id) < 2 OR coalesce(sum(p.amount_minor), 0) <> 0"""),
            new Check("I5", "posting currency differs from its entry", """
                    SELECT p.id::text
                    FROM postings p JOIN journal_entries e ON e.id = p.journal_entry_id
                    WHERE p.currency <> e.currency"""),
            new Check("I5", "balance trigger missing or disabled",
                    missingTriggers("postings_entry_balanced", "journal_entries_balanced")),
            new Check("I6", "balances don't sum to zero", """
                    SELECT currency || ' total ' || sum(balance_minor)
                    FROM accounts
                    GROUP BY currency
                    HAVING sum(balance_minor) <> 0"""),
            new Check("I7", "balance differs from the sum of its postings", """
                    SELECT a.id::text || ' balance ' || a.balance_minor || ' postings ' || coalesce(sum(p.amount_minor), 0)
                    FROM accounts a LEFT JOIN postings p ON p.account_id = a.id
                    GROUP BY a.id
                    HAVING a.balance_minor <> coalesce(sum(p.amount_minor), 0)"""),
            new Check("I8", "account below zero without allow_negative", """
                    SELECT id::text || ' balance ' || balance_minor
                    FROM accounts
                    WHERE NOT allow_negative AND balance_minor < 0"""),
            new Check("I9", "append-only trigger missing or disabled", missingTriggers(
                    "journal_entries_append_only", "journal_entries_no_truncate",
                    "postings_append_only", "postings_no_truncate")),
            // A balanced reversal for the wrong amount passes every check above. The two EXCEPT ALLs
            // compare the multisets of (account, amount) in both directions.
            new Check("I9", "reversal doesn't mirror the entry it reverses", """
                    SELECT r.id::text
                    FROM journal_entries r
                    WHERE r.kind = 'REVERSAL'
                      AND EXISTS (
                        (SELECT account_id, -amount_minor FROM postings WHERE journal_entry_id = r.reverses_id
                         EXCEPT ALL
                         SELECT account_id, amount_minor FROM postings WHERE journal_entry_id = r.id)
                        UNION ALL
                        (SELECT account_id, amount_minor FROM postings WHERE journal_entry_id = r.id
                         EXCEPT ALL
                         SELECT account_id, -amount_minor FROM postings WHERE journal_entry_id = r.reverses_id))"""));

    public List<Violation> check(DSLContext db) {
        List<Violation> violations = new ArrayList<>();
        for (Check check : CHECKS) {
            for (Record row : db.fetch(check.sql())) {
                violations.add(new Violation(check.invariant(), check.description() + ": " + row.get(0)));
            }
        }
        return violations;
    }

    /** Selects each named trigger that doesn't exist or doesn't fire in normal operation. */
    private static String missingTriggers(String... names) {
        String expected = Arrays.stream(names).map(name -> "('" + name + "')").collect(Collectors.joining(", "));
        return """
                SELECT expected.name
                FROM (VALUES %s) AS expected(name)
                WHERE NOT EXISTS (
                    SELECT 1 FROM pg_trigger t WHERE t.tgname = expected.name AND t.tgenabled IN ('O', 'A'))"""
                .formatted(expected);
    }
}
