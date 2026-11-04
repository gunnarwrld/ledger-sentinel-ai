# Test matrix

What each requirement is checked by, and whether that check has run. VERIFIED means the spec ran
green in the build recorded in `PROGRESS.md`. Invariant ids (I1–I14) are from DESIGN §5; threat ids
(T1–T25) are from `threat-model.md`.

**Levels:** unit = `./gradlew test` (no containers) · integration = `./gradlew integrationTest`
(real PostgreSQL 18 / Redis 8 via Testcontainers, or a real Jetty server).

## Phase 1 (implemented)

| Requirement | Spec | Level | Status |
|---|---|---|---|
| I1 checked arithmetic: overflow throws, never wraps | `MoneySpec` (overflow ×3) | unit | VERIFIED |
| I1 same-currency arithmetic; mixing currencies rejected; currency required | `MoneySpec` | unit | VERIFIED |
| I3 only EUR accepted, exact code (`eur`, ` EUR`, `EURO`, `""`, null, USD rejected) | `SupportedCurrenciesSpec` | unit | VERIFIED |
| I2 transfer amount within 1..100,000,000 minor units; supported currency | `TransferAmountSpec` | unit | VERIFIED |
| Config: loopback default, overrides, blank → default, range checks, password redacted (T9, T12) | `AppConfigSpec` | unit | VERIFIED |
| Compose and build use the same images | `ComposeConsistencySpec` | unit | VERIFIED |
| Migrations apply to a clean DB; re-run is a no-op | `MigrationSpec` | integration | VERIFIED |
| jOOQ generated classes round-trip an account with its defaults | `MigrationSpec` | integration | VERIFIED |
| I3/I8 DB CHECKs: unsupported currency, negative customer balance, negative SYSTEM balance without flag, customer allowed negative, owner/kind mismatch ×2, unknown kind, unknown status, unknown caller role | `MigrationSpec` | integration | VERIFIED |
| jOOQ dialect supports the PostgreSQL server we run (D11) | `DatabaseVersionSpec` | integration | VERIFIED |
| `/health/live` 200 even with dependencies down | `HealthApiSpec` | integration | VERIFIED |
| `/health/ready` 200 when PG + Redis up; 503 within 2 s when PG stops (T13); 200 with `redis: DOWN` when Redis stops | `HealthApiSpec` | integration | VERIFIED |
| HTTP binds only the configured address (T12) | `HttpApiStartupSpec` | integration | VERIFIED (skipped automatically on machines with no LAN address) |
| Port conflict throws instead of exiting the JVM (T17) | `HttpApiStartupSpec` | integration | VERIFIED |
| Implemented routes and `openapi.yaml` match in both directions | `OpenApiContractSpec` | integration | VERIFIED (both directions checked by red run + mutation) |

## Phase 2, Slice 2a (implemented)

| Requirement | Spec | Level | Status |
|---|---|---|---|
| I5 entry shape: ≥ 2 non-zero postings, one supported currency, each account once, sum exactly 0, overflow rejected; REVERSAL names its target; unmodifiable copy | `JournalEntryRequestSpec` | unit | VERIFIED |
| I5 unbalanced entry rejected at COMMIT (inserts accepted first); entry with no postings rejected | `LedgerGuardsSpec` | integration | VERIFIED |
| I3 posting currency must match its account (composite FK) | `LedgerGuardsSpec` | integration | VERIFIED |
| Zero posting rejected; REVERSAL must name its target; an entry can be reversed only once | `LedgerGuardsSpec` | integration | VERIFIED |
| I9 UPDATE, DELETE and TRUNCATE of ledger history rejected, including `TRUNCATE accounts CASCADE` (T5) | `LedgerGuardsSpec` | integration | VERIFIED |
| Funding posts one balanced entry; SYSTEM account goes negative | `LedgerServiceSpec` | integration | VERIFIED |
| A transfer entry moves both balances | `LedgerServiceSpec` | integration | VERIFIED |
| I8 overdraft refused before anything is written; the caller's transaction stays usable | `LedgerServiceSpec` | integration | VERIFIED |
| Unknown account and non-ACTIVE account refused | `LedgerServiceSpec` | integration | VERIFIED |
| I1 balance overflow refused, nothing written | `LedgerServiceSpec` | integration | VERIFIED |
| Opposite-direction postings never deadlock (ordered locks, ADR 0006) | `LedgerServiceSpec` | integration | VERIFIED (mutation-checked) |
| Checker reports nothing on clean ledgers and detects I5, I6, I7, I8 and I9 damage | `InvariantCheckerSpec` | integration | VERIFIED |

## Phase 2, Slice 2b (implemented)

API specs start the whole app (same wiring as `Main`) on a template-copy database. Response bodies
are checked against the contract with `ContractSchemas`, which also rejects undeclared fields.

| Requirement | Spec | Level | Status |
|---|---|---|---|
| Key format `pl_test_` + 32 random bytes in base64url; keys don't repeat; hash is SHA-256 of the whole key (FIPS 180-2 vector); 11 malformed shapes rejected | `ApiKeysSpec` | unit | VERIFIED |
| `Authorization: Bearer` parsing: scheme in any case, one or more spaces, token68; 11 malformed headers yield no token | `BearerTokenSpec` | unit | VERIFIED |
| Statement cursor: round trip, opaque and URL-safe; 12 kinds of broken or hand-made cursors rejected | `EntryCursorSpec` | unit | VERIFIED |
| `create-caller` arguments: both orders parse; 10 mistakes rejected with a message saying what is wrong | `CreateCallerOptionsSpec` | unit | VERIFIED |
| T1: every documented `/v1` operation (planned ones too) and an unmapped `/v1` path × 9 bad-credential variants → the same 401 problem and `WWW-Authenticate`; health stays public; a valid key works with the scheme in any case | `AuthApiSpec` | integration | VERIFIED (mutation-checked) |
| T9: no API key in any log line, with our loggers at TRACE (requests and `create-caller`) | `AuthApiSpec`, `CreateCallerCommandSpec` | integration | VERIFIED |
| T8: the owner reads balance and status in the contract's shape; another customer, an operator, the SYSTEM account and a non-UUID id all get the same 404 body as an unknown id, on both account routes | `AccountApiSpec` | integration | VERIFIED (mutation-checked) |
| Statement: newest first, signed per account, with kinds and entry ids; empty statement; a page walk has no duplicates or gaps; pages stay stable while postings arrive (keyset, not offset); default limit 50; limits 1 and 200 accepted; 13 invalid limit/cursor queries → 400 naming every bad field | `AccountApiSpec` | integration | VERIFIED (page boundary mutation-checked) |
| T10: unknown routes → problem+json 404; a SQL failure → generic 500 with a correlation id; the details are logged under that id | `ErrorResponsesApiSpec` | integration | VERIFIED |
| `create-caller`: the key is printed once and works; only its SHA-256 hash is stored; taken and malformed names are refused with nothing stored; `Created.toString()` redacts the key | `CreateCallerCommandSpec` | integration | VERIFIED |
| The response validator itself rejects missing, undeclared, mistyped and out-of-enum values | `ContractSchemasSpec` | integration | VERIFIED |

## Phase 2, Slice 2c (implemented)

| Requirement | Spec | Level | Status |
|---|---|---|---|
| **R3:** a duplicate INSERT of a key waits (seen in `pg_stat_activity`) until the first transaction commits, then reads its response; a rollback lets it take the key; `lock_timeout` → 55P03; other callers and operations don't wait; a key row can't commit without a response | `IdempotencyProtocolSpec` | integration (plain SQL) | VERIFIED — **R3 retired** |
| Request fingerprint: deterministic SHA-256 over length-prefixed parts (exact format pinned); every field, the operation, part boundaries and absent vs empty all change it | `RequestFingerprintSpec` | unit | VERIFIED |
| Strict JSON bodies: amounts only as JSON integers in range (11 rejected forms); exact currency codes; canonical UUIDs; unknown fields rejected; duplicate fields, trailing data and non-objects rejected; all problems reported at once | `RequestBodySpec` | unit | VERIFIED |
| Transactions: 2 s `lock_timeout`; 40P01, 40001 and 55P03 retried with the failed attempt rolled back; 3 attempts then `ContentionException`; backoff 10 ms then 20 ms ± 50 %; other errors not retried; a real deadlock is retried and both sides finish | `TxRunnerSpec` | integration | VERIFIED |
| I11: same key and payload → byte-identical replay with `Idempotent-Replayed: true`, money moved once; rewritten payloads (field order, whitespace, UUID case) still replay; different payload → 422 and the original stays replayable; keys are per caller; validation errors and refusals aren't stored; missing key → `IDEMPOTENCY_KEY_MISSING`, malformed → `VALIDATION_FAILED` | `IdempotencyApiSpec` | integration | VERIFIED (mutation-checked) |
| I11 under concurrency: 20 simultaneous duplicates → one funding, identical responses, 19 replays; a mid-flight duplicate replays the committed response, takes over after a rollback, or gets 409 with `Retry-After: 1` after `lock_timeout` | `IdempotencyApiSpec` | integration | VERIFIED (mutation-checked) |
| `POST /v1/accounts`: a customer opens an ACTIVE, zero-balance EUR account only they can read; operators get 403; 7 invalid bodies (including `ownerId` and `balanceMinor`) → 400 and nothing created | `AccountCreationApiSpec` | integration | VERIFIED |
| `POST /v1/admin/fundings` (T18): one FUNDING entry the owner sees; customers get 403; unknown account → 404; SYSTEM account → 422 `INVALID_ACCOUNT_COMBINATION`; frozen account → 422 `ACCOUNT_NOT_ACTIVE`; 9 invalid bodies → 400; nothing posted on any refusal | `FundingApiSpec` | integration | VERIFIED (role check mutation-checked) |

## Phase 2, Slice 2d (implemented)

| Requirement | Spec | Level | Status |
|---|---|---|---|
| I12: transitions exactly REVIEW_REQUIRED → POSTED/DECLINED and POSTED → REVERSED; REVERSED is never a starting state; only POSTED and REVERSED moved money | `PaymentStatusSpec` | unit | VERIFIED |
| Optional free text (`reference`): absent or ≤ 140 characters, counted as PostgreSQL counts them (accents, emoji); numbers, null, objects and 141 characters rejected | `RequestBodySpec` | unit | VERIFIED |
| Happy path: one TRANSFER entry seen from both sides, both balances moved, the Transfer reads back identically, one `transfer.posted` event | `TransferApiSpec` | integration | VERIFIED |
| D4 / ADR 0009: insufficient funds or a frozen source or destination → 201 DECLINED with a coarse reason, a stored payment, no entry, no money moved, one `transfer.declined` event; a decline is final for its key | `TransferApiSpec` | integration | VERIFIED (mutation-checked) |
| T8: someone else's source or an unknown destination → the same 404 as an unknown account, nothing stored; a transfer is readable by its creator and operators only | `TransferApiSpec` | integration | VERIFIED (mutation-checked) |
| I2/I4/T6: same account on both sides → 422; operators → 403; 10 invalid bodies (amounts 0, −1, max + 1, 1.5, "10", 2^63; USD; 141-character reference; a `status` field; no destination) → 400, nothing stored | `TransferApiSpec` | integration | VERIFIED |
| T9: the reference is stored and returned but never logged (our loggers at TRACE) | `TransferApiSpec` | integration | VERIFIED |
| I10, crash point C1: a fault after every write, before commit → generic 500 and no payment, entry, posting, balance change, outbox event or key; the same key then posts exactly once | `TransferAtomicitySpec` | integration | VERIFIED |
| I11: 50 concurrent duplicates → one payment, one entry, identical responses, 49 replays | `TransferConcurrencySpec` | integration | VERIFIED |
| I8, R4: 20 debits of 10 racing for a balance of 100 → exactly 10 POSTED and 10 DECLINED(INSUFFICIENT_FUNDS), 0 errors, balance 0 | `TransferConcurrencySpec` | integration | VERIFIED (mutation-checked) |
| R4: 100 transfers in opposite directions (4 workers) → all POSTED, no deadlock reaches a client, balances unchanged | `TransferConcurrencySpec` | integration | VERIFIED |
| Hot account: 100 concurrent transfers into one account → all POSTED, exact balance | `TransferConcurrencySpec` | integration | VERIFIED (throughput measured in Phase 6) |
| I13: each transfer writes one outbox event in its transaction | `TransferApiSpec`, `TransferAtomicitySpec` | integration | VERIFIED (mutation-checked) |
| T9: PostgreSQL error details, which can quote rows, are dropped from the app pool's exception messages (control: the driver default keeps them) | `DatabaseSpec` | integration | VERIFIED |

## Phase 2, Slice 2e (implemented)

| Requirement | Spec | Level | Status |
|---|---|---|---|
| ADR 0010: only REVERSAL entries may post to FROZEN accounts; nothing posts to CLOSED ones | `EntryKindSpec` | unit | VERIFIED (mutation-checked) |
| `LedgerService.reverse`: the exact mirror, linked by `reverses_id`, balances restored; FROZEN receiver allowed; CLOSED account refused; a receiver who spent the money refused before any write, transaction still usable; no second reversal, no reversal of a reversal | `LedgerServiceSpec` | integration | VERIFIED |
| I9: a balanced reversal for the wrong amount, which passes every other check, is reported | `InvariantCheckerSpec` | integration | VERIFIED |
| Reversal through the API: mirroring entry on both statements, balances restored, transfer reads back REVERSED, operator recorded, `transfer.reversed` event | `ReversalApiSpec` | integration | VERIFIED |
| I12: same key replays; another key or another operator → 409 `INVALID_STATE_TRANSITION`; a DECLINED transfer → 409; nothing written on any refusal | `ReversalApiSpec` | integration | VERIFIED (mutation-checked) |
| ADR 0010: a receiver who spent the money → 422 `INSUFFICIENT_FUNDS`, nothing written, and the same key works once covered; FROZEN source or destination still reversible; CLOSED either side → 422 `ACCOUNT_NOT_ACTIVE` | `ReversalApiSpec` | integration | VERIFIED |
| T18: customers (sender or receiver) → 403; unknown or non-UUID transfer → 404 (with a control); body must be empty or `{}`; missing key → 400 | `ReversalApiSpec` | integration | VERIFIED |
| Concurrency: ten simultaneous reversals → exactly one 201, nine 409, one mirror entry; a reversal racing the receiver's own spending → exactly one of them gets the money, in all 20 rounds | `ReversalApiSpec` | integration | VERIFIED (row lock mutation-checked) |
| I10: a fault after the reversal's writes, before commit → nothing survives, the transfer stays POSTED; the same key then reverses once | `TransferAtomicitySpec` | integration | VERIFIED |

## Phase 2, Slice 2f (implemented)

| Requirement | Spec | Level | Status |
|---|---|---|---|
| I6, I7 and the whole API contract under random use: 50 seeds × 200 operations (fundings, valid and invalid transfers, exact retries, reused keys, reversals, status changes, reads) through the real API; every answer matches an independent in-memory model; afterwards balances, statuses, row counts and the SYSTEM account match it and every invariant holds | `LedgerModelSpec` | integration | VERIFIED (3 mutations: caught in 38, 32 and 49 of 50 seeds) |
| The default seeds reach every kind of answer at least once (21 kinds, from boundary declines to keys moved between operations) | `LedgerModelSpec` | integration | VERIFIED |
| I11: keys are scoped per operation: an operator's funding key used again for a reversal is a new request | `IdempotencyApiSpec` | integration | VERIFIED (mutation-checked) |
| I8 boundary: one cent more than the balance is declined; exactly the balance posts and leaves 0 | `TransferApiSpec` | integration | VERIFIED (mutation-checked) |

**Phase 2 is complete:** every row above is VERIFIED.

Phases 3–6 are listed in DESIGN §12 and get rows here when each phase starts.
