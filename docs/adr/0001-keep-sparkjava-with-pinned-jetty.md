# ADR 0001: Keep SparkJava 2.9.4, force Jetty 9.4.58, isolate the HTTP layer

- **Status:** Accepted
- **Date:** 2026-10-29
- **Decided by:** the student (D1). Options and mitigations proposed by Claude.

## Context

The required stack names SparkJava. Checked on 2026-10-29:

- `com.sparkjava:spark-core` 2.9.4 is the newest release (Maven Central `lastUpdated` 2022-07-10).
- Its POM targets Java 8 and pins Jetty `9.4.48.v20220622`.
- jetty.org lists the 9.4 line as EOL; the newest 9.4 build on Maven Central is `9.4.58.v20250814`.
- Javalin 7.2.4 (Jetty 12) is a maintained framework with a very similar style.

## Decision

1. Keep SparkJava 2.9.4.
2. Force every `org.eclipse.jetty*` module to 9.4.58 with a Gradle `eachDependency` rule
   (`build.gradle.kts`), so the reason shows up in `./gradlew dependencyInsight`.
3. Only `io.paymentslab.api` may import `spark.*`. Domain and application code take and return
   plain Java types, so a framework swap touches one package.
4. Use the instance API (`Service.ignite()`), never the static `Spark.get(...)`, so tests can run
   isolated servers on ephemeral ports.

## Consequences

- We run an unmaintained framework on an EOL server line. Upstream won't ship security fixes.
  Phase 5 adds a dependency vulnerability scan and documents any findings.
- Forcing Jetty 9.4.58 is a combination Spark's maintainers never tested; our own HTTP specs are the safety net.
- Spark's default `initExceptionHandler` calls `System.exit(100)` from a background thread if the
  server can't start. `HttpApi` overrides it: the failure is recorded and `start()` throws.
  Verified by `HttpApiStartupSpec` (red run: the test JVM exited with code 100; green: exception thrown).
- Spark binds all interfaces unless told otherwise; `HttpApi` passes `HTTP_BIND_ADDRESS`
  (default `127.0.0.1`), verified by the same spec.
- Jetty 9.4 has no virtual-thread pool. That doesn't matter here: the database pool (10–20
  connections) limits throughput, not request threads.

## Revisit if

A Spark or Jetty 9.4 issue blocks a feature or a security scan finds an exploitable issue we
can't mitigate. The switch target would be Javalin 7, confined to the `api` package.

## Explain-back (student)

- Why is "runs on Java 21" not the same as "safe to depend on"?
- What exactly does the Jetty `eachDependency` rule change, and how would you prove it took effect?
