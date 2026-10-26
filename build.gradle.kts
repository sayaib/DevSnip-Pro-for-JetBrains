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

configurations.all {
    exclude(group = "org.slf4j")
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    // YAML 1.2 parsing for the OpenAPI toolkit (configured for the core schema).
    implementation("org.yaml:snakeyaml:2.2")

    // The Database Client's drivers (the extension's adapters run in the extension host; these do the I/O).
    // Logging goes through the IDE's own SLF4J, so no SLF4J jar is bundled.
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.4.1") { exclude(group = "com.github.waffle") }
    implementation("com.microsoft.sqlserver:mssql-jdbc:12.8.1.jre11")
    implementation("com.oracle.database.jdbc:ojdbc11:23.5.0.24.07")
    implementation("org.mongodb:mongodb-driver-sync:5.2.1")
    implementation("redis.clients:jedis:5.2.0") { exclude(group = "com.google.code.gson") }

    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
        testFramework(TestFrameworkType.Platform)
    }

    // A real PostgreSQL for the database driver tests (DbBridgeTest).
    testImplementation("io.zonky.test:embedded-postgres:2.0.7") { exclude(group = "io.zonky.test.postgres") }
    testImplementation(enforcedPlatform("io.zonky.test.postgres:embedded-postgres-binaries-bom:16.4.0"))
    // The binary for the machine the tests run on (the library refuses two that both fit).
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
    val pgPlatform = when {
        os.contains("mac") -> if (arm) "darwin-arm64v8" else "darwin-amd64"
        os.contains("win") -> "windows-amd64"
        else -> if (arm) "linux-arm64v8" else "linux-amd64"
    }
    testRuntimeOnly("io.zonky.test.postgres:embedded-postgres-binaries-$pgPlatform")
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
            <p><b>1.0.0</b>: every feature of DevSnip Pro for VS Code.</p>
            <ul>
              <li>The extension's own code now runs in the IDE (an extension host per project): the Tools sidebar with points and level, Milestones &amp; Points (quests, streaks, rewards, theme unlocks), and points for the REST client's premium features</li>
              <li>Database Client: PostgreSQL, CockroachDB, MySQL, MariaDB, SQL Server, Oracle, SQLite, ClickHouse, MongoDB, Redis</li>
              <li>CSV &amp; Excel viewer with editing; snippets in code completion; Create / Saved / Framework Snippets</li>
              <li>Insert Debug Log and Clean Debug Logs, Remove Unused Imports, README manager, Dependencies &amp; Installation</li>
              <li>Security Hub with workspace, cloud and dependency audits and the endpoint scanner</li>
              <li>Browse All Tools, Search All Tools, Choose Theme, Get Started, What's New; editor and Project view menus; Settings | Tools | DevSnip Pro</li>
              <li>Works on 2026.2 and later, where the embedded browser is a bundled plugin running out of process</li>
            </ul>
            <p><b>0.1.2</b>: compatibility with IntelliJ IDEA 2026.3.</p>
            <ul>
              <li>Tabs, generated files and saved reports open through APIs that are current on 2026.3 (no deprecated or scheduled-for-removal calls); nothing changes in how they open</li>
            </ul>
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

    // ./gradlew verifyPlugin checks binary compatibility with the oldest IDE, a current one and the EAP.
    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaCommunity, providers.gradleProperty("platformVersion"))
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2026.2.3")
            // The Marketplace also verifies against the current EAP.
            create(IntelliJPlatformType.IntellijIdeaUltimate, "263.7307.44")
        }
    }
}

// DEVSNIP_SELFTEST=<project folder> ./gradlew runIde opens that project and runs DevSnipSelfTest (results in idea.log).
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask>("runIde") {
    val selfTest = providers.environmentVariable("DEVSNIP_SELFTEST")
    if (selfTest.isPresent) {
        jvmArgumentProviders += CommandLineArgumentProvider { listOf("-Ddevsnip.selfTest=exit", "-Didea.trust.all.projects=true") }
        argumentProviders += CommandLineArgumentProvider { listOf(selfTest.get()) }
    }
}

tasks.test {
    useJUnitPlatform()
    // Run Task.Backgroundable asynchronously in the headless test IDE, as in a real IDE.
    systemProperty("intellij.progress.task.ignoreHeadless", "true")
}

// The test classpath, for webview/test/db-servers.mjs (it starts DbBridgeServer with it).
val serverStdlib = configurations.create("devsnipServerStdlib")
dependencies { serverStdlib("org.jetbrains.kotlin:kotlin-stdlib:2.1.20") }

tasks.register("writeTestClasspath") {
    // The IDE provides the Kotlin stdlib to the plugin; the standalone server needs its own.
    val classpath = sourceSets["test"].runtimeClasspath + serverStdlib
    val target = layout.buildDirectory.file("test-classpath.txt")
    dependsOn("testClasses")
    outputs.file(target)
    doLast { target.get().asFile.writeText(classpath.files.joinToString(File.pathSeparator)) }
}
