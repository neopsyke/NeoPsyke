import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.JavaExec
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.2.0"
    application
}

group = "ai.neopsyke"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))

    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    implementation("org.slf4j:slf4j-simple:2.0.12")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("io.modelcontextprotocol:kotlin-sdk:0.6.0")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.0")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")

    testImplementation(kotlin("test"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.test {
    useJUnitPlatform()
    environment("EGO_SCRATCHPAD_DEBUG_CAPTURE_ENABLED", "true")
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        // Emit Java 21-compatible bytecode, even when Gradle runs on a newer JDK.
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjdk-release=21")
    }
}

tasks.withType<JavaCompile>().configureEach {
    // Align Java bytecode target with Kotlin.
    options.release.set(21)
}

application {
    mainClass.set("ai.neopsyke.ApplicationKt")
}

tasks.named<JavaExec>("run") {
    // Forward optional simple-logger level from Gradle JVM/env to the app JVM.
    val configuredLogLevel = System.getProperty("org.slf4j.simpleLogger.defaultLogLevel")
        ?: System.getenv("NEOPSYKE_LOG_LEVEL")
    if (!configuredLogLevel.isNullOrBlank()) {
        systemProperty("org.slf4j.simpleLogger.defaultLogLevel", configuredLogLevel)
    }
    val configuredLogFile = System.getProperty("org.slf4j.simpleLogger.logFile")
        ?: System.getenv("NEOPSYKE_LOG_FILE")
    if (!configuredLogFile.isNullOrBlank()) {
        systemProperty("org.slf4j.simpleLogger.logFile", configuredLogFile)
    }

    // Keep interactive CLI loops alive by wiring terminal stdin to the app process.
    standardInput = System.`in`
}
