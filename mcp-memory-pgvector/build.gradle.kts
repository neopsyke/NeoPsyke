import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.testing.Test

plugins {
    kotlin("jvm")
    application
}

group = "ai.neopsyke"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // MCP SDK (server-side)
    implementation("io.modelcontextprotocol:kotlin-sdk:0.6.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // HTTP client for embedding API
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON (Jackson, matching main project)
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")

    // PostgreSQL JDBC driver
    implementation("org.postgresql:postgresql:42.7.4")

    // pgvector Java support
    implementation("com.pgvector:pgvector:0.1.6")

    // Logging (matching main project)
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    implementation("org.slf4j:slf4j-simple:2.0.12")

    // Test
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // Root-project --tests filters should not fail this unrelated subproject when no tests match.
    filter {
        isFailOnNoMatchingTests = false
    }
}

val integrationTest by sourceSets.creating {
    kotlin.srcDir("src/integrationTest/kotlin")
    resources.srcDir("src/integrationTest/resources")
    compileClasspath += sourceSets["main"].output + configurations["testRuntimeClasspath"]
    runtimeClasspath += output + compileClasspath
}

configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

tasks.register<Test>("integrationTest") {
    description = "Runs manual DB-backed integration evals for the pgvector memory server."
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    shouldRunAfter(tasks.test)
    useJUnitPlatform()
}

tasks.register("memoryDbEval") {
    description = "Manual-only DB eval for semantic dedupe, fact upsert, and namespace isolation."
    group = "verification"
    dependsOn("integrationTest")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjdk-release=21")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

application {
    mainClass.set("ai.neopsyke.mcp.memory.MemoryServerKt")
}

tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "ai.neopsyke.mcp.memory.MemoryServerKt"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    with(tasks.jar.get())
}
