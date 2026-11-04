package io.paymentslab.support

import java.security.SecureRandom
import org.jooq.DSLContext

/** Test data written with plain SQL, the way an external client would. Synthetic only. */
final class LedgerFixtures {

    private static final SecureRandom RANDOM = new SecureRandom()

    private LedgerFixtures() {}

    /** A new customer (caller) and an empty, active EUR account they own. Returns the account id. */
    static UUID customerAccount(DSLContext db) {
        def callerId = UUID.randomUUID()
        def keyHash = new byte[32]
        RANDOM.nextBytes(keyHash)
        db.execute("INSERT INTO callers (id, name, role, api_key_hash) VALUES (?::uuid, ?, 'CUSTOMER', ?)",
                callerId, "customer-$callerId".toString(), keyHash)
        accountFor(db, callerId)
    }

    /** An empty, active EUR account owned by an existing caller. Returns the account id. */
    static UUID accountFor(DSLContext db, UUID ownerId) {
        def accountId = UUID.randomUUID()
        db.execute("INSERT INTO accounts (id, owner_id, kind, currency) VALUES (?::uuid, ?::uuid, 'CUSTOMER', 'EUR')",
                accountId, ownerId)
        accountId
    }

    static long balance(DSLContext db, UUID accountId) {
        db.fetchValue("SELECT balance_minor FROM accounts WHERE id = ?::uuid", accountId) as long
    }
}
