package io.paymentslab.ledger;

import java.util.UUID;

/** Well-known accounts seeded by migrations. */
public final class SystemAccounts {

    /** Issues synthetic money and may go negative. Seeded by V3__system_funding_account.sql. */
    public static final UUID FUNDING = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private SystemAccounts() {}
}
