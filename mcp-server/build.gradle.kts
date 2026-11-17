import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        apiVersion = KotlinVersion.KOTLIN_2_3
        languageVersion = KotlinVersion.KOTLIN_2_3
        freeCompilerArgs.add("-Xjdk-release=21")
    }
}

dependencies {
    api(project(":core"))

    // Provided by the IDE at runtime (excluded from the plugin distribution by the root project).
    implementation(kotlin("stdlib"))
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.mcp.sdk.server)
    implementation(libs.ktor.server.cio)
    // SDK 0.9.0 responds with JSON-RPC objects but does not install ContentNegotiation itself.
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mcp.sdk.client)
    testImplementation(libs.ktor.client.cio)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
