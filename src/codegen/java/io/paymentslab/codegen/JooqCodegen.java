package io.paymentslab.codegen;

import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.Configuration;
import org.jooq.meta.jaxb.Database;
import org.jooq.meta.jaxb.Generate;
import org.jooq.meta.jaxb.Generator;
import org.jooq.meta.jaxb.Jdbc;
import org.jooq.meta.jaxb.Target;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Generates jOOQ classes from the real schema: start a throwaway PostgreSQL container,
 * apply the Flyway migrations, then read the resulting catalog. See docs/adr/0002.
 *
 * <p>Arguments: migrations directory, output directory, PostgreSQL image.
 */
public final class JooqCodegen {

    private JooqCodegen() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("usage: JooqCodegen <migrationsDir> <outputDir> <postgresImage>");
        }
        Path migrations = Path.of(args[0]);
        Path outputDir = Path.of(args[1]);
        DockerImageName image = DockerImageName.parse(args[2]);

        try (PostgreSQLContainer postgres = new PostgreSQLContainer(image)) {
            postgres.start();

            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("filesystem:" + migrations.toAbsolutePath())
                    .load()
                    .migrate();

            GenerationTool.generate(new Configuration()
                    .withJdbc(new Jdbc()
                            .withDriver("org.postgresql.Driver")
                            .withUrl(postgres.getJdbcUrl())
                            .withUser(postgres.getUsername())
                            .withPassword(postgres.getPassword()))
                    .withGenerator(new Generator()
                            .withDatabase(new Database()
                                    .withName("org.jooq.meta.postgres.PostgresDatabase")
                                    .withInputSchema("public")
                                    .withExcludes("flyway_schema_history")
                                    // The plpgsql guard functions are database-side only; Java never calls them.
                                    .withIncludeRoutines(false))
                            .withGenerate(new Generate()
                                    .withRecords(true)
                                    .withPojos(false)
                                    .withJavaTimeTypes(true))
                            .withTarget(new Target()
                                    .withPackageName("io.paymentslab.db")
                                    .withDirectory(outputDir.toAbsolutePath().toString()))));
        }
    }
}
