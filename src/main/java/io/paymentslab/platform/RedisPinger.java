package io.paymentslab.platform;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Answers "is Redis reachable right now?" quickly and without throwing. Commands fail fast while
 * disconnected instead of queueing, and Lettuce reconnects in the background when Redis returns.
 */
public final class RedisPinger implements AutoCloseable {

    private static final Duration TIMEOUT = Duration.ofMillis(500);

    private final RedisClient client;
    private StatefulRedisConnection<String, String> connection; // guarded by this

    public RedisPinger(String redisUrl) {
        RedisURI uri = RedisURI.create(redisUrl);
        uri.setTimeout(TIMEOUT);
        this.client = RedisClient.create(uri);
        client.setOptions(ClientOptions.builder()
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(TIMEOUT).build())
                .timeoutOptions(TimeoutOptions.enabled(TIMEOUT))
                .build());
    }

    /** Connects lazily, so the app can start while Redis is down. */
    public synchronized boolean ping() {
        try {
            if (connection == null) {
                connection = client.connect();
            }
            return "PONG".equals(connection.sync().ping());
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public synchronized void close() {
        if (connection != null) {
            connection.close();
        }
        client.shutdown(0, 2, TimeUnit.SECONDS);
    }
}
