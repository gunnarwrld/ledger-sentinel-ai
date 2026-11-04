plugins {
    java
    groovy
    application
}

group = "io.paymentslab"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

val postgresImage = providers.gradleProperty("postgresImage").get()
val redisImage = providers.gradleProperty("redisImage").get()
val jooqOutputDir = layout.buildDirectory.dir("generated-src/jooq")

sourceSets {
    main {
        java.srcDir(jooqOutputDir)
    }
    // Small program that turns the Flyway migrations into jOOQ classes (docs/adr/0002).
    create("codegen")
    create("integrationTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

configurations["integrationTestImplementation"].extendsFrom(configurations.testImplementation.get())
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations.testRuntimeOnly.get())

// Spark 2.9.4 bundles Jetty 9.4.48. Force every Jetty module to the newest 9.4 patch (docs/adr/0001).
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group.startsWith("org.eclipse.jetty")) {
            useVersion(libs.versions.jetty.get())
            because("Spark's bundled Jetty 9.4.48 predates published security fixes")
        }
    }
}

dependencies {
    implementation(libs.spark.core)
    implementation(libs.jooq)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.hikari)
    implementation(libs.lettuce)
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.logback.classic)

    "codegenImplementation"(libs.jooq.codegen)
    "codegenImplementation"(libs.flyway.core)
    "codegenImplementation"(libs.flyway.postgresql)
    "codegenImplementation"(libs.testcontainers.postgresql)
    "codegenRuntimeOnly"(libs.postgresql)
    "codegenRuntimeOnly"(libs.slf4j.simple)

    testImplementation(libs.spock.core)
    testImplementation(libs.groovy)
    testImplementation(libs.groovy.json)
    testImplementation(libs.groovy.yaml)
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    "integrationTestImplementation"(libs.testcontainers)
    "integrationTestImplementation"(libs.testcontainers.postgresql)
    "integrationTestImplementation"(libs.jooq)
    "integrationTestImplementation"(libs.hikari)
    "integrationTestImplementation"(libs.flyway.core)
    // LogCapture inspects log events directly (API keys must never be logged).
    "integrationTestImplementation"(libs.logback.classic)
}

val generateJooq = tasks.register<JavaExec>("generateJooq") {
    group = "build"
    description = "Applies the Flyway migrations to a throwaway PostgreSQL container and generates jOOQ classes."
    val migrations = layout.projectDirectory.dir("src/main/resources/db/migration")
    classpath = sourceSets["codegen"].runtimeClasspath
    mainClass = "io.paymentslab.codegen.JooqCodegen"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    inputs.dir(migrations).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("postgresImage", postgresImage)
    outputs.dir(jooqOutputDir)
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(migrations.asFile.absolutePath, jooqOutputDir.get().asFile.absolutePath, postgresImage)
    })
}

tasks.compileJava {
    dependsOn(generateJooq)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("org.jooq.no-logo", "true")
    systemProperty("org.jooq.no-tips", "true")
    systemProperty("postgres.image", postgresImage)
    systemProperty("redis.image", redisImage)
    // Specs read these files directly (ComposeConsistencySpec, OpenApiContractSpec); declare them
    // so editing either one reruns the specs instead of reporting UP-TO-DATE.
    inputs.file("docker-compose.yml").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file("docs/api/openapi.yaml").withPathSensitivity(PathSensitivity.RELATIVE)
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

val integrationTest = tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Runs integration specs against real PostgreSQL and Redis containers (needs Docker)."
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    shouldRunAfter(tasks.test)
    // LedgerModelSpec: -PmodelSeeds=17,42 replays exact seeds; -PmodelRandomSeeds=200 tries new ones.
    providers.gradleProperty("modelSeeds").orNull?.let { systemProperty("model.seeds", it) }
    providers.gradleProperty("modelRandomSeeds").orNull?.let { systemProperty("model.randomSeeds", it) }
}

tasks.check {
    dependsOn(integrationTest)
}

application {
    mainClass = "io.paymentslab.app.Main"
    applicationDefaultJvmArgs = listOf("-Dorg.jooq.no-logo=true", "-Dorg.jooq.no-tips=true")
}
