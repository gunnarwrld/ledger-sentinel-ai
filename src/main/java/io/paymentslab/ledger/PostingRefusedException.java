package io.paymentslab.ledger;

import java.util.UUID;

/**
 * The ledger refused an entry because of an account's state. Thrown before anything is written,
 * so the caller's transaction is still usable (for example, to record a declined payment).
 */
public abstract sealed class PostingRefusedException extends RuntimeException
        permits AccountNotFoundException, AccountNotActiveException, InsufficientFundsException {

    private final UUID accountId;

    PostingRefusedException(UUID accountId, String reason) {
        super("account " + accountId + " " + reason);
        this.accountId = accountId;
    }

    public UUID accountId() {
        return accountId;
    }
}
