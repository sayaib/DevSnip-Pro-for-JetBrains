import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.10.5"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")

    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Platform (IDE) tests use the JUnit 3/4 based IntelliJ test framework.
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Match the Kotlin stdlib bundled with the oldest supported IDE.
        apiVersion.set(KotlinVersion.KOTLIN_1_9)
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "pro.devsnip.api"
        name = "DevSnip Pro: REST API Client"
        version = providers.gradleProperty("pluginVersion")
        changeNotes = """
            <p>First preview: the REST API client from DevSnip Pro for VS Code.</p>
            <ul>
              <li>REST and GraphQL requests with params, headers, and JSON, text or form bodies</li>
              <li>Bearer, Basic, API key and OAuth 2.0 authentication</li>
              <li>Environments with {{variables}}; secrets stored in the IDE password safe</li>
              <li>Formatted responses with status, timing, size, headers and error diagnosis</li>
              <li>Retries, redirects, timeouts, proxy and SSL options, cookies</li>
              <li>cURL import/export and request history with JSON/CSV export</li>
            </ul>
        """.trimIndent()
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false

    // Credentials come from the environment only; nothing secret lives in the repository.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // Set PUBLISH_CHANNEL=eap to publish to a pre-release channel instead of the default one.
        channels = providers.environmentVariable("PUBLISH_CHANNEL").map { listOf(it) }.orElse(listOf("default"))
    }

    // ./gradlew verifyPlugin checks binary compatibility with the oldest and a current IDE.
    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaCommunity, providers.gradleProperty("platformVersion"))
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2026.2.3")
        }
    }
}

tasks.test {
    useJUnitPlatform()
    // Run Task.Backgroundable asynchronously in the headless test IDE, as in a real IDE.
    systemProperty("intellij.progress.task.ignoreHeadless", "true")
}
