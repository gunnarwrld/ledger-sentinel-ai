package io.paymentslab.ledger;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** An entry written in the caller's transaction, with the balance each account ended at. */
public record PostedEntry(UUID entryId, OffsetDateTime createdAt, Map<UUID, Long> balancesAfter) {

    public PostedEntry {
        balancesAfter = Map.copyOf(balancesAfter);
    }
}
