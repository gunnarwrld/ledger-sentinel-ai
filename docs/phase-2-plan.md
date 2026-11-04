# Phase 2 plan: ledger and transfers

> **Status: approved 2026-10-29. **Phase 2 complete:** slices 2a–2f VERIFIED; R3 retired, R4 retired at API level (throughput in Phase 6). Waiting for the student's review.** Builds on D2 (single-transaction
> idempotency, ADR 0007), D3 (ordered row locks, ADR 0006), D8 (signed postings, ADR 0005) and
> D9 (minimal auth now, ADR 0008). D4 (ADR 0009) and D7 (ADR 0010) are decided.

## What changed from DESIGN §12

- **Idempotency moves ahead of transfers.** Every POST endpoint is idempotent from the day it
  ships, and risk R3 (a duplicate waits on the unique index, then replays) is proven on real
  PostgreSQL before transfers depend on it.
- **The ledger core comes first, without HTTP**, so balancing and immutability are proven at the
  database and service level before any endpoint can call them.

## Working rules (unchanged from Phase 1)

- Each slice: specs first → red run with its causes checked → implementation → green run →
  `InvariantChecker` → `PROGRESS.md` entry.
- Implementing an endpoint removes its `x-status: planned` from `openapi.yaml` in the same change
  (`OpenApiContractSpec` enforces this).
- Migrations are never edited once committed.
- Stop at the end of Phase 2. Stop earlier if a risk test fails or behaves unexpectedly (R3 in 2c, R4 in 2d).

## Test infrastructure (start of 2a)

Ledger tables are append-only, so specs can't clean up by deleting rows. One PostgreSQL container
per test JVM migrates a template database once; each spec gets its own copy with
`CREATE DATABASE … TEMPLATE payments_template`. This also replaces one-container-per-spec, so the
suite gets faster. `HealthApiSpec` keeps its own containers because it stops them.

## Slices

### 2a Ledger core (no HTTP)

- **V2:** `journal_entries`, `postings` (composite FK on `(account_id, currency)`), deferred
  balanced-entry constraint trigger (plus a twin on `journal_entries` for entries with no postings),
  append-only triggers for UPDATE, DELETE and TRUNCATE.
- **V3:** seed the SYSTEM funding account (fixed id, `allow_negative`).
- **Code (`ledger`):** `LedgerService.post(entry)` checks ≥ 2 postings, sum 0 and a single currency,
  locks accounts in id order and updates balances. Internal `FundingService`. `InvariantChecker`
  (SQL for I5–I9).
- **Specs first:**
  - `LedgerGuardsSpec`: direct SQL attacks. An unbalanced entry fails at commit; an entry without
    postings fails; posting currency ≠ account currency fails; UPDATE, DELETE and TRUNCATE on both
    ledger tables fail.
  - `LedgerServiceSpec`: funding posts one balanced entry; the SYSTEM account goes negative; a
    customer account can't; balance overflow is rejected.
  - `InvariantCheckerSpec`: a clean ledger passes; a balance corrupted by direct UPDATE is reported as I7.
- **Done (2026-10-29).** Added during the slice: a service-level opposite-direction concurrency
  feature (lock ordering, mutation-checked), detection features for I5, I6, I8 and I9, and a fix
  for `migrate` failing when `DB_POOL_SIZE=1`.

### 2b Authentication and account reads

- **Code:** API keys per ADR 0008; a Spark before-filter on `/v1/*` with a `Caller` principal;
  RFC 9457 problem+json mapping; CLI `create-caller --role --name` that prints the key once.
- **Endpoints:** `GET /v1/accounts/{accountId}`, `GET /v1/accounts/{accountId}/entries` (keyset
  pagination, newest first).
- **Specs first:**
  - `AuthApiSpec`: missing, malformed or unknown key → 401 problem+json; health stays public; the key never appears in logs.
  - `AccountApiSpec`: the owner sees the balance; another customer gets 404, not 403; an unknown id
    gets 404; pages stay stable while new postings arrive; error bodies carry the contract's Problem fields.
- **Done (2026-10-30).** Added during the slice: `ErrorResponsesApiSpec` (problem+json 404s, generic
  500 with a correlation id), `CreateCallerCommandSpec`, `ContractSchemas` (response bodies checked
  against the contract's schemas), and logs moved to stderr after the smoke test showed them mixed
  into `create-caller`'s stdout.

### 2c Idempotency and the first POST endpoints (retires R3)

- **V4:** `idempotency_keys`.
- **Code:** canonical request fingerprint (SHA-256); `TxRunner` with retry classification
  (`40P01`, `40001`; `55P03` handled by the step it occurs in); the single-transaction protocol (ADR 0007).
- **Endpoints:** `POST /v1/accounts`, `POST /v1/admin/fundings` (operators only; customers get 403).
- **Specs first, the R3 proof before anything else:**
  - `IdempotencyProtocolSpec` (real PostgreSQL, two connections): the second insert of a key
    **blocks** until the first transaction commits, then sees the committed row; if the first rolls
    back, the second proceeds; with `lock_timeout` the second gets the in-progress error.
  - `IdempotencyApiSpec`: same key and payload → identical status and body plus
    `Idempotent-Replayed: true`; different payload → 422; keys are separate per caller and per
    operation; validation errors aren't stored; missing header → 400.
- **Done (2026-10-30), R3 retired.** Added during the slice: `TxRunnerSpec` (including a real
  deadlock), `RequestFingerprintSpec`, `RequestBodySpec`, `AccountCreationApiSpec`,
  `FundingApiSpec`, and API-level concurrency and mid-flight features in `IdempotencyApiSpec`.
  Per-operation scope is proven at the SQL level only: no role can call two POST operations yet.

### 2d Transfers and concurrency (needs D4; retires R4)

- **V5:** `payments`, `outbox_events` (written in the same transaction; the relay comes in Phase 3).
- **Code:** `TransferService` (flow in DESIGN §3.2), `PaymentStateMachine`, an approve-all fraud stub
  (Phase 4 replaces it), and a `FaultInjector` with named points that is only active in tests.
- **Endpoints:** `POST /v1/transfers`, `GET /v1/transfers/{transferId}`.
- **Specs first:**
  - `TransferApiSpec`: happy path; insufficient funds (response shape per D4); amounts 0, −1,
    MAX+1, 1.5, "10" and 2^63 → 400 with nothing stored; same source and destination → 422;
    currency mismatch → 422; source not owned → 404; frozen account → `DECLINED(ACCOUNT_NOT_ACTIVE)`.
  - `TransferAtomicitySpec`: a fault after the postings, before commit, leaves no payment, entry,
    posting, balance change, idempotency row or outbox row; a retry with the same key then succeeds exactly once.
  - `IdempotencyConcurrencySpec`: 50 threads, same key and payload → exactly one payment and entry; all responses identical.
  - `OverdraftConcurrencySpec`: balance 100, 20 concurrent transfers of 10 to different
    destinations → 10 POSTED, 10 DECLINED, zero errors, final balance 0.
  - `DeadlockSpec`: concurrent A→B and B→A loops → no deadlock error reaches a client; invariants hold.
  - `HotAccountSpec`: 100 concurrent transfers into one account → all posted, balance correct.
- **Done (2026-10-30), D4 = 201 + DECLINED (ADR 0009).** Changes from this plan: the four concurrency
  specs became one `TransferConcurrencySpec` (shared app and database); `PaymentStatusSpec` and
  `DatabaseSpec` (error details out of logs) were added. **Deferred:** the approve-all fraud stub, to
  Phase 4: a call that always approves would be untested dead code, so `TransferService` marks the
  slot instead (after the idempotency key, before account locks). A true account-currency mismatch
  can't be built in single-currency scope; an unsupported currency is a 400, and the composite
  foreign keys on `payments` enforce I3.

### 2e Reversal (D7 decided, ADR 0010)

- **Endpoint:** `POST /v1/transfers/{transferId}/reversal` (operators; idempotent).
- **Specs first:** `ReversalSpec`. The reversal is a new balancing entry that references the
  original, and the transfer goes POSTED → REVERSED. A second reversal or reversing a DECLINED
  transfer → 409 `INVALID_STATE_TRANSITION`. A destination that already spent the funds → 422, nothing written (ADR 0010). Reversals may post to
  FROZEN accounts but never CLOSED ones, which needs a ledger change (ordinary transfers still require ACTIVE).
  Two concurrent reversals → exactly one succeeds.
- **Done (2026-11-03).** Added during the slice: V6 `payments.reversed_by` (who reversed, T7), an I9
  check that every reversal exactly mirrors its original, ten concurrent reversals instead of two, a
  reversal racing the receiver's own spending, and a reversal fault point in `TransferAtomicitySpec`.

### 2f Model-based sequences (I6, I7)

- `LedgerModelSpec`: a seeded generator produces operations (fundings, valid and invalid
  transfers, duplicate retries, reversals) and applies them to the real service and to a small
  in-memory model. After each sequence the balances must match the model and I5–I9 must hold.
  At least 50 seeds × 200 operations; a failing seed is printed so the run can be replayed exactly.
- **Done (2026-11-03).** Runs through the real API rather than the services, so routes, validation and
  idempotency are part of what agrees with the model. Every answer is checked as it happens, not only the
  final balances. A coverage feature requires every kind of answer to occur. A mutation showed uniform
  amounts almost never hit the overdraft edge, so the generator now aims at it on purpose, and
  `TransferApiSpec` gained a deterministic boundary feature.

## Phase 2 exit criteria

- Every Phase 2 item in DESIGN §12 is checked, with evidence in `PROGRESS.md`.
- `docs/test-matrix.md` Phase 2 rows are VERIFIED.
- No Phase 2 operation in `openapi.yaml` is still `x-status: planned`.
- The threat model status is updated for T1, T2, T4, T5, T6, T8, T10 and T18.

## Not in Phase 2

The outbox relay (Phase 3), real fraud rules and Redis velocity (Phase 4), metrics, JSON logs and
database roles (Phase 5), and benchmarks (Phase 6).
