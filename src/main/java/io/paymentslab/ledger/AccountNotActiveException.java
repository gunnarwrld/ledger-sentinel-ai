package io.paymentslab.ledger;

import java.util.UUID;

public final class AccountNotActiveException extends PostingRefusedException {
    public AccountNotActiveException(UUID accountId) {
        super(accountId, "is not active");
    }
}
