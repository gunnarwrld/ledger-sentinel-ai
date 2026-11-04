# ADR 0003: Run PostgreSQL 18

- **Status:** Accepted
- **Date:** 2026-10-29
- **Decided by:** the student (D11). Problem found and options proposed by Claude.

## Context

Slice 1.1 ran on PostgreSQL 17.11. At runtime jOOQ logged "Database version is older than what
dialect SQLDialect.POSTGRES supports". The free jOOQ edition has a single unversioned `POSTGRES`
dialect; versioned dialects are commercial. If the SQL builder assumes features the server lacks,
the failure shows up as runtime SQL errors, not compile errors.

Evidence, captured by `DatabaseVersionSpec`, which calls jOOQ's public
`SQLDialect.supportsDatabaseVersion(major, minor, productVersion)`:

| Server | Result |
|---|---|
| PostgreSQL 17.11 (`postgres:17-alpine`) | `false` (spec failed) |
| PostgreSQL 18.6 (`postgres:18-alpine`) | `true` (spec passed) |

No `postgres:19` image existed on Docker Hub when checked (2026-10-29).

## Decision

Use `postgres:18-alpine` for jOOQ codegen, integration specs (both via `gradle.properties`) and Compose.

## Consequences

- PostgreSQL 18 images keep data in `/var/lib/postgresql/18/docker` and declare the volume at
  `/var/lib/postgresql` (verified with `docker image inspect`). Compose mounts a new volume,
  `pg18data`, there. The old PostgreSQL 17 volume `payments-lab_pgdata` is left untouched; its data
  can't be read by 18 anyway. Remove it with `docker volume rm payments-lab_pgdata` when no longer wanted.
- `ComposeConsistencySpec` fails the build if Compose and `gradle.properties` name different images.
- `DatabaseVersionSpec` will flag the same mismatch again if jOOQ is upgraded past what the server supports.
- PostgreSQL 18 adds features such as a built-in `uuidv7()`. We adopt them only with their own reason.

## Explain-back (student)

- Why is a dialect/server mismatch a runtime risk rather than a compile-time one?
- Why does a major-version change need a new data volume?
