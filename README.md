# ledger-sentinel-ai

A Java 21 portfolio project: internal account-to-account transfers on a double-entry ledger, with
idempotent APIs and safe concurrency. **Synthetic data only. Not a real payment system.** The code and
docs use the working name `payments-lab`.

**Built and verified (Phases 1 and 2 of 6):** the ledger core, API keys and account reads, idempotent
account opening and fundings, transfers with concurrency proofs, operator reversals, and model-based
runs (50 seeds × 200 random operations checked against a model). Transfers already write their events
to an outbox table.

**Planned, not built yet:** the outbox relay and crash demos (Phase 3), deterministic fraud screening
(Phase 4), operations and security hardening (Phase 5), benchmarks and local Kubernetes (Phase 6).
Nothing here uses AI or machine learning yet: the planned fraud rules are deterministic and ML is deferred.

[PROGRESS.md](PROGRESS.md) has what is implemented and verified, with the evidence, and says who wrote
and decided what.

## Documentation

| Document | What it covers |
|---|---|
| [docs/DESIGN.md](docs/DESIGN.md) | Architecture, invariants, schema, phases, open decisions |
| [docs/api/openapi.yaml](docs/api/openapi.yaml) | API contract (implemented and planned operations) |
| [docs/threat-model.md](docs/threat-model.md) | Assets, trust boundaries, STRIDE threats with status |
| [docs/test-matrix.md](docs/test-matrix.md) | Requirement → spec → verified or planned |
| [docs/adr/](docs/adr/) | Architecture decision records |

## Prerequisites

- JDK 21 (for example `brew install openjdk@21`)
- Docker running. Compiling needs it too: jOOQ classes are generated from a real PostgreSQL
  ([ADR 0002](docs/adr/0002-jooq-codegen-from-real-postgres.md)).

Point Gradle at JDK 21 (Homebrew path shown):

```bash
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
```

## Build and test

```bash
./gradlew test
```

```bash
./gradlew integrationTest
```

`test` runs fast unit specs. `integrationTest` runs specs against real PostgreSQL 18 and Redis 8
containers and a real Jetty server. `./gradlew build` runs both.

## Run locally

```bash
docker compose up -d --wait
```

```bash
./gradlew run --args=migrate
```

```bash
./gradlew run
```

```bash
curl -s localhost:8080/health/ready
```

Every `/v1` route needs an API key. Create a synthetic caller; the command prints its key once and
stores only a SHA-256 hash:

```bash
./gradlew -q run --args="create-caller --role CUSTOMER --name alice"
```

Logs go to stderr, so stdout carries only the command's output. Don't run it where stdout is
collected as logs. Every POST needs an `Idempotency-Key`; repeating a request with the same key
returns the stored response instead of doing the work twice. A customer opens an account:

```bash
curl -s -H "Authorization: Bearer $API_KEY" -H "Idempotency-Key: $(uuidgen)" -H "Content-Type: application/json" -d '{"currency":"EUR"}' localhost:8080/v1/accounts
```

An operator (`--role OPERATOR`) funds it with `POST /v1/admin/fundings` and
`{"accountId": "…", "amountMinor": 2500, "currency": "EUR"}`. The owner moves money with
`POST /v1/transfers` and `{"sourceAccountId": "…", "destinationAccountId": "…", "amountMinor": 1000,
"currency": "EUR"}`; a refusal for lack of funds is `201` with `"status": "DECLINED"` (ADR 0009).
The owner reads the balance:

```bash
curl -s -H "Authorization: Bearer $API_KEY" localhost:8080/v1/accounts/$ACCOUNT_ID
```

Compose exposes PostgreSQL on `127.0.0.1:55432` and Redis on `127.0.0.1:56379` (non-default ports
to avoid clashing with other local services).

Configuration comes from environment variables; blank means unset. The defaults match Compose and
are for local use only.

| Variable | Default | Notes |
|---|---|---|
| `HTTP_BIND_ADDRESS` | `127.0.0.1` | Set `0.0.0.0` inside containers |
| `HTTP_PORT` | `8080` | `0` picks a free port |
| `DB_URL` | `jdbc:postgresql://localhost:55432/payments` | |
| `DB_USER` / `DB_PASSWORD` | `payments` / local-only value | Never logged |
| `DB_POOL_SIZE` | `10` | 1–100 |
| `REDIS_URL` | `redis://localhost:56379` | |

## License

[MIT](LICENSE). Dependencies keep their own licenses.
