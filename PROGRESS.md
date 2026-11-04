# Progress log

Labels: **PLANNED** (designed, no code) · **IMPLEMENTED** (code exists) · **VERIFIED** (run here, output captured below).

## Feature status

| Area | Status |
|---|---|
| Design proposal (`docs/DESIGN.md`) | D1–D4, D7, D8, D9, D11 decided; D5, D6 and D10 open |
| Phase 2 plan (`docs/phase-2-plan.md`) | Approved by the student (2026-10-29) |
| Phase 1: skeleton, PostgreSQL 18, health, Money, config, OpenAPI v0, threat model, ADRs, test matrix | **VERIFIED** (2026-10-29) |
| Phase 2, Slice 2a: ledger core (journal, guards, LedgerService, InvariantChecker) | **VERIFIED** (2026-10-29) |
| Phase 2, Slice 2b: API keys, `create-caller`, problem+json errors, account balance and statement reads | **VERIFIED** (2026-10-30) |
| Phase 2, Slice 2c: idempotency protocol (R3 retired), `TxRunner`, `POST /v1/accounts`, `POST /v1/admin/fundings` | **VERIFIED** (2026-10-30) |
| Phase 2, Slice 2d: transfers (D4 = 201 + DECLINED), outbox writes, fault injection, API concurrency (R4) | **VERIFIED** (2026-10-30) |
| Phase 2, Slice 2e: reversals (D7, ADR 0010), I9 mirror check, `reversed_by` | **VERIFIED** (2026-11-03) |
| Phase 2, Slice 2f: model-based runs (50 seeds × 200 operations through the API) | **VERIFIED** (2026-11-03) |
| **Phase 2 overall** | **VERIFIED**; waiting for the student's review |
| Phase 3: outbox, recovery demos | PLANNED |
| Phase 4: fraud rules, Redis velocity | PLANNED |
| Phase 5: ops and security | PLANNED |
| Phase 6: benchmarks, Kubernetes | PLANNED |

### Phase 1 acceptance (DESIGN §12)

- [x] `./gradlew build` on the JDK 21 toolchain: 62 specs, 0 failed, 0 skipped
- [x] Every `org.eclipse.jetty*` module at 9.4.58 (dependency report)
- [x] Compose: PostgreSQL 18.6 and Redis 8.10.3 healthy
- [x] Flyway migrates a clean database; re-run is a no-op (`MigrationSpec`, smoke test)
- [x] jOOQ classes generated from the migrated schema and used by queries (`MigrationSpec`)
- [x] `/health/live` 200; `/health/ready` 200, 503 within 2 s without PG, 200 with `redis: DOWN` (`HealthApiSpec`)
- [x] `Money` / `TransferAmount` / `SupportedCurrencies` unit specs
- [x] Docs: OpenAPI v0 (drift-checked), threat model, ADRs 0001–0007, README, test matrix

### Phase 2 acceptance (DESIGN §12)

- [x] Concurrent duplicates: 50 threads → 1 payment, 1 entry, identical responses (`TransferConcurrencySpec`)
- [x] No overdraft: 20 × 10 on a balance of 100 → 10 POSTED, 10 DECLINED, 0 errors, balance 0 (`TransferConcurrencySpec`)
- [x] No deadlock leaks: opposite-direction transfers all post (`TransferConcurrencySpec`)
- [x] Same key, different payload → 422, original unchanged (`IdempotencyApiSpec`)
- [x] Invalid amounts (0, −1, max + 1, 1.5, "10", 2^63) → 400, nothing persisted (`TransferApiSpec`, `FundingApiSpec`)
- [x] Rollback: fault after postings, before commit → nothing persisted; the same key then posts once (`TransferAtomicitySpec`)
- [x] DB guards against direct SQL (`LedgerGuardsSpec`, `MigrationSpec`)
- [x] Property runs: 50 seeds × 200 operations; model == database; I5–I9 hold (`LedgerModelSpec`)
- [x] Reversal: mirroring entry, POSTED → REVERSED, a second reversal rejected (`ReversalApiSpec`)

## Phase 2 review (for the student)

Phase 2 is complete and every acceptance item has evidence. Before Phase 3, please go through:

1. **Your rationale, in your own words**, for D1 (Spark), D2 (single-transaction idempotency), D3 (ordered
   row locks), D4 (201 + DECLINED), D7 (decline uncovered reversals; FROZEN allowed), D9 (auth in Phase 2)
   and D11 (PostgreSQL 18). D8 is recorded. Each ADR ends with two explain-back questions.
2. **Accept or reject** ADR 0002 (jOOQ codegen from a real PostgreSQL) and ADR 0004 (money as `long`
   minor units); both are still Proposed. For ADR 0004 you proposed to accept, pending final confirmation.
3. **Confirm or change Claude's choices** made inside the approved plan. Each slice's log entry lists them;
   the ones with the widest effect:
   - 404 (not 400) for ids that aren't UUIDs; operators can't read customer accounts.
   - Idempotency stores only completed operations, and stores the response as text so replays are exact.
   - When several decline reasons apply, the source account's problem is reported.
   - The reversing operator is recorded on the payment (`reversed_by`, V6).
   - No jqwik: a seeded generator with step-level checks instead (DESIGN §11).
4. **Fill in the "can explain?" column** of the authorship table below.
5. **Decide D10** (Phase 3 transport: Redis Stream or an in-process queue) before Phase 3 starts.

## Current known gaps

- `Main` itself (command dispatch, connection setup) has no specs; each change is smoke-tested against
  Compose. Slice 2a found and fixed a real bug there that way (`migrate` with `DB_POOL_SIZE=1`).
  `create-caller`'s logic is covered by `CreateCallerCommandSpec`.
- `create-caller` prints the new key on stdout (logs go to stderr). Don't run it where stdout is
  collected as logs, such as a Kubernetes Job. A `--key-file` option is planned for Phase 5 (T24).
- Statements don't return `Entry.transferId` yet (the ledger can't read payments; the API would need a
  second lookup).
- `InvariantChecker` covers the ledger only. Nothing yet re-checks that a POSTED payment's entry moves
  exactly its amount between its two accounts (the DB only ensures the entry exists). Add with the
  Phase 3 recovery checks.
- A customer can learn whether an account id exists by sending a transfer to it (T25, accepted: ids are
  random UUIDs).
- No fraud screening yet; the slot is marked in `TransferService` (Phase 4).
- If the connection pool is exhausted (Hikari waits 1 s), the request fails with a generic 500 instead
  of `503 DEPENDENCY_UNAVAILABLE`. Duplicates hold a connection while they wait (ADR 0007), so a burst of
  duplicates larger than the pool can reach this. Fix in 2d or Phase 5.
- Every funding locks the SYSTEM account first (its id sorts lowest), so fundings serialize on that
  row. Acceptable for an operator action; measure in Phase 6.
- `LedgerModelSpec` runs one operation at a time; concurrency is covered by the concurrency specs, not
  the model. A seed replays exactly only with the same generator code. The spec adds about 40 s to the suite.
- Idempotency snapshots record a 7-day expiry, but nothing purges them yet (Phase 5). Request bodies
  have no size limit yet (Phase 5, T16). `TxRunner` retries aren't logged or counted (metrics in Phase 5).
- Account reads are owner-only, operators included, as the contract says. Revisit if operator tooling
  needs to read balances.
- Only `Exception`s reach the problem+json handler. An `Error` (for example `StackOverflowError`)
  would get Jetty's default error page, which may include a stack trace. Not checked yet; Phase 5.
- Concurrency is proven at the service level only so far (two threads per direction on two
  accounts). The API-level specs (duplicates, overdraft races, hot account) come in Slice 2d.
- A reversal the receiver can't cover leaves no record anywhere (ADR 0010): tracking such cases needs a
  dispute or case record, not in scope.
- Fundings record their operator only in the 7-day idempotency snapshot; reversals record theirs on the
  payment (`reversed_by`). A full audit trail is Phase 5 (T7).
- Each entry costs three balance-trigger checks at commit (one per posting, one for the entry). Fine
  at this scale; measure in Phase 6.
- The dev-only DB password default in `AppConfig` must become required outside local dev (Phase 5).
- Image tags float (`postgres:18-alpine`, `redis:8-alpine`); tested digests are recorded below. Pin by digest before Phase 6.
- The OpenAPI contract is checked for route drift and basic structure but not linted as a whole.
  Account-read and error responses are checked against its schemas by `ContractSchemas`, a small
  validator for the keywords the contract uses; health responses aren't.
- `HttpApiStartupSpec` and `OpenApiContractSpec` build `HttpApi` with null dependencies because they
  never call a route. If `HttpApi` grows further, give it narrower dependencies.
- Its LAN-address check skips automatically on machines without a non-loopback IPv4 (some CI containers).
- Lettuce logs a macOS-only Netty DNS warning (`MacOSDnsServerAddressStreamProvider`). Harmless on Linux/containers.
- The old PostgreSQL 17 volume `payments-lab_pgdata` still exists (synthetic data, unreadable by 18). Remove with `docker volume rm payments-lab_pgdata` if unwanted.

## Log

### 2026-11-04 · Published to GitHub (student's request)

The student asked to push everything to a public GitHub repository, named in the format they usually use.

- **Name:** `ledger-sentinel-ai`. Claude read the format from the student's account: their five newest
  repositories of their own use lowercase words joined by hyphens, and all five of their own repositories
  in their local projects folder have the same words as the folder, here `ledger_sentinel_ai`. The code
  keeps the working name `payments-lab`.
- **Checked before publishing:** 142 files, with no build output, logs or local settings. A pattern scan
  found no tokens, private keys or API keys. The only password is the documented local-only Compose
  default. No personal email address or home-directory path.
- **Corrected for public readers:** the README's first sentence described the outbox and fraud screening
  as if they were finished. It now separates what is verified from what is planned, and says nothing uses
  AI or ML yet, because the name could suggest otherwise.
- No application code or spec changed.

### 2026-11-03 · Phase 2 review, round 1: money and the ledger's shape (student)

- The student wrote their own rationale for D8 and their position on ADR 0004 (accept, pending final
  confirmation). Both are copied word for word into the decision log; ADR 0004 stays Proposed until confirmed.
- Their debit/credit explanation of a transfer is recorded under the matching question in ADR 0005.

### 2026-11-03 · Slice 2f: model-based runs (AI-assisted implementation)

The student asked to start Slice 2f, the last slice of Phase 2.

**What was built (test code only; no production code changed)**
- `LedgerModel`: the expected behaviour in plain maps (balances, statuses, owners, payments, stored keys),
  with the rules written independently of the code: role, validation, idempotency, then business rules.
- `ModelRun`: a world of one operator and three customers with two accounts each, then seeded random
  operations through the real API: fundings, valid and invalid transfers, exact retries, reused keys (an
  operator's key also moves between fundings and reversals), reversals, account status changes (by SQL) and
  reads. Every answer is checked as it arrives; a disagreement names the seed, the step, the expected and
  actual answers, the last 12 steps and the replay command. Every random choice comes from the seed and the
  model's state, never from server-generated ids, so a seed replays exactly.
- `LedgerModelSpec`: seeds 1–50 × 200 operations. After each seed: balances, statuses, payment statuses,
  new rows (entries, events, payments, keys) and the SYSTEM account must match the model, and
  `InvariantChecker` must report nothing. A coverage feature requires 21 kinds of answer to occur.
  Replay: `-PmodelSeeds=17`; explore: `-PmodelRandomSeeds=200` (wired in `build.gradle.kts`).
- Also: `IdempotencyApiSpec` feature "a key belongs to one operation", which closes the 2c gap now that
  operators have two POST operations; `TransferApiSpec` boundary feature.

**Red evidence.** A model test of existing behaviour can't start red: its first run agreed with the system
(50 seeds, 10,000 operations, 41 s). So the red step is three injected bugs, run against **all**
integration specs (each file restored byte-identical, confirmed with `cmp`):
- Swapped the order of a transfer's postings, so the destination is checked first → **only the model
  noticed**: 38 of 50 seeds; every other spec passed. First failure: seed 1, step 102, expected DECLINED
  (INSUFFICIENT_FUNDS) but got ACCOUNT_NOT_ACTIVE; the trace showed the destination frozen at step 95.
- Dropped the operation from the idempotency key's scope → 32 seeds (seed 1, step 28: an operator's funding
  key reused for a reversal got 422 "key reused" instead of running), plus the new explicit feature.
- An off-by-one in the overdraft check (`< 0` → `< -1`) → **only 1 of 50 seeds** noticed, and no other
  spec at all: uniform amounts almost never land exactly on the edge. At seed 26, step 168, an account
  holding 9.70 sent 9.71; the database CHECK refused the overdraft and the client got a 500.
  Fix: the generator now aims at the boundary on purpose (exactly the balance; one cent more), and
  `TransferApiSpec` gained a deterministic boundary feature. Rerun: 49 of 50 seeds plus that feature caught it.

**Coverage of the default seeds** (final generator): every one of the 21 required kinds occurred, for
example 141 exact-balance transfers posted and 167 one-cent-over transfers declined, alongside hundreds of
each of POSTED, both decline reasons, reversals, 409s, replays and reused keys. The full tally is printed
in the spec's output.

**Choices Claude made** (for the student to confirm or change):
- Through the API rather than the services, so routes, validation and idempotency are part of what agrees
  with the model.
- Fixed seeds 1–50 by default, so CI is repeatable; random seeds on demand.
- No jqwik (no new dependency); the trade-off is no automatic shrinking of a failing sequence.
- Status changes happen by SQL, because there's no API for them.

**Final verification (Phase 2 exit):** `./gradlew clean build --warning-mode all` → BUILD SUCCESSFUL in
1 min 20 s. `test` 198 run / 0 failed / 0 skipped; `integrationTest` 347 run / 0 failed / 0 skipped. No
Gradle deprecation warnings. The only operations still planned in `openapi.yaml` are reviews (Phase 4) and
metrics (Phase 5).

**What this proves:** across 10,000 random operations, including retries, reused keys, reversals and
frozen or closed accounts, every API answer matched an independent model, and the ledger's balances and
invariants agreed with it afterwards. The test can be trusted because it was shown to catch injected bugs,
including one that no other spec catches.

### 2026-11-03 · Slice 2e: reversals (AI-assisted implementation)

The student asked to start Slice 2e, built to their D7 decision (ADR 0010).

**Specs first**
- Unit: `EntryKindSpec` (which account statuses each kind of entry may touch).
- Integration: `ReversalApiSpec` (new, 18 runs); 5 reversal features in `LedgerServiceSpec`; an I9
  mirror feature in `InvariantCheckerSpec`; a reversal fault feature in `TransferAtomicitySpec`.
- Contract: the reversal operation lost `x-status: planned`, gained a 400 response and the
  `INSUFFICIENT_FUNDS` code, and documents that it takes no body.

**Red run** (`./gradlew test integrationTest --continue`; every earlier spec stayed green):
- Unit: 198 run / 9 failed, the new `EntryKindSpec` runs, on the stub.
- Integration: 294 run / 26 failed. The new `LedgerServiceSpec` features hit the stub (5); every
  `ReversalApiSpec` run got 404 from the missing route (18); the reversal fault never fired;
  `OpenApiContractSpec` reported the documented reversal as missing.
- **The checker gap was real:** a balanced reversal for the wrong amount, written with plain SQL that
  keeps every balance consistent, produced no violation at all.
- No vacuous passes this time: the 404 feature carries a control from the start.

**Green run**
- First attempt: 1 failure, in my test, not the product. Ten threads read one parsed response at once;
  Groovy's `JsonSlurper` builds its maps lazily and isn't thread-safe, so the transfer id came back
  `null` and one thread hit an NPE inside the map. `ApiClient.json` now parses eagerly
  (`JsonSlurperClassic`).
- Then: `test` 198 run / 0 failed; `integrationTest` 294 run / 0 failed / 0 skipped. Ten concurrent
  reversals: one 201 and nine 409 in 0.07 s. The race between a reversal and the receiver's own
  spending: in each of 20 rounds exactly one got the money; both orders occurred (the spend won 19 times).

**Mutation checks** (each file restored byte-identical, confirmed with `cmp`)
- Let every kind of entry post to FROZEN accounts → only "a posting to an account that isn't ACTIVE is
  refused" failed: transfers still require ACTIVE.
- Removed the payment row lock → only the ten-reversal feature failed. Money still moved once, because
  the ledger's balance check refused the nine losers, but they got 422 `INSUFFICIENT_FUNDS` instead of
  409. The lock is what makes the answer right; the balance check and `UNIQUE (reverses_id)` are backstops.
- Skipped the state-machine check → 3 features failed (DECLINED reversal, second reversal, concurrency).

**Smoke test** (Compose, PostgreSQL 18.6, via `installDist`)
- `migrate` applied V6 ("now at version 6").
- Reversal → 201 REVERSED; same key → replayed; another key → 409 `INVALID_STATE_TRANSITION`; customer →
  403; reversal after the receiver spent the money → 422 `INSUFFICIENT_FUNDS`.
- Alice's statement shows the REVERSAL line; balances add up (alice 50.00, bob 20.00).
- Database: payments `REVERSED by the operator, POSTED`; outbox `transfer.posted, transfer.reversed`;
  the I9 mirror query finds 0 violations across everything stored in Compose; I6/I7: 0.
- SIGTERM → 143; no key in the server's output. Compose stopped afterwards.

**Choices Claude made inside the approved plan** (for the student to confirm or change):
- The reversal is built from the original entry's postings (`LedgerService.reverse`), not from the
  payment's fields, so it can only ever be an exact mirror.
- V6 records the reversing operator on the payment (`reversed_by`). The plan didn't ask for it, but who
  moved money back belongs on the record (T7).
- A reversal takes no body: empty or `{}`; anything else (such as an amount) is refused, not ignored.
- 409 for "not POSTED any more" is checked under the payment's row lock, before touching the ledger.

**Final verification:** `./gradlew clean build --warning-mode all` → BUILD SUCCESSFUL in 41 s.
`test` 198 run / 0 failed / 0 skipped; `integrationTest` 294 run / 0 failed / 0 skipped. No Gradle
deprecation warnings.

**What this proves:** a mistaken transfer can be undone without editing history, exactly once, by an
operator only, and never by overdrawing the receiver. Concurrent reversals and a reversal racing the
receiver's spending always end with one clear winner. The checker now catches a "correction" that
doesn't exactly undo the original.

### 2026-11-03 · Decision D7 (student)

- After Claude explained the options (decline, allow a negative balance, platform absorbs the loss) and
  the frozen-account question, the student chose through a prompt: **decline** a reversal the receiver
  can't cover, and **allow reversals into FROZEN accounts** (never CLOSED). Both match Claude's proposals.
  No note was attached, so the rationale is pending.
- Written up as ADR 0010 (Accepted). The `openapi.yaml` reversal description now points to it. No code changed.

### 2026-10-30 · Slice 2d: transfers and concurrency (AI-assisted implementation)

**Decision D4 (student):** chose "201 + DECLINED" from a prompt; no reasoning attached, so the
rationale is pending. Recorded as ADR 0009, Accepted. Its key consequence: a decline is a stored
outcome, so the same key replays DECLINED even after a top-up, and trying again needs a new key.

**Specs first** (4 new spec classes, 2 extended)
- Unit: `PaymentStatusSpec`; `RequestBodySpec` gained optional text (`reference`).
- Integration: `TransferApiSpec`, `TransferAtomicitySpec`, `TransferConcurrencySpec`, `DatabaseSpec`.
  The plan's four concurrency specs became one class with four features (shared app and database).
- Test support: `ArmedFaults` (arm a named point; the next pass throws once). `App.start(config, faults)`
  wires it in; production uses `FaultInjector.NONE`.
- Contract: the two transfer operations lost `x-status: planned`; `createTransfer` gained 403.

**Red run** (`./gradlew test integrationTest --continue`):
- Unit: 189 run / 30 failed: `PaymentStatusSpec` 21/21 and the 9 new `RequestBodySpec` runs, all on stubs.
- Integration: 269 run / 27 failed. 13 hit `relation "payments" does not exist` (no V5 yet), the others
  got 404s (no routes); `DatabaseSpec`'s real feature failed because the message still quoted the row
  ("Failing row contains"), while its control passed, as a control should; `OpenApiContractSpec`
  reported the two transfer operations as documented but missing.
- **Found another vacuous pass:** "the creator and operators can read a transfer" passed with no
  transfer route: the POST and every GET returned the same 404 body, which compared equal. The feature
  now requires a 201 first; rerun: `TransferApiSpec` 21 / 21 failed.

**Green run:** first attempt. `test` 189 run / 0 failed; `integrationTest` 269 run / 0 failed / 0 skipped.
- Timings: 50 duplicates 0.20 s; 20 racing debits 0.19 s; 100 opposite-direction transfers 0.58 s;
  100 concurrent transfers into one account 0.68 s. A deadlock costs at least PostgreSQL's 1 s
  `deadlock_timeout`, so the opposite-direction run had none, rather than retried ones.
- The only ERROR lines are the deliberate ones (2b's 500 and the injected fault). No key and no
  reference in any captured log.

**Mutation checks** (each file restored byte-identical, confirmed with `cmp`)
- Checked only that the source exists, not that the caller owns it → only "a source owned by someone
  else" failed.
- Let `InsufficientFundsException` escape instead of recording DECLINED → the race, the decline and the
  "final for its key" features failed (3 of 26).
- Removed the outbox write → the happy path, the decline and the atomicity features failed.

**Smoke test** (Compose, PostgreSQL 18.6, via `installDist`)
- `migrate` applied V5 ("now at version 5"). Created an operator and two customers; opened two accounts
  through the API; funded Alice 50.00.
- Transfer 20.00 → 201 POSTED (with its reference); same key → replayed POSTED; 99.00 → 201
  DECLINED(INSUFFICIENT_FUNDS); Bob using Alice's account → 404. GET as Alice, operator, Bob → 200, 200, 404.
  Balances 30.00 and 20.00.
- Database: payments `POSTED entry, DECLINED(INSUFFICIENT_FUNDS) no-entry`; outbox `transfer.posted`
  and `transfer.declined`, both PENDING, neither payload with a reference. I6/I7: 0 violations.
- SIGTERM → 143; no key or reference in the server's output. Compose stopped afterwards.

**Conventions Claude chose inside the approved plan** (for the student to confirm or change):
- Transfers are customer-only (operators get 403). A transfer is readable by its creator and by operators.
- Not owning the source and an unknown destination give the same 404 (T25 documents what a destination
  probe still reveals).
- Ledger order decides which reason is reported when several apply: the source is checked first.
- The outbox event leaves out the free-text reference. Event types `transfer.posted` and `transfer.declined`.
- PgJDBC `logServerErrorDetail=false` on the app pool, so constraint errors can't quote rows into logs.
- Deferred to Phase 4: the approve-all fraud stub (it would be untested dead code).
- `Entry.transferId` still isn't returned in statements.

**Final verification:** `./gradlew clean build --warning-mode all` → BUILD SUCCESSFUL in 39 s.
`test` 189 run / 0 failed / 0 skipped; `integrationTest` 269 run / 0 failed / 0 skipped. No Gradle
deprecation warnings.

**What this proves:** a transfer either commits completely (payment, entry, postings, balances, outbox
event and idempotency record) or leaves nothing. Concurrent debits can't overdraw an account, and the
losers get a clean DECLINED rather than an error. Duplicates move money once. Opposite transfers don't
deadlock. Nobody can move money out of an account they don't own.

### 2026-10-30 · Slice 2c: idempotency, account opening, fundings (AI-assisted implementation)

The student asked to start Slice 2c.

**R3 first** (the plan said to stop if PostgreSQL behaved differently from ADR 0007's assumption):
- `IdempotencyProtocolSpec`: plain SQL on two connections, no application code.
- Red: 5 run / 5 failed, all with `relation "idempotency_keys" does not exist`.
- Wrote V4 (`idempotency_keys`, plus a deferred constraint trigger that refuses to commit a key row
  without its response). Green: 5/5; `MigrationSpec` still 13/13.
- What PostgreSQL did, as assumed:
  - The duplicate INSERT waited; `pg_stat_activity` showed `wait_event_type = Lock`. After the first
    transaction committed, the duplicate inserted 0 rows and its next statement read the committed response.
  - After a rollback, the duplicate inserted the row and owned the key.
  - With `lock_timeout = 200ms`, it failed with 55P03 after about 0.2 s.
  - The same key text for another caller or another operation didn't wait.
  - Committing a key row without a response failed with 23514.
- **R3 retired.** No surprise, so the slice continued.

**Specs first** (6 more spec classes)
- Unit: `RequestFingerprintSpec`, `RequestBodySpec`.
- Integration: `TxRunnerSpec`, `IdempotencyApiSpec`, `AccountCreationApiSpec`, `FundingApiSpec`.
- New `Concurrency` helper (extracted from `LedgerServiceSpec`, which now uses it).
- Contract: removed `x-status: planned` from the two POSTs; added the `ACCOUNT_NOT_ACTIVE` code.

**Red run** (`./gradlew test integrationTest --continue`):
- Unit: 156 run / 41 failed, all on the stubs. `RequestFingerprintSpec` reported 5 rather than 8 runs
  because its data-driven feature failed while building its data.
- Integration: 241 run / 47 failed. The API specs got 404s (no routes yet); `TxRunnerSpec` hit its stub;
  3 mid-flight features hit the fingerprint stub; `OpenApiContractSpec` reported the two documented
  POSTs as missing.
- **Found a vacuous pass:** "funding an account that doesn't exist → 404" passed with no route at all,
  because Spark's catch-all 404 is deliberately the same body. Added a control (a successful funding
  through the same route) and reran: `FundingApiSpec` 14 / 14 failed.

**Green run:** the first attempt didn't compile (rewriting `ApiException` had dropped `internal()`).
After restoring it: `test` 159 run / 0 failed; `integrationTest` 241 run / 0 failed / 0 skipped.
- Timings: the 409 came after 2.03 s (`lock_timeout`); the real deadlock resolved in 1.04 s (PostgreSQL's
  1 s `deadlock_timeout`, then one retry); 20 concurrent duplicates took 34 ms.
- No key in any log or report; the only ERROR line is 2b's deliberate 500.

**Mutation checks** (each file restored byte-identical, confirmed with `cmp`)
- Skipped the payload-hash comparison on replay → only "different payload → 422" failed.
- Removed `ON CONFLICT DO NOTHING` from the key insert → 7 of 16 `IdempotencyApiSpec` features failed:
  every kind of repeat (sequential, rewritten, concurrent, mid-flight).
- Removed the operator role check from fundings → only "a customer can't fund" failed.

**Smoke test** (Compose, PostgreSQL 18.6, via `installDist`)
- `migrate` applied V4 ("now at version 4"). Created an operator and a customer with `create-caller`.
- Customer `POST /v1/accounts` → 201. Operator funded 25.00 → 201. Same key with the fields reordered →
  `Idempotent-Replayed: true` and a byte-identical body. Same key with 30.00 → 422
  `IDEMPOTENCY_KEY_REUSED`. Customer funding → 403. No key header → 400 `IDEMPOTENCY_KEY_MISSING`.
- Balance 2500; statement shows one FUNDING of 2500.
- `idempotency_keys` held only the two completed operations (201s, kept 7 days); the refusals left nothing.
- I6/I7 queries: 0 violations. SIGTERM → 143. No key in the server's output. Compose stopped afterwards.

**Conventions Claude chose inside the approved plan** (for the student to confirm or change):
- `response_body` is `text`, not the DESIGN draft's `jsonb`, so replays are byte-for-byte (jsonb reorders keys).
- Only completed operations are stored. Every refusal rolls back, key included: after an account is
  unfrozen, the same key funds it.
- Funding a frozen or closed account → 422 `ACCOUNT_NOT_ACTIVE` (a new code). Funding the SYSTEM account
  → 422 `INVALID_ACCOUNT_COMBINATION`. An unknown account → 404 (operators aren't owner-scoped).
- Operators can't open accounts (403); customers can't fund (403).
- A missing `Idempotency-Key` has its own code; a malformed one is listed with the body's field errors.
- Operation names `account.create` and `funding.create`; the operation is part of the fingerprint too.
- Fingerprint = SHA-256 over 4-byte length + UTF-8 bytes per part (length −1 for absent).

**Final verification:** `./gradlew clean build --warning-mode all` → BUILD SUCCESSFUL in 34 s.
`test` 159 run / 0 failed / 0 skipped; `integrationTest` 241 run / 0 failed / 0 skipped. No Gradle
deprecation warnings.

**What this proves:** a POST runs at most once per caller and key, even with 20 copies arriving
together or a copy arriving mid-flight. A repeat gets the exact original response; a different request
under the same key is refused; nothing half-finished is ever visible; and a failure leaves the key free.
Deadlocks and lock timeouts are retried a bounded number of times. Only operators move money in, and
unexpected JSON fields are refused rather than ignored.

### 2026-10-30 · Slice 2b: API keys and account reads (AI-assisted implementation)

The student asked to start Slice 2b. Before that, the project moved out of the app's temporary
session folder into its own folder; a clean build there was green before any change (52 + 51 specs).

**Specs first** (8 new spec classes plus a self-test for new test support)
- Unit: `ApiKeysSpec`, `BearerTokenSpec`, `EntryCursorSpec`, `CreateCallerOptionsSpec`.
- Integration, starting the whole app on a template-copy database: `AuthApiSpec`, `AccountApiSpec`,
  `ErrorResponsesApiSpec`, `CreateCallerCommandSpec`.
- Test support: `CallerFixtures` (callers with real keys, inserted and hashed in test code so the API
  specs don't rely on what they test), `LogCapture` (Logback list appender that can lower our loggers
  to TRACE), `ContractSchemas` (checks response bodies against `openapi.yaml` and treats undeclared
  fields as violations) with `ContractSchemasSpec`, and `ApiClient`.
- Contract: removed `x-status: planned` from the two account reads before implementing them.

**Red run** (`./gradlew test integrationTest --continue`; all 103 earlier specs stayed green):
- Unit: 115 run / 63 failed, exactly the 63 new runs, all on the stubs' `UnsupportedOperationException`.
- Integration: 189 run / 125 failed: 124 of the 138 new runs, plus `OpenApiContractSpec`'s
  "documented but not implemented: GET /v1/accounts/{accountId}, GET …/entries".
  - `AuthApiSpec` 88 / 85: 81 of the 401 checks got Spark's default 404 (no filter, no routes);
    valid keys got 404; the log feature counted 0 "authentication failed" lines.
  - `AccountApiSpec` 26 / 26 (Spark's HTML 404 instead of JSON); `ErrorResponsesApiSpec` 3 / 3
    (`text/html` 404s); `CreateCallerCommandSpec` 10 / 10 (stubs).
  - Passed as expected: `ContractSchemasSpec` 11/11 (test support, including 9 cases that must be
    rejected), the two "health stays public" runs, and the sanity check on the coverage list.

**Green run:** first attempt. `test` 115 run / 0 failed; `integrationTest` 189 run / 0 failed / 0 skipped.
No API key appears in any captured log or report; the only ERROR line is the deliberate 500.

**Mutation checks** (each file restored byte-identical, confirmed with `cmp`)
- Removed the ownership condition from `AccountQueries.findOwned` → exactly the 3 BOLA rows failed
  (another customer, an operator, the SYSTEM account); the other 23 passed.
- Narrowed the auth filter from `/v1/*` to `/v1/accounts/:accountId` → 72 failures: the 8 operations
  it no longer covered × 9 credential variants.
- Changed the page condition from `<` to `<=` → exactly the 3 pagination features failed.

**Smoke test** (Compose, PostgreSQL 18.6, via `installDist`)
- **Found:** Logback's console appender wrote to stdout, so `create-caller`'s stdout mixed log lines
  with the key. No log line contained the key, but a script reading stdout picked up the caller id
  twice. Logs now go to stderr. Rerun: stdout is exactly 3 lines with the key last; stderr has the
  log lines and no key.
- `psql` confirmed the stored hash equals `shasum -a 256` of the printed key.
- Taken name → exit 1 ("already exists"); `--role ADMIN` → exit 2 with the usage line.
- Server on 127.0.0.1:18080: no key → 401 problem; owner → 200 with id, currency, status,
  `balanceMinor` 0 and `createdAt`; statement → `{"items":[],"nextCursor":null}`; unknown id and
  SYSTEM account → 404; SIGTERM → exit 143; nothing on stdout; no key in any output. Compose stopped afterwards.

**Conventions Claude chose inside the approved plan** (for the student to confirm or change):
- A path id that isn't a canonical UUID gets the same 404 as an unknown id (not 400).
- Account reads are owner-only for operators too, as the contract says.
- Every 401 has the same body and `WWW-Authenticate: Bearer realm="payments-lab"`. Malformed keys are
  rejected before any hashing or query.
- Problem `type` is `about:blank`; clients branch on `code`. A 400 lists every bad parameter.
- Query parameters are validated before ownership, so a bad request gets the same 400 whether or not
  the caller owns the account.
- Cursor = base64url of `v1:<posting id>`, unsigned (ownership is still checked on every page).
- Failed authentications are logged at DEBUG with the reason and raw URI, never the header.

**Final verification:** `./gradlew clean build --warning-mode all` → BUILD SUCCESSFUL in 35 s.
`test` 115 run / 0 failed / 0 skipped; `integrationTest` 189 run / 0 failed / 0 skipped. No Gradle
deprecation warnings.

**What this proves:** every `/v1` route, including ones not written yet, needs a valid key, and every
kind of failure looks the same. A customer reads only their own accounts, and "not yours" can't be told
apart from "doesn't exist". Statements page without duplicates or gaps, even while postings arrive.
Errors never show internals. Keys are shown once, stored only as hashes, and never logged.

**Note for reviewers:** Spock prints every sub-expression of a failed condition, so a failing API spec
can print a synthetic test key. The specs keep requests that carry keys outside conditions where practical.

### 2026-10-29 · Slice 2a: ledger core (AI-assisted implementation)

The student approved the Phase 2 plan and asked to start Slice 2a.

**Test infrastructure**
- `TestDatabases`: one PostgreSQL 18 container per test JVM; a template database migrated once;
  each spec (or feature) gets `CREATE DATABASE … TEMPLATE payments_template`, dropped when closed.
  `MigrationSpec` (on an empty database) and `DatabaseVersionSpec` moved onto it and stayed green.
- First attempt failed: the template pool had one connection and Flyway needs two (timed out after 30 s). Raised to three.

**Red run** (`./gradlew test integrationTest --continue`): all 62 Phase 1 specs still green; the new
specs ran 41 features, 37 failed:
- `JournalEntryRequestSpec` 14 run / 11 failed: the stub had no validation. The 3 "accepts" cases passed, as expected.
- `LedgerGuardsSpec` 14 / 13: the red V2 had tables but no guards, so unbalanced entries, wrong
  currencies, zero postings, double reversals and history edits were all accepted. The positive control
  passed; `DELETE FROM journal_entries` failed with a foreign-key error instead of the guard's 42501.
- `LedgerServiceSpec` 7 / 7 and `InvariantCheckerSpec` 6 / 6: stubbed methods, and triggers that didn't exist yet.

**Green run:** 103 run, 0 failed.

**Mutation check (lock ordering):** changed `lockAccounts` to lock one account per statement in
posting order. "postings in opposite directions never deadlock" failed with PostgreSQL
`ERROR: deadlock detected … while locking tuple`; the other 6 features still passed. Restored
byte-identical (`cmp`); 7/7 pass.

**Latent Phase 1 bug, fixed:** `payments-lab migrate` ran Flyway through the app's pool, so
`DB_POOL_SIZE=1` (which `AppConfig` allows) broke it. Reproduced against Compose: exit 1 after ~1 s,
`Connection is not available … (total=1, active=1)`, schema still v1. Flyway now opens its own
connections; the same command then applied V2 and V3 ("now at version v3"). No spec covers `Main`;
verified by hand as described.

**Final verification**
- `./gradlew clean build --warning-mode all` → BUILD SUCCESSFUL in 36 s. `test` 52 run / 0 failed /
  0 skipped; `integrationTest` 51 run / 0 failed / 0 skipped. No Gradle deprecation warnings.
- Compose (PostgreSQL 18.6): the existing v1 database upgraded to v3; SYSTEM funding account present
  with balance 0; the I6 and I7 queries return no rows; readiness 200; SIGTERM clean.

**What this proves:** the database itself refuses unbalanced or currency-mismatched entries, zero
postings, a second reversal, and any UPDATE, DELETE or TRUNCATE of ledger history. The service refuses
overdrafts, unknown or frozen accounts and overflows *before writing anything*, leaving the caller's
transaction usable. Opposite-direction postings don't deadlock. The checker detects each kind of damage
it claims to (I5–I9).

### 2026-10-29 · Decisions D2, D3, D8, D9 (student)

- Chosen through a multiple-choice prompt: D2 single transaction, D3 ordered row locks, D8 signed
  amounts, D9 minimal auth in Phase 2. All four match Claude's proposals. No reasoning was attached,
  so the rationale column says "pending" until the student writes it in their own words.
- ADRs 0005–0007 moved from Proposed to Accepted; ADR 0008 (auth timing) added.
- Wrote `docs/phase-2-plan.md`. It reorders the DESIGN §12 slices so idempotency exists before any
  POST endpoint ships. No code changed.

### 2026-10-29 · Slice 1.2: rest of Phase 1 (AI-assisted implementation)

**PostgreSQL 18 (D11, decided by the student)**
- Red: `DatabaseVersionSpec` on `postgres:17-alpine` → jOOQ's public
  `SQLDialect.POSTGRES.supportsDatabaseVersion(17, 11, "17.11")` returned `false`. `ComposeConsistencySpec` passed (both files still said 17).
- Changed only `gradle.properties` to `postgres:18-alpine` → `DatabaseVersionSpec` passed (forced with `--rerun`);
  `ComposeConsistencySpec` failed: Compose `postgres:17-alpine` vs build `postgres:18-alpine`.
- `docker image inspect postgres:18-alpine` → `PGDATA=/var/lib/postgresql/18/docker`, volume `/var/lib/postgresql`.
  Compose now mounts a new `pg18data` volume there. The old volume was left in place, not deleted.
- After updating Compose: all 47 specs passed.

**Bind address and startup failure**
- `AppConfigSpec` red: 10 run, 7 failed (no loopback default, override ignored, blanks not defaulted,
  no range checks). The 3 that passed covered existing behaviour: non-integer rejection and password redaction.
- `HttpApiStartupSpec` red, attempt 1 (bind address ignored): the bind feature failed because the LAN
  address accepted connections. The port-taken feature failed with "no exception was thrown":
  on macOS, binding `0.0.0.0:P` succeeds while `127.0.0.1:P` is taken, so Spark's exit path was never reached.
- Red, attempt 2 (bind address implemented, Spark's default handler kept): the bind feature passed;
  port-taken → `Process 'Gradle Test Executor 25' finished with non-zero exit value 100`. Spark's `System.exit(100)` killed the test JVM.
- Green: the handler records the failure; `start()` stops the server and throws
  `IllegalStateException("HTTP server failed to start on 127.0.0.1:<port>")`. Both features pass; `AppConfigSpec` 10/10.

**OpenAPI v0 with a drift guard**
- Red, before `docs/api/openapi.yaml` existed: `implemented - operations(false)` → `extra: [GET /health/live, GET /health/ready]`; building-blocks feature failed.
- Green after writing the contract: 3/3.
- Mutation check: removing `x-status: planned` from `/metrics` made "every documented operation not
  marked planned is implemented" fail with `extra: [GET /metrics]`; the file was restored
  byte-identical (`cmp`) and the spec passed again.
- **Build bug found by that check:** the first mutation run was skipped as UP-TO-DATE because
  `openapi.yaml` and `docker-compose.yml` weren't declared as Test task inputs. Both are now declared.

**Docs:** threat model (T1–T22, each with status), test matrix, ADRs 0003 (PostgreSQL 18, accepted),
0004 (money, proposed), 0005 (signed postings, proposed, D8), 0006 (row locks, proposed, D3),
0007 (idempotency, proposed, D2). README config table and doc index. DESIGN updated for PostgreSQL 18
and the new `403 FORBIDDEN` code.

**Final verification**
- `./gradlew clean build --warning-mode all` → BUILD SUCCESSFUL in 35 s. JUnit reports:
  `test` 38 run / 0 failed / 0 skipped; `integrationTest` 24 run / 0 failed / 0 skipped. No Gradle deprecation warnings.
- Smoke test against Compose (PostgreSQL 18.6, Redis 8.10.3) via `installDist`:
  - `payments-lab migrate` → "applied 1 migration(s); schema now at version 1" (fresh `pg18data` volume).
  - `GET /health/ready` → `{"status":"UP","checks":{"postgres":"UP","redis":"UP"}}` HTTP 200.
  - `lsof` → the only listening socket is `127.0.0.1:8080`.
  - jOOQ logged "Database version is supported by dialect SQLDialect.POSTGRES: 18.6"; 0 "Version mismatch" lines.
  - SIGTERM → "Spark shutting down … done", exit code 143.
- Image digest: `postgres:18-alpine` = `postgres@sha256:10a5c69a…edadfb5` (18.6). Redis digest unchanged.

### 2026-10-29 · Slice 1.1: walking skeleton (AI-assisted implementation)

**Environment changes (approved by the student)**
- `brew install openjdk@21 gradle` → OpenJDK 21.0.13, Gradle 9.8.2. **Side effect:** Gradle's
  Homebrew dependency upgraded the default `openjdk` from 25.0.1 to 27.0.1 (`java -version` on PATH is now 27).
- Started Docker Desktop (`open -a Docker`); server 29.2.1.
- Port clashes found and avoided: a native PostgreSQL 15 listens on 5432 and another project's
  container `fff-redis` holds 6379. Compose uses `127.0.0.1:55432` and `127.0.0.1:56379`. Nothing
  belonging to other projects was touched.

**Red run (specs written first, implementation stubbed)**
- A first attempt never ran any specs: dependency download failed with a DNS error after stalling
  ~1 h, most likely while the machine slept between turns. The retry ran normally.
- `./gradlew test integrationTest --continue` → **unit 26 run / 25 failed; integration 18 run / 14 failed.**
  Causes checked: 24 × `UnsupportedOperationException` (stubs), 1 missing null check, HealthApiSpec
  404s (no routes yet), MigrationSpec "no exception thrown" (no CHECK constraints yet). The 5 that
  passed were expected: the max-amount constant, migrate, migrate re-run, round-trip with defaults,
  negative SYSTEM balance allowed.

**Green run and checks**
- `./gradlew test integrationTest --continue` → **44 run, 44 passed**; `./gradlew clean build` → 25 s, 44 passed.
- Second `./gradlew compileJava` → `generateJooq UP-TO-DATE`.
- Dependency report: every `org.eclipse.jetty*` module at `9.4.58.v20250814` (`9.4.48.v20220622 -> 9.4.58.v20250814` for Spark's requests).
- Removed the deprecated `by tasks.registering` syntax → 0 deprecation warnings.
- Smoke test (PostgreSQL 17.11, Redis 8.10.3): migrate applied V1, then 0 on re-run; ready and live 200;
  SIGTERM clean (exit 143); config log shows `dbPassword=***`. Fixed a log bug: a no-op migrate printed `version null`.
- Digests used: `postgres:17-alpine` `sha256:266d3c25…6c917e7b`, `redis:8-alpine` `sha256:21090af9…ae2883537`.

### 2026-10-29 · Phase 0: design proposal (AI-assisted)

- Checked for existing work: the session folder was empty, and none of `~/code ~/projects ~/dev ~/src
  ~/IdeaProjects ~/workspace ~/git ~/repos ~/Developer` exist. Nothing to preserve.
- Toolchain at the time: OpenJDK 25.0.1 only, Docker daemon not running, no Gradle/Maven/k6/kind;
  kubectl 1.34.1 and psql 15.17 present. Machine: Apple M1 Pro, 10 cores, 16 GB, macOS 26.6.2.
- Compatibility evidence (DESIGN §2): spark-core 2.9.4 lastUpdated 2022-07-10, POM pins Jetty 9.4.48 / Java 1.8;
  jetty.org lists 9.4.58 as EOL; jooq.org lists OSS 3.20/3.21 as Java 21+; Spock 2.5 notes "tested … on Java 25 LTS";
  Testcontainers 2.0.2 "Set default docker API version to 1.44".

## Decision log

"Proposed by" is who suggested the option; "Decided by" is filled in only by the student, with their
own rationale.

| # | Decision | ADR | Proposed by | Proposal | Decided by | Student's rationale |
|---|---|---|---|---|---|---|
| D1 | Web framework | 0001 | Claude | Keep SparkJava + Jetty 9.4.58 pin | **Student, 2026-10-29: keep SparkJava** | *(to write)* |
| D2 | Idempotency protocol | 0007 | Claude | Single transaction | **Student, 2026-10-29: single transaction** | *(pending: explain-back)* |
| D3 | Concurrency control | 0006 | Claude | READ COMMITTED + ordered `FOR NO KEY UPDATE` | **Student, 2026-10-29: ordered row locks** | *(pending: explain-back)* |
| D4 | Decline over HTTP | 0009 | Claude | 201 + `status=DECLINED` | **Student, 2026-10-30: 201 + DECLINED** | *(pending: explain-back)* |
| D5 | Redis outage | — | Claude | Non-gating readiness (implemented provisionally); route to REVIEW | — | |
| D6 | Velocity counts | — | Claude | Distinct attempts per (caller, key) | — | |
| D7 | Reversal of spent funds | 0010 | Claude | Decline; operator-only; reversals may post to FROZEN (not CLOSED) accounts | **Student, 2026-11-03: decline; FROZEN allowed** | *(pending: explain-back)* |
| D8 | Posting sign convention | 0005 | Claude | Signed amounts, credit positive | **Student, 2026-10-29: signed amounts** | "Signed amounts directly express balance changes and make the balancing rule simple: all postings for an entry must sum to zero. A separate DEBIT/CREDIT column would require translating that direction into a sign when calculating balances." (2026-11-03) |
| D9 | Auth timing | 0008 | Claude | Minimal API-key auth in Phase 2 | **Student, 2026-10-29: minimal auth in Phase 2** | *(pending: explain-back)* |
| D10 | Phase 3 transport | — | Claude | Redis Stream | — | |
| D11 | PostgreSQL major version | 0003 | Claude | Move to 18 | **Student, 2026-10-29: switch to PG 18** | *(to write)* |
| — | jOOQ codegen approach | 0002 | Claude | Generate from a real PostgreSQL container | — | |
| — | Money representation | 0004 | Claude | `long` minor units, checked arithmetic | **Student, 2026-11-03: proposes to accept, pending final confirmation** | "A long stores cents exactly and keeps arithmetic simple for this single-currency system. double can introduce rounding errors; BigDecimal is exact but adds scale and rounding-policy complexity we don't currently need. Checked arithmetic prevents silent overflow." (2026-11-03) |

## Authorship

Everything below was written by Claude. The last column is for the student's own assessment.

| Item | Student can explain? |
|---|---|
| `docs/DESIGN.md`, threat model, test matrix | — |
| ADRs 0001–0008 | — |
| Build (`build.gradle.kts`, version catalog, codegen task, declared test inputs) | — |
| `money` package and its specs | — |
| V1 migration, `MigrationSpec`, `DatabaseVersionSpec`, `ComposeConsistencySpec` | — |
| `AppConfig` and `AppConfigSpec` | — |
| `HttpApi` (bind address, startup-failure handling, routes), `HttpApiStartupSpec` | — |
| Health: `RedisPinger`, `HealthService`, `HealthApiSpec` | — |
| OpenAPI v0 and `OpenApiContractSpec` | — |
| Test infrastructure: `TestDatabases`, `TestDatabase`, `LedgerFixtures` | — |
| V2 journal migration (guards, triggers) and V3 funding-account seed | — |
| `ledger` package: `JournalEntryRequest`, `LedgerService`, `FundingService`, `InvariantChecker`, refusal exceptions | — |
| `JournalEntryRequestSpec`, `LedgerGuardsSpec`, `LedgerServiceSpec`, `InvariantCheckerSpec` | — |
| `migrate` fix (Flyway's own connections) | — |
| Auth: `ApiKeys`, `BearerToken`, `Callers`, `Caller`, `CallerRole`, the `/v1` auth filter | — |
| `HttpApi` account routes and error handling (`ApiException`, `Problem`), `EntryCursor`, `ledger.AccountQueries` | — |
| `create-caller` (`CreateCallerCommand`, `Main` wiring); logs moved to stderr | — |
| Slice 2b specs: `ApiKeysSpec`, `BearerTokenSpec`, `EntryCursorSpec`, `CreateCallerOptionsSpec`, `AuthApiSpec`, `AccountApiSpec`, `ErrorResponsesApiSpec`, `CreateCallerCommandSpec` | — |
| Test support: `ApiClient`, `CallerFixtures`, `LogCapture`, `ContractSchemas` and `ContractSchemasSpec` | — |
| V4 `idempotency_keys` (deferred "has a response" trigger) and `IdempotencyProtocolSpec` (R3) | — |
| `payments`: `Idempotency`, `RequestFingerprint`; `platform.TxRunner`; `ledger.AccountOpening` | — |
| `HttpApi` POST routes, role checks, `RequestBody`, `CanonicalUuid` | — |
| Slice 2c specs: `RequestFingerprintSpec`, `RequestBodySpec`, `TxRunnerSpec`, `IdempotencyApiSpec`, `AccountCreationApiSpec`, `FundingApiSpec`; `Concurrency` helper | — |
| ADR 0009 (D4 write-up; the decision itself is the student's) | — |
| ADR 0010 (D7 write-up; the decision itself is the student's) | — |
| V5 `payments` and `outbox_events`; `TransferService`, `PaymentStatus`, `OutboxWriter`, `FaultInjector`; transfer routes; `logServerErrorDetail` | — |
| Slice 2d specs: `PaymentStatusSpec`, `TransferApiSpec`, `TransferAtomicitySpec`, `TransferConcurrencySpec`, `DatabaseSpec`; `ArmedFaults` | — |
| `LedgerService.reverse`, `EntryKind.allowsPostingTo`, the I9 mirror check, `TransferService.reverse`, V6 `reversed_by`, the reversal route | — |
| Slice 2e specs: `EntryKindSpec`, `ReversalApiSpec`, reversal features in `LedgerServiceSpec`, `InvariantCheckerSpec`, `TransferAtomicitySpec` | — |
| Slice 2f: `LedgerModel`, `ModelRun`, `LedgerModelSpec`; the per-operation key and boundary features | — |
