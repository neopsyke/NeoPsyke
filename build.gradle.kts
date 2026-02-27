import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.JavaExec
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.23"
    application
}

group = "ai.psyke"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))

    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    implementation("org.slf4j:slf4j-simple:2.0.12")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(23)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(23))
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        // Kotlin 1.9.x supports bytecode targets up to 21; use JDK 23 toolchain but emit 21-compatible bytecode.
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    // Align Java bytecode target with Kotlin.
    options.release.set(21)
}

application {
    mainClass.set("psyke.ApplicationKt")
}

tasks.named<JavaExec>("run") {
    // Forward optional simple-logger level from Gradle JVM/env to the app JVM.
    val configuredLogLevel = System.getProperty("org.slf4j.simpleLogger.defaultLogLevel")
        ?: System.getenv("PSYKE_LOG_LEVEL")
    if (!configuredLogLevel.isNullOrBlank()) {
        systemProperty("org.slf4j.simpleLogger.defaultLogLevel", configuredLogLevel)
    }

    // Keep interactive CLI loops alive by wiring terminal stdin to the app process.
    standardInput = System.`in`
}
