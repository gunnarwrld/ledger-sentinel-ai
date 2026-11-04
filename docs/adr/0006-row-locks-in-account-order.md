# ADR 0006: Concurrency control with ordered row locks

- **Status:** Accepted. **Decided by the student (D3), 2026-10-29**, choosing Claude's proposal.
  To be proven by the Phase 2 concurrency specs (Slice 2d). The student's rationale is pending.
- **Date:** 2026-10-29

## Context

Two transfers that debit the same account must never both succeed if together they overdraw it.
Two transfers in opposite directions (A→B, B→A) must not deadlock. Details: DESIGN §7.

## Proposal

- Isolation level READ COMMITTED, plus explicit locks:
  `SELECT … FROM accounts WHERE id IN (:a, :b) ORDER BY id FOR NO KEY UPDATE`.
- **Fixed lock order:** the idempotency key first (its unique-index entry), then accounts in
  ascending id. Every transaction takes locks in the same global order, so no wait-for cycle can form.
- `FOR NO KEY UPDATE` rather than `FOR UPDATE`: inserting a posting runs a foreign-key check that
  takes `FOR KEY SHARE` on the account row. `FOR UPDATE` conflicts with that lock; `FOR NO KEY UPDATE` does not.
- Balance check and update happen while holding the lock.
- Bounded retry of the whole transaction (3 attempts, jittered backoff) on `40P01`, `40001`, and
  `55P03` at the account-lock step, then `503` with `Retry-After`.
- The `accounts_no_overdraft` CHECK remains as a last line of defence. Tests must show the
  application never reaches it: a race yields a clean `INSUFFICIENT_FUNDS`, not a 500.

## Alternatives

| Option | Trade-off |
|---|---|
| SERIALIZABLE + retry | Correct without explicit locks; hot accounts cause abort storms and every write path needs retries. |
| Conditional `UPDATE … WHERE balance >= :amt` | Fine for one row; a two-row transfer still needs a lock order, and the order is less visible. |
| Optimistic version column | Many retries under contention; same ordering concerns. |
| Advisory locks | Works, but the lock isn't tied to the row it protects. |

## Consequences

- Throughput through one hot account is at most 1 / lock-hold time (for example, 2 ms gives about 500/s).
  Phase 6 must measure this, not hide it.

## Verified

- Service level (Slice 2a): opposite-direction postings never deadlock; locking one account at a time
  in posting order made PostgreSQL report `deadlock detected` (mutation check).
- API level (Slice 2d, `TransferConcurrencySpec`): 20 debits racing for one balance give exactly 10
  POSTED and 10 DECLINED with no errors; 100 opposite-direction transfers all post in about 0.6 s, which
  also shows no deadlock happened (each costs at least the 1 s `deadlock_timeout`); 100 concurrent
  transfers into one account all post. Throughput limits are measured in Phase 6.

## Explain-back (student)

- Why does ordering by id prevent deadlocks, and what cycle would form without it?
- What would you observe in PostgreSQL if two transactions locked in opposite orders?
