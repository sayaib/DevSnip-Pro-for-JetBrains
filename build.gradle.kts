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
        intellijIdeaUltimate(providers.gradleProperty("platformVersion"))
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
            <p><b>1.2.1</b>: full DevSnip Pro interface on compatible Android Studio installations.</p>
            <ul>
              <li>Web Browser (JCEF) is now a required dependency, installed with DevSnip Pro on compatible Android Studio versions</li>
              <li>Replaced the reduced native interface with recovery instructions and an Open Plugins Settings button when the embedded browser cannot start</li>
              <li>Requires IDE 2025.3.1 (build 253.29346.138) or newer and an available JCEF module; Android Studio requires a compatible Web Browser (JCEF) plugin</li>
            </ul>
            <p><b>1.2.0</b>: up to date with DevSnip Pro for VS Code 11.88.0.</p>
            <ul>
              <li>Cloud databases: Amazon RDS, Aurora, DynamoDB and Redshift; Azure SQL, Cosmos DB and Azure Database for PostgreSQL and MySQL; Google Cloud SQL, Spanner, Firestore, BigQuery and AlloyDB, with IAM, Entra ID and gcloud sign-in</li>
              <li>Faster large tables: counts once per filter, row estimates for big tables, server-side timeouts, and only visible rows drawn</li>
              <li>Theme Studio: accent, background, corners, borders, shadows and fonts for every DevSnip Pro page; IDE Adaptive pairs a dark and a light theme with the IDE; five new themes</li>
              <li>Code previews with line numbers and highlighting for about 40 languages; a cURL Converter with 17 targets; a rebuilt API Response Inspector</li>
              <li>A Feedback button on the Milestones &amp; Points page</li>
              <li>Fixed: large query results are no longer read into memory before being capped; idle extension hosts no longer collect messages; steadier page messaging and sidebar updates</li>
            </ul>
            <p><b>1.1.0</b>: up to date with DevSnip Pro for VS Code 11.84.0.</p>
            <ul>
              <li>Tool hubs with a category explorer, recent tools, collapsible groups and a compact view; tools organised into subsections</li>
              <li>Tools open empty and run when you press Run, with an optional Live preview; results show status and time, are marked stale when inputs change, and have Run again and Clear results</li>
              <li>Clicking a security finding opens the file at that line</li>
              <li>Fixed: context menu entries that were hidden (Create Snippet, JWT Decoder, Scan for Vulnerabilities, Open in CSV &amp; Excel Viewer) and commands wrongly listed in Find Action</li>
              <li>Less work on the UI thread at startup and when the extension host starts; faster snippet completion</li>
            </ul>
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
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2025.3.1")
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2026.2.3")
            // The Marketplace also verifies against the current EAP.
            create(IntelliJPlatformType.IntellijIdeaUltimate, "263.7307.44")
            // ./gradlew verifyPlugin -PverifyAllIdes also checks the other IDEs (about 10 GB of downloads);
            // -PverifyAllIdes=WebStorm,PyCharmProfessional checks only those.
            val others = providers.gradleProperty("verifyAllIdes").orNull
            if (others != null) {
                val chosen = others.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                listOf(
                    IntelliJPlatformType.WebStorm to "2026.2.4",
                    IntelliJPlatformType.PyCharmProfessional to "2026.2.4",
                    IntelliJPlatformType.GoLand to "2026.2.4",
                    IntelliJPlatformType.PhpStorm to "2026.2.4",
                    IntelliJPlatformType.RubyMine to "2026.2.4",
                    IntelliJPlatformType.CLion to "2026.2.4.1",
                    IntelliJPlatformType.Rider to "2026.2.4",
                ).filter { (type, _) -> chosen.isEmpty() || type.name in chosen }.forEach { (type, version) -> create(type, version) }
            }
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
