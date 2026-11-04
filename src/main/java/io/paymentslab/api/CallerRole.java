package io.paymentslab.api;

/** Must list the same values as the {@code callers_role_known} CHECK constraint. */
public enum CallerRole {
    /** Owns accounts and moves money out of them. */
    CUSTOMER,
    /** Funds accounts, reverses transfers and decides reviews; owns no accounts. */
    OPERATOR
}
