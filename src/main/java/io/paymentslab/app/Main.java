package io.paymentslab.app;

import io.paymentslab.platform.AppConfig;
import io.paymentslab.platform.Migrations;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.jooq.CloseableDSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point: {@code serve} (default), {@code migrate} or {@code create-caller}. */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private Main() {}

    public static void main(String[] args) {
        AppConfig config = AppConfig.fromEnv(System.getenv());
        String command = args.length == 0 ? "serve" : args[0];
        switch (command) {
            case "serve" -> serve(config);
            case "migrate" -> migrate(config);
            case "create-caller" -> System.exit(createCaller(config, List.of(args).subList(1, args.length)));
            default -> {
                System.err.println("usage: payments-lab [serve | migrate | create-caller --role CUSTOMER|OPERATOR --name NAME]");
                System.exit(2);
            }
        }
    }

    private static void serve(AppConfig config) {
        log.info("starting with {}", config);
        App app = App.start(config);
        Runtime.getRuntime().addShutdownHook(new Thread(app::close, "shutdown"));
        log.info("listening on port {}", app.port());
    }

    private static void migrate(AppConfig config) {
        // Not through the app's pool: Flyway needs two connections at once, and DB_POOL_SIZE may be 1.
        Flyway flyway = Migrations.flyway(config.dbUrl(), config.dbUser(), config.dbPassword());
        MigrateResult result = flyway.migrate();
        // result.targetSchemaVersion is null when nothing was applied, so ask Flyway directly.
        MigrationInfo current = flyway.info().current();
        log.info("applied {} migration(s); schema now at version {}",
                result.migrationsExecuted, current == null ? "none" : current.getVersion());
    }

    private static int createCaller(AppConfig config, List<String> args) {
        CreateCallerCommand.Options options;
        try {
            options = CreateCallerCommand.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println(CreateCallerCommand.USAGE);
            return 2;
        }
        // One insert needs one connection, not a pool.
        try (CloseableDSLContext db = DSL.using(config.dbUrl(), config.dbUser(), config.dbPassword())) {
            return CreateCallerCommand.run(db, options, System.out, System.err);
        }
    }
}
