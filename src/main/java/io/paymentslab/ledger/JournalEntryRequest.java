package io.paymentslab.ledger;

import io.paymentslab.money.Money;
import io.paymentslab.money.SupportedCurrencies;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A journal entry about to be posted. The constructor enforces the rules that need no database:
 * a supported currency, at least two non-zero postings in that currency, each account at most
 * once, and a sum of exactly zero (I3, I5). The database checks the same rules again at commit.
 */
public record JournalEntryRequest(EntryKind kind, Currency currency, UUID reversesEntryId, List<Posting> postings) {

    public JournalEntryRequest {
        Objects.requireNonNull(kind, "kind");
        SupportedCurrencies.require(currency);
        postings = List.copyOf(postings);
        if ((kind == EntryKind.REVERSAL) != (reversesEntryId != null)) {
            throw new InvalidEntryException("a REVERSAL entry, and only a REVERSAL entry, names the entry it reverses");
        }
        if (postings.size() < 2) {
            throw new InvalidEntryException("an entry needs at least two postings");
        }
        Set<UUID> accounts = new HashSet<>();
        Money sum = Money.of(0, currency);
        for (Posting posting : postings) {
            if (!posting.amount().currency().equals(currency)) {
                throw new InvalidEntryException("a " + currency + " entry can't hold a "
                        + posting.amount().currency() + " posting");
            }
            if (posting.amount().minor() == 0) {
                throw new InvalidEntryException("postings can't be zero");
            }
            if (!accounts.add(posting.accountId())) {
                throw new InvalidEntryException("account " + posting.accountId() + " appears twice");
            }
            sum = sum.plus(posting.amount()); // checked: overflow throws ArithmeticException
        }
        if (sum.minor() != 0) {
            throw new InvalidEntryException("postings sum to " + sum.minor() + ", not zero");
        }
    }
}
