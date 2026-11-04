package io.paymentslab.payments;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A stored payment. {@code declineReason} is set only when DECLINED; {@code reference} may be null. */
public record Transfer(
        UUID id, UUID callerId, PaymentStatus status, DeclineReason declineReason, UUID source, UUID destination,
        long amountMinor, String currency, String reference, OffsetDateTime createdAt) {

    /** Keeps the free-text reference out of logs and stack traces. */
    @Override
    public String toString() {
        return "Transfer[id=" + id + ", status=" + status + ", declineReason=" + declineReason + "]";
    }
}
