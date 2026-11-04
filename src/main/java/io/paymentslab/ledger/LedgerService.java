package io.paymentslab.ledger;

import static io.paymentslab.db.Tables.ACCOUNTS;
import static io.paymentslab.db.Tables.JOURNAL_ENTRIES;
import static io.paymentslab.db.Tables.POSTINGS;

import io.paymentslab.db.tables.records.AccountsRecord;
import io.paymentslab.db.tables.records.JournalEntriesRecord;
import io.paymentslab.money.Money;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/**
 * Writes balanced journal entries and keeps account balances in step with them.
 *
 * <p>Runs inside the caller's transaction ({@code tx}); the caller decides when to commit. Every
 * check happens before the first write, so a refusal leaves the transaction clean and usable.
 */
public final class LedgerService {

    public PostedEntry post(DSLContext tx, JournalEntryRequest request) {
        Map<UUID, AccountsRecord> accounts = lockAccounts(tx, request.postings());

        Map<UUID, Long> balancesAfter = new LinkedHashMap<>();
        for (Posting posting : request.postings()) {
            AccountsRecord account = accounts.get(posting.accountId());
            if (account == null) {
                throw new AccountNotFoundException(posting.accountId());
            }
            if (!request.kind().allowsPostingTo(account.getStatus())) {
                throw new AccountNotActiveException(posting.accountId());
            }
            // Checked arithmetic: overflow and a currency mismatch with the account both throw.
            Money after = Money.of(account.getBalanceMinor(), Currency.getInstance(account.getCurrency()))
                    .plus(posting.amount());
            if (after.minor() < 0 && !account.getAllowNegative()) {
                throw new InsufficientFundsException(posting.accountId());
            }
            balancesAfter.put(posting.accountId(), after.minor());
        }

        UUID entryId = UUID.randomUUID();
        String currency = request.currency().getCurrencyCode();
        OffsetDateTime createdAt = tx.insertInto(JOURNAL_ENTRIES)
                .set(JOURNAL_ENTRIES.ID, entryId)
                .set(JOURNAL_ENTRIES.KIND, request.kind().name())
                .set(JOURNAL_ENTRIES.CURRENCY, currency)
                .set(JOURNAL_ENTRIES.REVERSES_ID, request.reversesEntryId())
                .returningResult(JOURNAL_ENTRIES.CREATED_AT)
                .fetchSingle()
                .value1();
        var insertPostings = tx.insertInto(POSTINGS,
                POSTINGS.JOURNAL_ENTRY_ID, POSTINGS.ACCOUNT_ID, POSTINGS.CURRENCY, POSTINGS.AMOUNT_MINOR);
        for (Posting posting : request.postings()) {
            insertPostings = insertPostings.values(entryId, posting.accountId(), currency, posting.amount().minor());
        }
        insertPostings.execute();
        for (Posting posting : request.postings()) {
            tx.update(ACCOUNTS)
                    .set(ACCOUNTS.BALANCE_MINOR, ACCOUNTS.BALANCE_MINOR.plus(posting.amount().minor()))
                    .set(ACCOUNTS.UPDATED_AT, DSL.currentOffsetDateTime())
                    .where(ACCOUNTS.ID.eq(posting.accountId()))
                    .execute();
        }
        return new PostedEntry(entryId, createdAt, balancesAfter);
    }

    /**
     * Posts a REVERSAL entry that mirrors {@code entryId} exactly: the same accounts with every amount
     * negated (I9). The checks of {@link #post} apply, with the reversal's account rule: FROZEN accounts
     * are allowed, CLOSED ones never (ADR 0010). Callers serialize reversals of one entry;
     * {@code UNIQUE (reverses_id)} is the backstop.
     *
     * @throws InvalidEntryException if the entry doesn't exist, is itself a reversal, or is already reversed
     */
    public PostedEntry reverse(DSLContext tx, UUID entryId) {
        JournalEntriesRecord original = tx.selectFrom(JOURNAL_ENTRIES).where(JOURNAL_ENTRIES.ID.eq(entryId)).fetchOne();
        if (original == null) {
            throw new InvalidEntryException("no journal entry " + entryId);
        }
        if (EntryKind.valueOf(original.getKind()) == EntryKind.REVERSAL) {
            throw new InvalidEntryException("a reversal can't be reversed");
        }
        if (tx.fetchExists(JOURNAL_ENTRIES, JOURNAL_ENTRIES.REVERSES_ID.eq(entryId))) {
            throw new InvalidEntryException("entry " + entryId + " is already reversed");
        }
        Currency currency = Currency.getInstance(original.getCurrency());
        List<Posting> mirror = tx.select(POSTINGS.ACCOUNT_ID, POSTINGS.AMOUNT_MINOR)
                .from(POSTINGS)
                .where(POSTINGS.JOURNAL_ENTRY_ID.eq(entryId))
                .orderBy(POSTINGS.ID)
                .fetch(row -> new Posting(row.value1(), Money.of(row.value2(), currency).negate()));
        return post(tx, new JournalEntryRequest(EntryKind.REVERSAL, currency, entryId, mirror));
    }

    /**
     * Locks every account in the entry with one statement, in PostgreSQL's uuid order, so all
     * transactions take their locks in the same global order and can't deadlock (ADR 0006).
     * Don't sort in Java instead: {@code UUID.compareTo} compares signed longs, which is not
     * PostgreSQL's byte order. {@code FOR NO KEY UPDATE} leaves foreign-key checks unblocked.
     */
    private static Map<UUID, AccountsRecord> lockAccounts(DSLContext tx, List<Posting> postings) {
        List<UUID> ids = postings.stream().map(Posting::accountId).toList();
        return tx.selectFrom(ACCOUNTS)
                .where(ACCOUNTS.ID.in(ids))
                .orderBy(ACCOUNTS.ID)
                .forNoKeyUpdate()
                .fetchMap(ACCOUNTS.ID);
    }
}
