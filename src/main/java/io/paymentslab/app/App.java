package io.paymentslab.app;

import io.paymentslab.api.HttpApi;
import io.paymentslab.platform.AppConfig;
import io.paymentslab.platform.Database;
import io.paymentslab.platform.FaultInjector;
import io.paymentslab.platform.HealthService;
import io.paymentslab.platform.RedisPinger;
import java.util.List;

/** Wires the application together. Main and the integration specs both start it through here. */
public final class App implements AutoCloseable {

    private final Database database;
    private final RedisPinger redis;
    private final HttpApi api;

    private App(Database database, RedisPinger redis, HttpApi api) {
        this.database = database;
        this.redis = redis;
        this.api = api;
    }

    public static App start(AppConfig config) {
        return start(config, FaultInjector.NONE);
    }

    /** With fault injection, for specs that prove what survives a failure. */
    public static App start(AppConfig config, FaultInjector faults) {
        Database database = new Database(config);
        RedisPinger redis = new RedisPinger(config.redisUrl());
        try {
            HttpApi api = new HttpApi(config.httpBindAddress(), config.httpPort(),
                    new HealthService(database.dsl(), redis), database.dsl(), faults);
            api.start();
            return new App(database, redis, api);
        } catch (RuntimeException e) {
            redis.close();
            database.close();
            throw e;
        }
    }

    public int port() {
        return api.port();
    }

    /** Registered routes, for specs that check every one (for example, that each /v1 route needs a key). */
    public List<String> routes() {
        return api.routes();
    }

    /** Stop taking requests first, then release the dependencies they use. */
    @Override
    public void close() {
        api.stop();
        redis.close();
        database.close();
    }
}
