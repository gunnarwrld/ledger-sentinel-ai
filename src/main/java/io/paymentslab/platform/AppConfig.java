package io.paymentslab.platform;

import java.util.Map;

/**
 * Runtime configuration, read from environment variables. Blank values count as unset. The
 * defaults match docker-compose.yml and are for local development only: loopback-only HTTP,
 * synthetic data, a local-only password. Containers set {@code HTTP_BIND_ADDRESS=0.0.0.0}.
 */
public record AppConfig(
        String httpBindAddress,
        int httpPort,
        String dbUrl,
        String dbUser,
        String dbPassword,
        int dbPoolSize,
        String redisUrl) {

    public static AppConfig fromEnv(Map<String, String> env) {
        return new AppConfig(
                stringVar(env, "HTTP_BIND_ADDRESS", "127.0.0.1"),
                // 0 asks the OS for a free port (used by tests).
                intVar(env, "HTTP_PORT", 8080, 0, 65_535),
                stringVar(env, "DB_URL", "jdbc:postgresql://localhost:55432/payments"),
                stringVar(env, "DB_USER", "payments"),
                stringVar(env, "DB_PASSWORD", "payments-local-only"),
                // PostgreSQL's default max_connections is 100; a bigger pool is almost always a mistake.
                intVar(env, "DB_POOL_SIZE", 10, 1, 100),
                stringVar(env, "REDIS_URL", "redis://localhost:56379"));
    }

    private static String stringVar(Map<String, String> env, String name, String defaultValue) {
        String value = env.get(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static int intVar(Map<String, String> env, String name, int defaultValue, int min, int max) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
        }
        return parsed;
    }

    /** Records print every component by default; never let the password reach a log line. */
    @Override
    public String toString() {
        return "AppConfig[httpBindAddress=%s, httpPort=%d, dbUrl=%s, dbUser=%s, dbPassword=***, dbPoolSize=%d, redisUrl=%s]"
                .formatted(httpBindAddress, httpPort, dbUrl, dbUser, dbPoolSize, redisUrl);
    }
}
