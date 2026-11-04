# CLAUDE.md: payments-lab

Java 21 payments + fraud-screening portfolio project for a final-year CS student targeting backend /
banking-tech roles. Claude acts as a senior backend mentor. **The student must be able to explain and
defend every design decision.**

## Current state
- Design: `docs/DESIGN.md`. Decided: D1 Spark (ADR 0001), D2 single-tx idempotency (0007), D3 ordered row locks (0006),
  D8 signed postings (0005), D9 minimal auth in Phase 2 (0008), D11 PostgreSQL 18 (0003), D4 201 + DECLINED (0009),
  D7 decline uncovered reversals + allow FROZEN accounts (0010). Open: D5, D6, D10. The student's rationale for D1–D4, D7, D9, D11
  is still pending (D8 is recorded; ADR 0004 awaits their final confirmation); never write it for them.
- Phases 1 and 2 VERIFIED (`docs/phase-2-plan.md`): slices 2a–2f, 545 spec runs green. **Stopped for the student's Phase 2
  review** (checklist in `PROGRESS.md`). Phase 3 (outbox relay, crash demos) starts only after it, and needs D10 (transport). The review is at round 2 of 7.
- Public repository: https://github.com/gunnarwrld/ledger-sentinel-ai (branch `main`). Push only when the student asks.
- Latest status, verified results, current known gaps: `PROGRESS.md` (read it first).

## Ledger rules for new code
- `LedgerService.post(tx, request)` runs in the caller's transaction and checks everything before its first write.
- Lock accounts only through `LedgerService` (one ordered statement). Never lock rows one at a time or sort UUIDs in Java.
- Integration specs that need the schema use `TestDatabases.create()` (a template copy). Ledger rows can't be deleted or truncated, by design.
- Run `InvariantChecker` after any spec that writes ledger data.

## API rules for new code
- Every `/v1` route sits behind the `before("/v1/*")` auth filter. Take the caller from the request attribute, never from the request.
- Client-visible failures are `ApiException`s (problem+json, stable `code`). Anything else becomes a generic 500 with a correlation id.
- Map response bodies field by field. In specs, check them with `ContractSchemas.violations(body, "<Schema>")`.
- Never log keys, headers or request bodies (logs go to stderr). Keep requests that carry keys outside Spock conditions.
- API specs start the whole app with `App.start(db.appConfig())` and create callers with `CallerFixtures`.
- POST routes: role check → `Idempotency-Key` → `RequestBody` (collect every error) → `idempotency.execute(...)`. Throwing
  inside the work rolls back the key too, so only completed operations are stored. Writes run through `TxRunner`.
- Transfers: `TransferService.create` runs inside `idempotency.execute`; ledger refusals become stored DECLINED payments
  (ADR 0009). Never log or put `reference` in events. Fault points (`FaultInjector`) are no-ops in production.
- Reversals: lock order is idempotency key → payment row (`FOR NO KEY UPDATE`) → accounts. Corrections go through
  `LedgerService.reverse` (an exact mirror), never hand-built REVERSAL entries.
- `ApiClient.json` parses eagerly (`JsonSlurperClassic`): `JsonSlurper`'s lazy maps break when threads share them.
- A behaviour change that the model doesn't know makes `LedgerModelSpec` fail: update `LedgerModel` in the same change,
  and keep its rules written independently of the production code.
- A 404 can't prove a route exists (unknown routes give the same body): pair "not found" specs with a control request that succeeds.

## Non-negotiables
- Synthetic accounts and transactions only. No real payments, personal data or real credentials.
- PostgreSQL is the only source of truth. Redis is advisory (fraud velocity, maybe the event stream) and never decides whether money moved.
- Money is `long` minor units with checked arithmetic. The journal is append-only; corrections are reversal entries.
- Tests first (Spock): write the spec, run it, see it fail for the right reason, implement, run it green. Record both runs in `PROGRESS.md`.
- One small vertical slice at a time. **Stop after each phase for the student's review.**
- Label everything PLANNED / IMPLEMENTED / VERIFIED. Never claim a test or benchmark ran unless it was run here and its output captured.
- Never push, publish, deploy to paid infrastructure, or use real credentials without explicit approval. Kubernetes = local kind/k3d only.
- Don't change the stack (including major versions) without flagging the problem and the alternatives first.
- Record who decided what: AI-proposed vs student-decided, with the student's rationale, in `PROGRESS.md`.
- Migrations are immutable once shared. V1 was edited during Slice 1.1 only because it had never left this machine.

## Stack (pinned in `gradle/libs.versions.toml`)
Java 21 toolchain · SparkJava 2.9.4 with Jetty forced to 9.4.58 · jOOQ OSS 3.21.9 · Flyway 13.11.1 · PostgreSQL 18
· Redis 8 · Lettuce 7.9.0 · HikariCP 7.1.0 · PG JDBC 42.7.14 · Jackson 2.22.4 · Logback 1.6.6 · Spock 2.5-groovy-4.0 / Groovy 4.0.33
(+ groovy-yaml for the contract spec) · JUnit BOM 5.14.4 · Testcontainers 2.0.5 · Gradle 9.8.2 wrapper · k6 (Phase 6).
Image tags live in `gradle.properties` and are repeated in `docker-compose.yml`; `ComposeConsistencySpec` keeps them equal.

## Guards that keep docs honest
- `OpenApiContractSpec`: registered routes must match `docs/api/openapi.yaml`. When implementing an endpoint, remove its `x-status: planned` in the same change.
- `DatabaseVersionSpec`: jOOQ's dialect must support the PostgreSQL image. Recheck after any jOOQ or image upgrade.
- Specs that read files outside the classpath need those files declared as Test task `inputs` (see `build.gradle.kts`), or Gradle reports them UP-TO-DATE.

## Machine facts
Apple M1 Pro, 10 cores, 16 GB, macOS 26.6.2. JDK 21 at `/opt/homebrew/opt/openjdk@21` (keg-only; `java` on PATH is 27).
**Ports taken by other software:** 5432 (native PostgreSQL 15), 6379 (another project's `fff-redis` container). Don't touch them.
Long background builds can stall if the machine sleeps between turns: run builds in the foreground within a turn.

## Commands
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew test                 # unit specs (compiling needs Docker: jOOQ codegen)
./gradlew integrationTest      # Testcontainers specs (PostgreSQL + Redis)
./gradlew build                # both, via check
docker compose up -d --wait    # Postgres on 127.0.0.1:55432, Redis on 127.0.0.1:56379
./gradlew run --args=migrate   # apply migrations to the Compose database
./gradlew run                  # serve on :8080 (HTTP_PORT to change)
./gradlew -q run --args="create-caller --role CUSTOMER --name alice"   # prints a new API key once (stdout)
./gradlew integrationTest --tests '*LedgerModelSpec' -PmodelSeeds=17  # replay one model seed exactly (-PmodelRandomSeeds=200 explores)
```

## Layout
`docs/DESIGN.md` · `docs/adr/` (0001–0010) · `docs/api/openapi.yaml` · `docs/threat-model.md` · `docs/test-matrix.md`
· `src/main/java/io/paymentslab/{app,api,platform,money,ledger,payments,outbox}` (+ `fraud` in Phase 4); `api` also owns auth and the `callers` table (DESIGN §3.1)
· `src/main/resources/db/migration` · `src/codegen/java` (jOOQ generator, ADR 0002) · `src/test/groovy` (unit)
· `src/integrationTest/groovy` (Testcontainers / real Jetty) · `PROGRESS.md`. Planned: `k6/`, `deploy/k8s/`.
