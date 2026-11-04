package io.paymentslab.model

import io.paymentslab.support.CallerFixtures.TestCaller

/**
 * What the API should do, kept in plain maps: the test oracle for {@link LedgerModelSpec}. It encodes
 * the rules (DESIGN §6, ADR 0009, ADR 0010) independently of the code under test: no SQL, no shared
 * classes. Each request is judged in the API's order: role, validation, idempotency, business rules.
 */
final class LedgerModel {

    static final UUID SYSTEM_FUNDING = UUID.fromString("00000000-0000-0000-0000-000000000001")
    static final long MAX_AMOUNT = 100_000_000L

    /** One POST, kept so it can be sent again or have its key reused. Equal payloads mean the same request. */
    static final class Call {
        final TestCaller caller
        final String operation
        final String key
        final List payload
        final String path
        final String body
        /** Why the generator made this call, when it was aiming at something specific (such as "boundary"). */
        final String tag

        Call(TestCaller caller, String operation, String key, List payload, String path, String body, String tag = null) {
            this.caller = caller
            this.operation = operation
            this.key = key
            this.payload = payload
            this.path = path
            this.body = body
            this.tag = tag
        }
    }

    /** The response stored with an idempotency key: only completed operations are stored. */
    static final class Stored {
        final List payload
        final int status
        final String body

        Stored(List payload, int status, String body) {
            this.payload = payload
            this.status = status
            this.body = body
        }
    }

    static final class Payment {
        final String id
        final UUID creator
        final UUID source
        final UUID destination
        final long amount
        String status

        Payment(String id, UUID creator, UUID source, UUID destination, long amount, String status) {
            this.id = id
            this.creator = creator
            this.source = source
            this.destination = destination
            this.amount = amount
            this.status = status
        }
    }

    /** What the API should answer to one call. */
    static final class Expected {
        final int status
        /** The problem code, for errors. */
        final String code
        /** The transfer's status in a fresh 201 for a transfer or reversal. */
        final String transferStatus
        final String declineReason
        /** Set when the stored response must come back. */
        final Stored replay

        private Expected(int status, String code, String transferStatus, String declineReason, Stored replay) {
            this.status = status
            this.code = code
            this.transferStatus = transferStatus
            this.declineReason = declineReason
            this.replay = replay
        }

        static Expected error(int status, String code) { new Expected(status, code, null, null, null) }

        static Expected created(String transferStatus, String declineReason) { new Expected(201, null, transferStatus, declineReason, null) }

        static Expected replayOf(Stored stored) { new Expected(stored.status, null, null, null, stored) }

        @Override
        String toString() {
            replay != null ? "replay of the stored ${replay.status}" :
                    status == 201 ? "201 ${transferStatus ?: ''} ${declineReason ?: ''}".trim() : "$status $code"
        }
    }

    final Map<UUID, Long> balances = [:]
    final Map<UUID, String> statuses = [:]
    final Map<UUID, UUID> owners = [:]
    final Map<String, Payment> payments = [:]
    final Map<List, Stored> stored = [:]
    long funded = 0
    int entries = 0
    int events = 0

    void openAccount(UUID owner, UUID account) {
        balances[account] = 0L
        statuses[account] = "ACTIVE"
        owners[account] = owner
    }

    void store(TestCaller caller, String operation, String key, List payload, String body) {
        stored[[caller.id, operation, key]] = new Stored(payload, 201, body)
    }

    Expected expect(Call call) {
        switch (call.operation) {
            case "funding.create": return expectFunding(call)
            case "transfer.create": return expectTransfer(call)
            case "transfer.reverse": return expectReversal(call)
            default: throw new IllegalArgumentException("unknown operation ${call.operation}")
        }
    }

    /** Applies a completed operation. Errors and replays change nothing. */
    void commit(Call call, Expected expected, String responseBody, String createdId) {
        if (expected.status != 201 || expected.replay != null) {
            return
        }
        store(call.caller, call.operation, call.key, call.payload, responseBody)
        if (call.operation == "funding.create") {
            UUID account = call.payload[0]
            long amount = call.payload[1]
            balances[account] += amount
            funded += amount
            entries++
        } else if (call.operation == "transfer.create") {
            UUID source = call.payload[0]
            UUID destination = call.payload[1]
            long amount = call.payload[2]
            payments[createdId] = new Payment(createdId, call.caller.id, source, destination, amount, expected.transferStatus)
            events++
            if (expected.transferStatus == "POSTED") {
                balances[source] -= amount
                balances[destination] += amount
                entries++
            }
        } else {
            Payment payment = payments[call.payload[0] as String]
            payment.status = "REVERSED"
            balances[payment.source] += payment.amount
            balances[payment.destination] -= payment.amount
            entries++
            events++
        }
    }

    private Expected expectFunding(Call call) {
        UUID account = call.payload[0]
        long amount = call.payload[1]
        if (call.caller.role != "OPERATOR") return Expected.error(403, "FORBIDDEN")
        if (!validAmount(amount)) return Expected.error(400, "VALIDATION_FAILED")
        if (account == SYSTEM_FUNDING) return Expected.error(422, "INVALID_ACCOUNT_COMBINATION")
        Expected idempotent = fromStore(call)
        if (idempotent != null) return idempotent
        if (!balances.containsKey(account)) return Expected.error(404, "NOT_FOUND")
        if (statuses[account] != "ACTIVE") return Expected.error(422, "ACCOUNT_NOT_ACTIVE")
        Expected.created(null, null)
    }

    private Expected expectTransfer(Call call) {
        UUID source = call.payload[0]
        UUID destination = call.payload[1]
        long amount = call.payload[2]
        if (call.caller.role != "CUSTOMER") return Expected.error(403, "FORBIDDEN")
        if (!validAmount(amount)) return Expected.error(400, "VALIDATION_FAILED")
        if (source == destination) return Expected.error(422, "INVALID_ACCOUNT_COMBINATION")
        Expected idempotent = fromStore(call)
        if (idempotent != null) return idempotent
        if (owners[source] != call.caller.id || !balances.containsKey(destination)) return Expected.error(404, "NOT_FOUND")
        // The ledger checks the source first (status, then balance), then the destination.
        if (statuses[source] != "ACTIVE") return Expected.created("DECLINED", "ACCOUNT_NOT_ACTIVE")
        if (balances[source] < amount) return Expected.created("DECLINED", "INSUFFICIENT_FUNDS")
        if (statuses[destination] != "ACTIVE") return Expected.created("DECLINED", "ACCOUNT_NOT_ACTIVE")
        Expected.created("POSTED", null)
    }

    private Expected expectReversal(Call call) {
        if (call.caller.role != "OPERATOR") return Expected.error(403, "FORBIDDEN")
        Expected idempotent = fromStore(call)
        if (idempotent != null) return idempotent
        Payment payment = payments[call.payload[0] as String]
        if (payment == null) return Expected.error(404, "NOT_FOUND")
        if (payment.status != "POSTED") return Expected.error(409, "INVALID_STATE_TRANSITION")
        // The mirror credits the source, then debits the destination. FROZEN is fine, CLOSED never (ADR 0010).
        if (statuses[payment.source] == "CLOSED" || statuses[payment.destination] == "CLOSED") {
            return Expected.error(422, "ACCOUNT_NOT_ACTIVE")
        }
        if (balances[payment.destination] < payment.amount) return Expected.error(422, "INSUFFICIENT_FUNDS")
        Expected.created("REVERSED", null)
    }

    /** A stored key answers before any business rule: the same request replays, a different one is refused. */
    private Expected fromStore(Call call) {
        Stored previous = stored[[call.caller.id, call.operation, call.key]]
        if (previous == null) {
            return null
        }
        previous.payload == call.payload ? Expected.replayOf(previous) : Expected.error(422, "IDEMPOTENCY_KEY_REUSED")
    }

    private static boolean validAmount(long amount) {
        amount >= 1 && amount <= MAX_AMOUNT
    }
}
