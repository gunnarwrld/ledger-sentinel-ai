package io.paymentslab.payments;

/**
 * The source isn't the caller's, or an account doesn't exist. One exception for both, so the API
 * answers both with the same 404 and ids can't be probed.
 */
public final class AccountNotVisibleException extends RuntimeException {

    public AccountNotVisibleException() {
        super("account not found or not visible to the caller", null, false, false);
    }
}
