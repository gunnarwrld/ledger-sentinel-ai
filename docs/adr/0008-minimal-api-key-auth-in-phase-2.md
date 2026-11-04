# ADR 0008: Minimal API-key authentication arrives in Phase 2

- **Status:** Accepted. **Decided by the student (D9), 2026-10-29**, choosing Claude's proposal.
  The student's rationale is pending (see `PROGRESS.md`).
- **Date:** 2026-10-29

## Context

The original phase order put authentication in Phase 5. But two Phase 2 guarantees need to know who is calling:

- Idempotency keys are scoped to the caller (ADR 0007). If the caller came from a header the client
  sets, any client could claim another caller's identity and read or collide with their stored results.
- Only the owner may debit an account. An ownership check against a client-chosen identity checks nothing.

## Decision

Phase 2 (Slice 2b) includes the minimum needed for a trustworthy caller identity:

- API keys: `pl_test_` plus 32 bytes from `SecureRandom`, base64url-encoded. Only a SHA-256 hash is
  stored (`callers.api_key_hash`, unique). The key is shown once, when a CLI command creates the caller.
- `Authorization: Bearer <key>` required on every `/v1/*` route; health endpoints stay public.
- Two roles: `CUSTOMER` (owns accounts) and `OPERATOR` (funding, reversals, reviews).
- Missing or unknown key → `401 UNAUTHENTICATED` (problem+json). Customer calling an operator route → `403 FORBIDDEN`.

Phase 5 still owns the hardening: key rotation and revocation, rate limiting, auth-failure metrics
and audit, request size limits, and a review of secret handling.

## Why a fast hash is fine here

Slow password hashes (bcrypt, Argon2) defend *low-entropy* secrets against guessing. A 256-bit random
key can't be guessed, so SHA-256 is enough, and a deterministic hash lets us find the caller with an
indexed lookup instead of comparing against every stored hash.

## Alternatives

| Option | Why not now |
|---|---|
| Trusted `X-Caller-Id` header until Phase 5 | Idempotency scoping and ownership would rest on a spoofable value |
| JWTs | Needs an issuer, signing keys and expiry handling; no extra benefit for one service |
| Mutual TLS | Infrastructure-heavy for a local portfolio setup |

## Consequences

- Moves a small part of Phase 5 into Phase 2.
- Tests need a helper that creates callers and keys; the keys are generated per test run, never real credentials.

## Implementation (Slice 2b, VERIFIED)

- `create-caller --role CUSTOMER|OPERATOR --name NAME` prints the key once on stdout; logs go to stderr.
- A Spark `before("/v1/*")` filter authenticates every `/v1` request before route matching, so
  routes that don't exist yet are covered too. Every failure gets the same 401 body and
  `WWW-Authenticate: Bearer realm="payments-lab"`.
- A shape check rejects malformed keys before any hashing or database lookup.
- `403 FORBIDDEN` arrives with the first operator route (fundings, Slice 2c).
- Specs: `ApiKeysSpec`, `BearerTokenSpec`, `AuthApiSpec`, `CreateCallerCommandSpec` (see `docs/test-matrix.md`).

## Explain-back (student)

- Why can't idempotency scoping trust a header the client sets?
- Why is SHA-256 acceptable for API keys but not for user passwords?
