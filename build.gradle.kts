import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.intellij.platform)
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
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

// The IDE provides the Kotlin stdlib and kotlinx.coroutines; plugins must not bundle their own copies.
// See https://plugins.jetbrains.com/docs/intellij/using-kotlin.html
configurations.named("runtimeClasspath") {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-common")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-bom")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-slf4j")
    exclude(group = "org.jetbrains", module = "annotations")
    // Ktor pulls kotlin-reflect 2.1.x; the IDE ships kotlin-reflect matching its own stdlib.
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
    // Ktor's ANSI console coloring (native code); meaningless inside the IDE.
    exclude(group = "org.fusesource.jansi", module = "jansi")
    // The IDE provides SLF4J (routed to idea.log); bundling a second API copy would log nowhere.
    exclude(group = "org.slf4j", module = "slf4j-api")
}

dependencies {
    implementation(project(":mcp-server"))
    compileOnly(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("stdlib"))
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    intellijPlatform {
        intellijIdea(libs.versions.intellijPlatform)
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "net.khoango.mavencentralmcp"
        name = "Maven Central MCP"
        version = project.version.toString()
        ideaVersion {
            // Lowest platform that bundles the Kotlin stdlib/coroutines versions required by MCP SDK 0.9.0.
            sinceBuild = "261"
            // Only claim the range that has actually been verified.
            untilBuild = "262.*"
        }
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdea, libs.versions.intellijPlatform.get())
            // Optionally verify against a locally installed IDE: -PverifyLocalIde=/path/to/ide
            val localIde = providers.gradleProperty("verifyLocalIde").orNull
            if (localIde != null) {
                local(file(localIde))
            } else {
                create(IntelliJPlatformType.IntellijIdea, "2026.2")
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

// Exposes the plugin version to runtime code without relying on internal platform APIs.
tasks.processResources {
    val pluginVersion = project.version.toString()
    inputs.property("pluginVersion", pluginVersion)
    filesMatching("**/plugin-version.properties") {
        expand("pluginVersion" to pluginVersion)
    }
}
