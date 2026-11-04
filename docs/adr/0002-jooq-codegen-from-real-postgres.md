# ADR 0002: Generate jOOQ classes from the migrated schema in a real PostgreSQL container

- **Status:** Proposed by Claude. Needs the student's acceptance.
- **Date:** 2026-10-29

## Context

jOOQ's type-safe API needs Java classes that mirror the schema. The schema is defined by Flyway
migrations (`src/main/resources/db/migration`). The generated classes must never drift from the migrations.

## Options

| Option | Fidelity | Build needs Docker? | Drift risk |
|---|---|---|---|
| **A. Codegen against a throwaway PostgreSQL container** after applying the migrations | Exact: the real engine parses the real SQL | Yes | None: regenerated whenever migrations change |
| B. jOOQ `DDLDatabase` (parses the SQL files without a database) | Approximate: PostgreSQL-specific DDL (triggers, plpgsql) must be hidden from the parser | No | Low, but parser gaps can silently differ |
| C. Commit generated sources, regenerate by hand | Exact when regenerated | Only when regenerating | Real: someone forgets to regenerate |

## Decision

Option A. A small program (`src/codegen/java/.../JooqCodegen.java`) starts the PostgreSQL image
from `gradle.properties`, applies the migrations with Flyway, and runs jOOQ's `GenerationTool`.
Gradle runs it as the `generateJooq` task before `compileJava`. The task declares the migrations
directory, the image tag and its own classpath as inputs and `build/generated-src/jooq` as its
output. It reruns when any of those change, and also after edits to `build.gradle.kts`, because its
argument provider is a lambda defined there. Otherwise it is skipped: verified 2026-10-29, a second
`./gradlew compileJava` reported `generateJooq UP-TO-DATE`.

## Consequences

- **Compiling needs Docker**, even for unit tests. The integration specs need Docker anyway, so
  this adds no new requirement, but a clean build is a few seconds slower.
- Generated code isn't committed; reviewers read the migrations, which are the source of truth.
- The codegen image tag and the test image tag come from one property, so codegen, tests and
  production (compose) use the same major version. Compose repeats the tag; keep them in sync.
- Measured cost on the dev machine: a clean `./gradlew build` (codegen, 26 unit specs, 18
  integration specs) took 25 s with images already pulled.

## Explain-back (student)

- Why is generating from a real database more trustworthy than parsing the SQL files?
- What does Gradle compare to decide `generateJooq` is up to date?
