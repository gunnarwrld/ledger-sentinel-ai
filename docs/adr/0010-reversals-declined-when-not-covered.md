# ADR 0010: A reversal is declined when the receiver can't cover it, and may touch frozen accounts

- **Status:** Accepted. **Decided by the student (D7), 2026-11-03**, choosing Claude's proposal on both
  questions. The student's rationale is pending (see `PROGRESS.md`). Implemented in Slice 2e (VERIFIED).
- **Date:** 2026-11-03

## Context

An operator reverses a POSTED transfer with a new entry that mirrors it: receiver −amount, sender
+amount. The journal stays append-only (I9). The receiver may have spent part of the money already,
and customer balances must never go below zero (I8, enforced by the ledger and by a database CHECK).
Separately, the ledger refuses postings to any account that isn't ACTIVE, but freezing the receiver is
usually the first step of a fraud response.

## Decision

1. **Not covered → declined.** If the receiver's balance is below the amount, the reversal is refused
   with `422` (planned code `INSUFFICIENT_FUNDS`). Nothing is written: the payment stays POSTED, and the
   idempotency key stays free, so a later retry can succeed once the funds are there. I8 stays unconditional.
2. **Frozen accounts.** A reversal may post to FROZEN accounts on either side; CLOSED accounts never take
   postings. Ordinary transfers keep requiring ACTIVE accounts.

Unchanged from the plan: reversals are operator-only and idempotent; reversing twice or reversing a
DECLINED transfer → `409 INVALID_STATE_TRANSITION`; of two concurrent reversals exactly one succeeds.

## Consequences

- The sender carries the loss until it is recovered outside the ledger; the ledger has no record of
  the failed attempt. A case or dispute record would be needed to track it (not in scope).
- The ledger needs a way to allow FROZEN accounts for reversal entries only (Slice 2e).
- No partial reversals: one reversal per entry (`UNIQUE (reverses_id)`) reverses the full amount.

## Implementation (Slice 2e, VERIFIED)

- `LedgerService.reverse` posts the exact mirror of the original entry; `EntryKind.allowsPostingTo`
  lets only REVERSAL entries reach FROZEN accounts. `InvariantChecker` reports any REVERSAL that doesn't
  exactly mirror its original (I9).
- Lock order: the idempotency key, then the payment's row (`FOR NO KEY UPDATE`), then both accounts in id
  order. The row lock gives concurrent reversals of one transfer the right answer (one 201, the rest
  409). Without it (mutation check), money still moved once, because the balance check and
  `UNIQUE (reverses_id)` are backstops, but the losers got a misleading 422 instead of 409.
- The payment records the reversing operator (`reversed_by`, V6, threat T7).
- Specs: `ReversalApiSpec`, `EntryKindSpec`, reversal features in `LedgerServiceSpec`,
  `InvariantCheckerSpec` and `TransferAtomicitySpec`.

## Alternatives

| Option | Why not chosen |
|---|---|
| Let the receiver's balance go negative | Weakens I8; the platform carries credit risk on customer debts |
| Platform absorbs the loss from a SYSTEM losses account | Needs a second system account and a non-mirror entry; hides recoverable money as a write-off |
| Partial reversal (take what's there, write off the rest) | Needs several reversal entries per transfer and partial states |
| Reversals only into ACTIVE accounts | The operator would have to unfreeze a suspected fraudster first, and the funds could move in that gap |

## Explain-back (student)

- Under this decision, where does an unrecovered loss show up, if not in the ledger?
- Why is it safe to let a reversal post to a frozen account but not an ordinary transfer?
