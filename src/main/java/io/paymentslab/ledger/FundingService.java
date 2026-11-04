package io.paymentslab.ledger;

import io.paymentslab.money.Money;
import io.paymentslab.money.TransferAmount;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;

/** Moves synthetic money from the SYSTEM funding account into an account (ADR 0005). */
public final class FundingService {

    private final LedgerService ledger;

    public FundingService(LedgerService ledger) {
        this.ledger = ledger;
    }

    public PostedEntry fund(DSLContext tx, UUID accountId, TransferAmount amount) {
        Money money = amount.money();
        return ledger.post(tx, new JournalEntryRequest(EntryKind.FUNDING, money.currency(), null, List.of(
                new Posting(SystemAccounts.FUNDING, money.negate()),
                new Posting(accountId, money))));
    }
}
