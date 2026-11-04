package io.paymentslab.platform;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Readiness of the process's dependencies. PostgreSQL is the source of truth, so it gates
 * readiness. Redis is advisory: it is reported but does not make the app unready
 * (DESIGN D5, to be revisited with the fraud outage policy in Phase 4).
 */
public final class HealthService {

    public enum Status { UP, DOWN }

    public record Readiness(boolean ready, Map<String, Status> checks) {}

    private static final Logger log = LoggerFactory.getLogger(HealthService.class);

    private final DSLContext db;
    private final RedisPinger redis;

    public HealthService(DSLContext db, RedisPinger redis) {
        this.db = db;
        this.redis = redis;
    }

    public Readiness readiness() {
        Map<String, Status> checks = new LinkedHashMap<>();
        checks.put("postgres", postgres());
        checks.put("redis", redis.ping() ? Status.UP : Status.DOWN);
        return new Readiness(checks.get("postgres") == Status.UP, checks);
    }

    private Status postgres() {
        try {
            db.selectOne().queryTimeout(1).fetch();
            return Status.UP;
        } catch (RuntimeException e) {
            // Probes poll often; keep failures out of the normal log stream.
            log.debug("postgres readiness check failed", e);
            return Status.DOWN;
        }
    }
}
