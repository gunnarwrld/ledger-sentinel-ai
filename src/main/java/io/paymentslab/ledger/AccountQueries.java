package io.paymentslab.ledger;

import static io.paymentslab.db.Tables.ACCOUNTS;
import static io.paymentslab.db.Tables.JOURNAL_ENTRIES;
import static io.paymentslab.db.Tables.POSTINGS;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/**
 * Read side of the ledger: balances and statements.
 *
 * <p>Statements page by posting id (keyset), newest first. For a single account, posting ids grow
 * in commit order: {@link LedgerService} inserts postings while holding the account's row lock, and
 * the identity sequence hands out ids in call order (it has no per-session cache). So a posting that
 * commits later can't land behind a cursor a client already holds, and new postings never shift the
 * pages still to come.
 */
public final class AccountQueries {

    public record AccountView(UUID id, String currency, String status, long balanceMinor, OffsetDateTime createdAt) {}

    /** One posting on the account, with the entry it belongs to. {@code amountMinor} is signed (ADR 0005). */
    public record StatementLine(
            long postingId, UUID journalEntryId, EntryKind kind, long amountMinor, String currency,
            OffsetDateTime createdAt) {}

    /**
     * The account, if it exists and {@code ownerId} owns it. Ownership is part of the query, so
     * "not yours" and "doesn't exist" are the same empty result, reached by the same path.
     */
    public Optional<AccountView> findOwned(DSLContext db, UUID ownerId, UUID accountId) {
        return db.select(ACCOUNTS.ID, ACCOUNTS.CURRENCY, ACCOUNTS.STATUS, ACCOUNTS.BALANCE_MINOR, ACCOUNTS.CREATED_AT)
                .from(ACCOUNTS)
                .where(ACCOUNTS.ID.eq(accountId))
                .and(ACCOUNTS.OWNER_ID.eq(ownerId))
                .fetchOptional(row -> new AccountView(row.value1(), row.value2(), row.value3(), row.value4(), row.value5()));
    }

    public boolean exists(DSLContext db, UUID accountId) {
        return db.fetchExists(ACCOUNTS, ACCOUNTS.ID.eq(accountId));
    }

    /**
     * Up to {@code limit} postings on the account, newest first, starting below
     * {@code beforePostingId} (null starts at the newest). Served by {@code postings_account_idx}.
     */
    public List<StatementLine> statement(DSLContext db, UUID accountId, Long beforePostingId, int limit) {
        Condition afterCursor = beforePostingId == null ? DSL.noCondition() : POSTINGS.ID.lt(beforePostingId);
        return db.select(POSTINGS.ID, JOURNAL_ENTRIES.ID, JOURNAL_ENTRIES.KIND, POSTINGS.AMOUNT_MINOR,
                        POSTINGS.CURRENCY, JOURNAL_ENTRIES.CREATED_AT)
                .from(POSTINGS)
                .join(JOURNAL_ENTRIES).on(JOURNAL_ENTRIES.ID.eq(POSTINGS.JOURNAL_ENTRY_ID))
                .where(POSTINGS.ACCOUNT_ID.eq(accountId))
                .and(afterCursor)
                .orderBy(POSTINGS.ID.desc())
                .limit(limit)
                .fetch(row -> new StatementLine(row.value1(), row.value2(), EntryKind.valueOf(row.value3()),
                        row.value4(), row.value5(), row.value6()));
    }
}
