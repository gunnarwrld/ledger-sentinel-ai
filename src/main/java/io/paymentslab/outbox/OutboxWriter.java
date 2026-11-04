package io.paymentslab.outbox;

import static io.paymentslab.db.Tables.OUTBOX_EVENTS;

import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;

/**
 * Appends events in the caller's transaction (transactional outbox, I13): an event exists if and only
 * if the state change it describes committed. Publishing them is Phase 3's relay.
 */
public final class OutboxWriter {

    /** Returns the event id, which consumers use to drop redeliveries. */
    public UUID append(DSLContext tx, String aggregateType, UUID aggregateId, String eventType, String payloadJson) {
        UUID eventId = UUID.randomUUID();
        tx.insertInto(OUTBOX_EVENTS)
                .set(OUTBOX_EVENTS.EVENT_ID, eventId)
                .set(OUTBOX_EVENTS.AGGREGATE_TYPE, aggregateType)
                .set(OUTBOX_EVENTS.AGGREGATE_ID, aggregateId)
                .set(OUTBOX_EVENTS.EVENT_TYPE, eventType)
                .set(OUTBOX_EVENTS.PAYLOAD, JSONB.valueOf(payloadJson))
                .execute();
        return eventId;
    }
}
