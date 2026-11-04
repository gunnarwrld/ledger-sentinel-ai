package io.paymentslab.ledger;

import static io.paymentslab.db.Tables.ACCOUNTS;

import io.paymentslab.db.tables.records.AccountsRecord;
import io.paymentslab.ledger.AccountQueries.AccountView;
import io.paymentslab.money.SupportedCurrencies;
import java.util.Currency;
import java.util.UUID;
import org.jooq.DSLContext;

/** Opens customer accounts: ACTIVE, zero balance, never allowed to go negative. */
public final class AccountOpening {

    public AccountView open(DSLContext tx, UUID ownerId, Currency currency) {
        SupportedCurrencies.require(currency);
        AccountsRecord account = tx.insertInto(ACCOUNTS)
                .set(ACCOUNTS.ID, UUID.randomUUID())
                .set(ACCOUNTS.OWNER_ID, ownerId)
                .set(ACCOUNTS.KIND, "CUSTOMER")
                .set(ACCOUNTS.CURRENCY, currency.getCurrencyCode())
                .returning()
                .fetchSingle();
        return new AccountView(account.getId(), account.getCurrency(), account.getStatus(),
                account.getBalanceMinor(), account.getCreatedAt());
    }
}
