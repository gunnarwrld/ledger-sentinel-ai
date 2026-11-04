# ADR 0009: A business decline is `201` with `status: DECLINED`

- **Status:** Accepted. **Decided by the student (D4), 2026-10-30**, choosing Claude's proposal.
  The student's rationale is pending (see `PROGRESS.md`).
- **Date:** 2026-10-30

## Context

A well-formed transfer from an account the caller owns can still be refused: insufficient funds,
an account that isn't active, and from Phase 4 a risk decline. HTTP needs one consistent shape for that.

## Decision

The request succeeded in creating a payment whose outcome is negative, so it gets `201 Created` with
the Transfer body, `status: DECLINED` and a coarse `declineReason` (`INSUFFICIENT_FUNDS`,
`ACCOUNT_NOT_ACTIVE`, later `RISK_DECLINED`). Error statuses stay for requests that create nothing:
400 validation, 401/403 auth, 404 not found or not owned, 409/422 idempotency, 422 same account.

## Consequences

- A decline is a stored outcome: a payment row with no journal entry (a DB CHECK enforces that), an
  outbox event, and the idempotency snapshot. **A retry with the same key replays DECLINED, even after
  the account has been topped up; trying again needs a new key.** This differs from Slice 2c's refusals
  (such as funding a frozen account), which create nothing and leave the key free.
- Declines appear in history and can be audited and counted.
- Benchmarks can tell business declines (201 DECLINED) from errors (4xx/5xx) by status code alone.
- Under a race for the same funds, the losers get a clean `201 DECLINED(INSUFFICIENT_FUNDS)`, never
  an error (`OverdraftConcurrency` feature in `TransferConcurrencySpec`).
- Clients must read `status`, not only the HTTP status code.

## Alternatives

| Option | Why not |
|---|---|
| `422 INSUFFICIENT_FUNDS` | Nothing stored: the decline vanishes from history, and a same-key retry could later succeed, so the key no longer pins one outcome |
| `402 Payment Required` | Reserved by HTTP for future use; clients and tools treat it inconsistently |
| `200 OK` | A payment resource was created, which is what `201` says |

## Explain-back (student)

- Why does a retry with the same key still say DECLINED after the source account was funded?
- In a load test, which responses count as errors and which as business outcomes?
