package io.paymentslab.payments;

/**
 * The payment state machine (DESIGN §4.3, invariant I12). Transitions happen as conditional
 * {@code UPDATE … WHERE status = <expected>} statements (reversals in Slice 2e, reviews in Phase 4).
 * Must list the same values as {@code payments_status_known}.
 */
public enum PaymentStatus {
    POSTED, DECLINED, REVIEW_REQUIRED, REVERSED;

    /** A payment is created POSTED, DECLINED or REVIEW_REQUIRED; only a reversal makes it REVERSED. */
    public boolean canStart() {
        return this != REVERSED;
    }

    public boolean canBecome(PaymentStatus next) {
        return switch (this) {
            case REVIEW_REQUIRED -> next == POSTED || next == DECLINED;
            case POSTED -> next == REVERSED;
            case DECLINED, REVERSED -> false;
        };
    }

    /** Whether the payment has a journal entry; the {@code payments_entry_iff_money_moved} CHECK agrees. */
    public boolean movedMoney() {
        return this == POSTED || this == REVERSED;
    }
}
