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
    // YAML 1.2 parsing for the OpenAPI toolkit (configured for the core schema).
    implementation("org.yaml:snakeyaml:2.2")

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
        // The name, vendor and description live in plugin.xml only.
        version = providers.gradleProperty("pluginVersion")
        changeNotes = """
            <p><b>0.1.1</b>: compatibility with current IDEs.</p>
            <ul>
              <li>The scanners' "Run in Terminal" fixes now run in the Run tool window (output, exit code and Stop), using the IDE's own execution API instead of the Terminal plugin's deprecated one; the Terminal plugin is no longer a dependency</li>
              <li>Workspace trust uses the IDE's public trust API where the IDE has it, instead of the deprecated, experimental one</li>
            </ul>
            <p><b>0.1.0</b>: first release: DevSnip Pro's look, Tools sidebar, themes and toolkit from VS Code, the REST API client and the OSV and SCA scanners.</p>
            <ul>
              <li>The Tools sidebar with 13 sections, search, favourites, recents and the 13 DevSnip themes (System Default follows the IDE theme)</li>
              <li>100 toolkit tools for backend, frontend, mobile, text, converters, database, testing, Git, DevOps, security, AI &amp; ML and RAG work, each in Find Action and Tools | DevSnip Pro</li>
              <li>The REST API Client as in VS Code: request tabs, collections, history, environments, cookies and the Software Developer / AI &amp; ML tools</li>
              <li>REST and GraphQL requests with params, headers and JSON, text or form bodies; Bearer, Basic, API key and OAuth 2.0 authentication</li>
              <li>Environments with {{variables}}; secret variables and OAuth tokens stored in the IDE password safe</li>
              <li>Retries, redirects, timeouts, proxy and SSL options, cookies, cURL import/export and request history</li>
              <li>OSV Vulnerability Scanner and SCA Scanner for npm, PyPI, Maven, Gradle, Go, Cargo, NuGet, Composer, RubyGems, Pub and more, with confirmed fixes</li>
              <li>Settings | Tools | DevSnip Pro Scanners</li>
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
