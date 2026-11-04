package io.paymentslab.payments;

import io.paymentslab.money.TransferAmount;
import java.util.Objects;
import java.util.UUID;

/** A validated transfer request. {@code reference} may be null. */
public record TransferCommand(
        UUID callerId, String idempotencyKey, UUID source, UUID destination, TransferAmount amount, String reference) {

    public TransferCommand {
        Objects.requireNonNull(callerId, "callerId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(amount, "amount");
        if (source.equals(destination)) {
            throw new IllegalArgumentException("source and destination must differ");
        }
    }

    /** Keeps the free-text reference out of logs and stack traces. */
    @Override
    public String toString() {
        return "TransferCommand[callerId=" + callerId + ", source=" + source + ", destination=" + destination
                + ", amount=" + amount + ", reference=" + (reference == null ? "none" : "***") + "]";
    }
}
