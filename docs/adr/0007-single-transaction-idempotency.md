# ADR 0007: Idempotency inside the business transaction

- **Status:** Accepted. **Decided by the student (D2), 2026-10-29**, choosing Claude's proposal.
  Risk R3 must be retired by a real-PostgreSQL test in Slice 2c before anything builds on it.
  The student's rationale is pending.
- **Date:** 2026-10-29

## Context

Clients retry: timeouts, lost responses, double clicks. A retried transfer must never move money
twice, and a key reused for a different request must be rejected. Full behaviour table: DESIGN §6.

## Proposal

Scope keys to `(caller_id, operation, idempotency_key)`. As the **first statement** of the transfer
transaction, `INSERT … ON CONFLICT DO NOTHING` the key row with a SHA-256 hash of the canonical
request. The row stays uncommitted until the end of the transaction, so:

- A concurrent duplicate **waits** on the unique index until the first transaction finishes.
  Committed: the duplicate reads the stored response and replays it. Rolled back: the duplicate
  runs. `lock_timeout` (2 s) fires first: `409 IDEMPOTENCY_REQUEST_IN_PROGRESS` with `Retry-After`.
- Nobody else can ever see a half-finished result; there is no visible IN_PROGRESS state to clean up after a crash.
- The response snapshot is written in the same commit as the money movement, so a response lost
  after commit is recovered by retrying.
- Same key, different payload: `422 IDEMPOTENCY_KEY_REUSED`. Validation, auth and not-found errors are not stored.
- Snapshots are kept 7 days; `UNIQUE (caller_id, idempotency_key)` on `payments` blocks a second
  transfer forever (`409 IDEMPOTENCY_KEY_EXPIRED` after the snapshot is purged).
- Redis is never consulted.

## Alternatives

| Option | Trade-off |
|---|---|
| Two-phase: commit an IN_PROGRESS claim with a lease, do the work, commit the result | Required when the work includes a side effect outside our database (for example, calling a card network). Needs lease expiry and recovery logic. |
| Insert the key at the end of the transaction | Duplicates do all the work, then one fails on the unique index: wasted locks and connections. |
| Redis `SET NX` as the guard | Redis isn't the source of truth; losing a key would allow a double transfer. |

## Consequences

- A duplicate holds a connection while it waits; `lock_timeout` bounds that.
- If a step ever calls an external system, this ADR must be revisited (two-phase).

## Risk R3 retired, and implementation (Slice 2c, VERIFIED)

- `IdempotencyProtocolSpec` (plain SQL, two connections, PostgreSQL 18): a duplicate INSERT of the
  key **waits** (seen in `pg_stat_activity` as `wait_event_type = Lock`) and then reads the committed
  response; if the first transaction rolls back, the duplicate takes the key; with `lock_timeout` it
  fails with 55P03 instead; other callers and operations don't wait.
- V4 `idempotency_keys`: `response_body` is `text`, not the `jsonb` of the DESIGN draft, because jsonb
  reorders keys and replays must be byte-for-byte. A deferred constraint trigger refuses to commit a
  key row without its response, so no half-finished row is ever visible.
- `Idempotency` (package `payments`) runs inside `TxRunner`. A 55P03 on the key becomes
  `409 IDEMPOTENCY_REQUEST_IN_PROGRESS` and is not retried. Any error in the work rolls back the key
  too, so only completed operations are stored.
- API proof: `IdempotencyApiSpec` (repeats, rewritten payloads, reuse → 422, per-caller scope, 20
  concurrent duplicates → one funding, and mid-flight duplicates that replay, take over, or get 409).

## Explain-back (student)

- What kind of side effect would force the two-phase design?
- Why is it safe for a crashed request's key to become free again?
