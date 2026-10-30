# DevSnip Pro for JetBrains

**DevSnip Pro: API Client & Developer Toolkit** for IntelliJ IDEA and every other
IntelliJ-based IDE (WebStorm, PyCharm, GoLand, Rider, PhpStorm, Android Studio, …).

It brings the full [DevSnip Pro VS Code extension](https://github.com/sayaib/DevSnip-Pro)
to JetBrains IDEs, with the same look, the same Tools sidebar and the same results:

- a REST, GraphQL and WebSocket client
- a database client for ten databases
- vulnerability and dependency scanners
- a CSV & Excel editor
- snippets in code completion
- debug-log tools
- 100 developer tools, from cURL conversion to RAG planning

Everything runs locally in the IDE. No account, API key or DevSnip server is needed.

---

## Contents

- [Getting started](#getting-started)
- [Features](#features)
  - [Tools sidebar](#tools-sidebar)
  - [REST API Client](#rest-api-client)
  - [Database Client](#database-client)
  - [Security scanners](#security-scanners)
  - [CSV & Excel viewer](#csv--excel-viewer)
  - [Code & productivity](#code--productivity)
  - [Debug logs](#debug-logs)
  - [Developer toolkit (100 tools)](#developer-toolkit-100-tools)
  - [Points, milestones and themes](#points-milestones-and-themes)
- [Where to find everything](#where-to-find-everything)
- [Settings](#settings)
- [Privacy and security](#privacy-and-security)
- [Requirements and compatibility](#requirements-and-compatibility)
- [Known limitations](#known-limitations)
- [Development](#development)
- [Publishing](#publishing)
- [Project layout](#project-layout)
- [Third-party components](#third-party-components)

---

## Getting started

1. **Install.** In *Settings | Plugins | Marketplace*, search for **DevSnip Pro**. To install
   a downloaded ZIP instead, use *Settings | Plugins | ⚙ | Install Plugin from Disk…*. No
   restart is needed.
2. **Open it.** Use the **DevSnip Pro** tool window on the right, *Tools | DevSnip Pro*, or
   *Find Action* (⌘⇧A / Ctrl+Shift+A) and type `DevSnip Pro`.
3. **Take the tour.** *DevSnip Pro: Get Started* walks through sending a request, trying
   an AI tool, running a security scan, connecting a database and saving a snippet.

Nothing has to be configured first. Every tool works with its defaults.

## Features

### Tools sidebar

The **DevSnip Pro** tool window holds the Tools sidebar:

- 14 sections with every tool
- search (⌘K / Ctrl+K)
- filters, favourites, and recently and most used tools
- hover cards with a description of each tool
- a rank card with points and level
- an onboarding guide, a What's New card and the theme picker

Tools open as editor tabs. **Browse All Tools** opens the full catalogue, and **Search All
Tools** finds a tool by name or regular expression. The **Security Hub** and **Browse AI & ML
Tools** gather the tools of their area on one page.

### REST API Client

A full API client in an editor tab.

**Requests**
- Any HTTP method, with params, headers and auth: Bearer, Basic, API key and OAuth 2.0.
- JSON, text, form and GraphQL bodies.
- Request tabs, saved requests, collections (import and export), and history (redacted,
  exportable as JSON or CSV).
- Environments with `{{variables}}`, and cookies.
- cURL import and export.

**Responses**
- A response viewer with search, timing and size.
- Each network error gets a plain explanation and a **Fix in…** button.

**Testing tools**
- Assertions, batch tests, response diff and request chaining.
- Load tests, mock generator, SDK export and code generation.
- JWT inspector, OAuth 2.0 helper, WebSocket client and endpoint security scan.

**AI & ML tools**
- LLM requests and streaming.
- Model comparison and benchmarks.
- Embeddings, vector search, RAG, and agent and prompt tests.

Requests are sent by the plugin's own engine, using the IDE's proxy settings. The
defaults are a 30 s timeout, retries on 429/502/503/504, 5 redirects and SSL
verification; all of them can be changed in the client's Settings tab. Secret-looking
environment variables and OAuth tokens are kept in the IDE password safe.

### Database Client

Supported databases: **PostgreSQL, CockroachDB, MySQL, MariaDB, SQL Server, Oracle, SQLite,
ClickHouse, MongoDB and Redis**.

- An explorer of schemas, tables and collections.
- A grid where rows can be edited.
- A query console.
- A connection store. Paste a connection string to connect; passwords go to the IDE
  password safe.

The drivers are bundled: JDBC, the MongoDB Java driver and Jedis. SQLite runs inside the
IDE. The toolkit's SQL Formatter & Linter, SQL Query Helper, Database Connection Strings and
SQL → MongoDB Query tools sit in the same sidebar section.

### Security scanners

- **OSV Vulnerability Scanner**
  - Finds known CVEs in npm, PyPI, Maven, Go, Cargo, RubyGems, NuGet and other
    dependencies, including Maven and Gradle trees, using [OSV.dev](https://osv.dev).
  - Adds CISA KEV and optional NVD data.
  - Offers fixes: *Apply* is an undoable edit with a diff preview, and *Run in Terminal*
    runs the fix command in your shell.
- **SCA Scanner**: a dependency scan that is aware of Spring Boot. It reports CVEs, CISA
  known-exploited vulnerabilities and deprecated packages, and suggests fixes that are safe
  for the BOM.
- **Workspace Security Audit**: finds secrets, injection risks and unsafe code in the
  project.
- **Cloud & Container Audit**: audits Terraform, Kubernetes, Docker and IAM configuration.
- **Dependency & Config Check**: checks lockfiles, known advisories and CI hardening.
- **Endpoint Security Scan**: checks a live endpoint's security headers, HSTS, CSP, CORS,
  TLS and cookies.

Right-click files or folders in the Project view and choose *Scan for Vulnerabilities* to
scan only those. Maven and Gradle run only in trusted projects, and only after you allow it.

### CSV & Excel viewer

`.xlsx` and `.xlsm` files open in the spreadsheet viewer. `.csv`, `.tsv` and `.tab` files
get a **Table** tab at the bottom of the editor.

- Search, sort, see column statistics and export.
- Edit cells with undo and redo, then Save or Save All.
- Choose the delimiter and whether the first row is a header.

### Code & productivity

- **Snippets in code completion.**
  - Type `ds-` for the framework snippets: Spring Boot, ASP.NET Core, FastAPI, Django,
    Flask, React, Next.js, Express, Flutter, SwiftUI, pytest, xUnit, JUnit and loggers.
  - Your own snippets appear in code completion too.
  - Snippets expand as live templates, with tab stops, choices and variables.
- **Saved Snippets / Create Snippet**: save the selection as a reusable snippet, then browse,
  insert and manage your snippets.
- **Remove Unused Imports**: finds imports that are never used and removes them.
- **Dependencies & Installation**: checks and updates dependencies for npm, pnpm, Yarn, Bun,
  pip, Poetry, uv, Maven, Gradle, NuGet and pub.
- **Unused Dependencies**: lists npm, Python and Flutter packages that nothing uses, and gives
  the command to remove them.
- **README Viewer & Manager**: browse, preview, create and edit the project's README files.

### Debug logs

- **Insert Debug Log** adds a labelled log line for the variable at the caret, for example
  `console.log("[app.js:2] total:", total);`. It works in JS/TS, Python, Java, Kotlin, C#,
  Dart and Swift. The shortcut is Ctrl+Alt+Shift+L on macOS keymaps; you can assign one
  in *Settings | Keymap*.
- **Clean Debug Logs** lists the debug logs in the project and removes the ones you choose.
  **Remove Debug Logs in This File** cleans the current file. Both can be undone.

### Developer toolkit (100 tools)

Each tool opens with a form, presets and examples. **Use Editor** reads the current
selection, and the results update as you type. Outputs can be copied, inserted at the caret,
opened, saved, or written into the project. Existing files are never overwritten without
asking.

| Section | Tools |
|---|---|
| **Backend & API** | cURL Converter, API Response Inspector, URL & Query String Tool, CORS Builder & Debugger, OpenAPI / Swagger Toolkit, GraphQL Formatter & Types, API Resource Scaffolder |
| **Web & Frontend** | React / Next.js Generator, HTML → JSX, CSS Units & Fluid Type, Color Converter & Palette, SEO & Social Meta Tags, Cache-Control Builder |
| **Mobile Development** | adb & simctl Commands, Deep Links & Universal Links, App Permissions, Local API from Devices, Environments & Flavors, Signing & Keystores, SDK & Build Compatibility, Mobile CI Workflow, dp / px / pt & Asset Sizes |
| **Text & Formatters** | JSON / YAML / XML Formatter, XML → JSON Converter, Diff Checker, Regex Tester & Library, Case Converter & Text Tools |
| **Encoders & Converters** | Encode / Decode, Timestamp Converter, UUID & ID Generator, JSON to Types, Data Converter |
| **Database** | SQL Formatter & Linter, SQL Query Helper, Database Connection Strings, SQL → MongoDB Query |
| **Testing & Debugging** | Run & Debug Configurations, Build Error Explainer, Log Analyzer & Formatter, Mock Data Generator, JSON Schema Validator, Port & Network Toolkit |
| **Git & Version Control** | Git Command Recipes, .gitignore Generator, Semver & App Versions |
| **DevOps & Cloud** | Dockerfile, Docker Compose, docker run → Compose, CI Pipeline, .env Checker, Kubernetes & Helm, Cloud Deploy Workflow, Cron Expression Helper, Nginx Config, Health Check Endpoints, Terraform Starter, PM2 Ecosystem, Observability Starter |
| **Security & Auth** | JWT Decoder & Signer, Hash, HMAC & Webhooks, OAuth 2.0 & PKCE, CSP & Security Headers, Cookie Inspector & Builder, Certificate & SSL Pinning |
| **AI & ML** | Prompt Builder, LLM Models, Tokens & Cost, LLM Client Setup, LLM Output & JSON, LLM API Tester, JSON → TOON for Prompts, AI App Starter, AI/ML Code Snippets, GPU Memory & Speed, Model Serving Starter, Model Metrics, Dataset Split Planner, Learning-Rate Schedule, Model Card |
| **Data & RAG** | Chunking Tester, RAG Pipeline Generator, Vector Store Setup, Embedding Model Guide, Retrieval Configuration, Context Window Budget, Grounded Prompt Assembler, Chunk & Index Size Calculator, Retrieval Evaluation, Answer Grounding Checker, Vector Similarity, Near-Duplicate Chunk Finder, Hybrid Search Fusion (RRF), Chunk Metadata Validator, Data Transformer, Data Profiler & Quality Check, JSON Lines Inspector, Schema Viewer & Diff, Partition & File Size Planner, Spark Cluster & Cost, Delta Lake Log Analyzer |

The tools run the VS Code extension's own code, so they give exactly the same results.
They run locally and do not call the network.

### Points, milestones and themes

- **Points**: every tool run earns points. There is also a daily check-in and bonus,
  streaks and streak freezes, and levels.
- **Milestones and challenges**: milestones, daily quests, weekly events and a weekly recap,
  lucky finds, a daily spin, the quiz and Bit Sprint.
- **Rewards**: mystery boxes, cosmetics and theme unlocks. The **Milestones & Points** page
  shows your progress.
- **Premium features**: some advanced features of the REST client cost points, which are
  refunded if the feature fails. **Points & Premium Features** lists them.
- **Themes**: *System Default* follows your IDE theme, light or dark, and changes with it.
  The 12 DevSnip themes are Dark, Midnight, Dracula, Monokai, Nord, Cyberpunk, Solarized,
  Ember, Synthwave, Aurora, Light and High Contrast. They restyle every DevSnip page at once
  and unlock with points. Choose one with **Choose Theme** or the sidebar's theme button.

Points are kept locally in the IDE. There is no payment and no server.

## Where to find everything

| Place | What is there |
|---|---|
| **DevSnip Pro** tool window | The Tools sidebar |
| *Find Action* / *Search Everywhere* | Every command, as `DevSnip Pro: …` |
| *Tools \| DevSnip Pro* | Show Tools Sidebar and every command |
| Editor right-click → *DevSnip Pro* | Create Snippet, Insert Debug Log, Remove Debug Logs in This File, Clean Debug Logs, Remove Unused Imports, and the tools that work on the selection (JSON Formatter, JWT Decoder, cURL Converter, HTML → JSX, Build Error Explainer and more) |
| Project view right-click | *Scan for Vulnerabilities* (OSV) and *Open in CSV & Excel Viewer* |
| Spreadsheet editor title bar | *Open in CSV & Excel Viewer* |
| Code completion | Framework snippets (`ds-`) and your own snippets |

## Settings

All settings are optional.

- *Settings | Tools | DevSnip Pro* holds the general settings:
  - REST timeout
  - confirmation before cleaning debug logs
  - security scan limits
  - progress notifications
  - **Share anonymous usage statistics**, which is off by default
- *Settings | Tools | DevSnip Pro | DevSnip Pro Scanners* holds the OSV and SCA settings:
  - Maven/Gradle resolution, POM downloads, health checks, and KEV and NVD lookups
  - an optional NVD API key, which is kept in the password safe
- **REST API Client → Settings tab**: timeout, retries, redirects, SSL verification and an
  HTTP proxy. When the proxy is left blank, the IDE's proxy settings are used.

## Privacy and security

- **Local first.** Nothing leaves the machine except the requests you send yourself, plus
  these:
  - the scanners' lookups: package names and versions to OSV.dev, package registries and
    Maven Central, and CVE ids to NVD
  - the endpoint scan of the URL you enter
  - anonymous usage statistics, only if you turn them on
- **Password safe.** Database passwords, secret-looking environment variables (`token`,
  `secret`, `password`, `key`, `auth`, `cookie`, `session`, …), OAuth tokens and the NVD key
  are stored in the IDE password safe. That is the macOS Keychain, Windows Credential
  Manager, KeePass or libsecret. The settings files hold only blank placeholders.
- **History without secrets.** Credential-like query values are redacted
  (`api_key=[redacted]`), and typed tokens are never saved. Exports leave out request
  bodies, and CSV exports are protected against formula injection.
- **Redirects.** `Authorization`, `Cookie` and `Proxy-Authorization` are dropped when a
  redirect goes to another host.
- **Files and processes.**
  - Files: the plugin's pages read and write only the project, the plugin's own storage and
    files you pick.
  - Processes: only package managers, build tools and Node, and only in trusted projects.
  - Terminal commands from the scanners run only after you confirm them.
- **Logs.** The logs contain only method, redacted URL, status and timing; no headers,
  bodies or credentials.

## Requirements and compatibility

- Any IntelliJ-based IDE **2024.1 or newer**, including 2026.2 and later, where the
  embedded browser runs out of process. The plugin depends only on
  `com.intellij.modules.platform`.
- The pages use the IDE's embedded browser (JCEF), which every standard JetBrains IDE
  includes. Without it, the tool window falls back to a native REST API Client and the seven
  Backend & API tools.
- The plugin is dynamic: it can be installed, updated and removed without a restart.
- The Plugin Verifier finds it compatible with IntelliJ IDEA 2024.1.7, 2026.2.3 and the
  2026.3 EAP, with no deprecated, experimental or internal API usages.

## Known limitations

- **Insert Debug Log shortcut**: it has no default shortcut on the Windows and Linux keymaps,
  where Ctrl+Alt+Shift+L is Reformat File.
- **Unsaved spreadsheet edits**: they are not kept across an IDE restart. The viewer asks
  you to save when you close it.
- **PostgreSQL client certificates**: the key must be PKCS#8 (`BEGIN PRIVATE KEY`).
- **SQL Server**: only SQL Server logins work, as in VS Code.
- **Request bodies**: they are text only, with no multipart or file uploads. The explicit
  proxy is HTTP only, without proxy authentication.
- **Response size**: responses over 100 MB are refused, to protect the IDE's memory.
- **Cookies**: they are simple per-host `name=value` pairs and last for the IDE session.
- **Shared state**: history and environments are shared by all projects.

The full feature-by-feature map, with every platform difference, is in
[docs/MIGRATION.md](docs/MIGRATION.md).

## Development

### How it works

The VS Code extension is the single source of truth, and it is not modified:

- **Pages.** Its pages (HTML, CSS and JavaScript) are shown in the IDE's embedded browser,
  with IntelliJ colours mapped onto the `--vscode-*` variables they use.
- **Extension code.** The extension's own code runs in an *extension host*: a hidden
  browser page, one per project, running on a VS Code API stand-in
  (`webview/shims/vscode-host.js`).
- **IDE side.** The IDE provides what VS Code would: files, editors, dialogs, terminals,
  processes, HTTP, database drivers and the password safe. This code is in
  `src/main/kotlin/pro/devsnip/api/ide/engine/`.
- **REST and Backend & API engines.** The REST client's HTTP engine and the seven Backend &
  API tools are Kotlin ports in `core/`, which has no IntelliJ dependency.

### Build and run

You need JDK 17 or newer. Gradle downloads IntelliJ IDEA Community 2024.1.7 to compile
against, about 1 GB on the first build. No environment variables are needed.

```bash
./gradlew test
```

```bash
./gradlew buildPlugin
```

The ZIP is written to `build/distributions/devsnip-pro-jetbrains-1.1.0.zip`.

```bash
./gradlew runIde
```

This starts a sandbox IDE with the plugin.

### Rebuilding the pages from the VS Code extension

This needs Node 18 or newer, with the extension's source in `../DevSnip-Pro` (or set
`DEVSNIP_VSCODE_REPO`):

```bash
cd webview && npm install && npm run build && npm test
```

`npm run build` writes the pages and bundles into `src/main/resources/webview`. Building
the plugin does not need Node. `webview/wording.mjs` rewords the extension's text that names
VS Code's UI, such as "Command Palette" → "Find Action".

### Tests

| Command | What it covers |
|---|---|
| `./gradlew test` | 144 unit, integration and headless-IDE tests: REST engine, cURL, OAuth, history and redaction, toolkit parity (256 recorded results), scanners' IDE side, REST client page protocol, extension host, database drivers (embedded PostgreSQL, and MongoDB when `mongod` is installed) |
| `cd webview && npm test` | Every toolkit tool against the extension's TypeScript on Node (249 cases and 50 crypto cross-checks), both scanners, 50 REST client feature messages, the extension host's commands and pages, and SQLite |
| `scripts/db-containers.sh start`, then `./gradlew writeTestClasspath && (cd webview && npm run test:db)` | The Database Client against MySQL, MariaDB, SQL Server, Oracle, ClickHouse and Redis in Docker, compared with the extension's own Node drivers. Run `scripts/db-containers.sh stop` afterwards |
| `DEVSNIP_SELFTEST=/path/to/project ./gradlew runIde` | A self-check in a real IDE: the main pages, the sidebar, the CSV viewer and Insert Debug Log. It writes `DEVSNIP-SELFTEST` lines to the sandbox `idea.log` |
| `scripts/smoke-test-zip.sh <IDE Contents folder> <project>` | The same self-check with the release ZIP installed into a fresh IDE configuration. The project needs a `README.md`, a `data.csv` and a `src/app.js` |
| `./gradlew verifyPlugin` | Plugin Verifier against IDEA 2024.1.7, 2026.2.3 and the 2026.3 EAP |

All automated tests use local servers on `127.0.0.1`. No production or third-party API is
contacted.

## Publishing

The current release checklist, with its test results and measurements, is in
[docs/RELEASE-1.1.0.md](docs/RELEASE-1.1.0.md).

1. **Signing key (one time).** Create a certificate as described in
   [Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html), and
   keep it outside the repository.
2. **First release.** JetBrains requires the first version to be uploaded on the website:

   ```bash
   export CERTIFICATE_CHAIN="$(cat /secure/path/chain.crt)"
   export PRIVATE_KEY="$(cat /secure/path/private.pem)"
   export PRIVATE_KEY_PASSWORD='...'
   ./gradlew signPlugin
   ```

   Upload `build/distributions/devsnip-pro-jetbrains-1.1.0-signed.zip` at
   [plugins.jetbrains.com](https://plugins.jetbrains.com) (your profile → **Add new
   plugin**), with the MIT licence and the source URL. JetBrains reviews new plugins
   before listing them.
3. **Later releases.** Bump `pluginVersion` in `gradle.properties`, then update
   `CHANGELOG.md` and the `changeNotes` in `build.gradle.kts`. Then publish with a
   Marketplace token:

   ```bash
   export PUBLISH_TOKEN='perm:...'
   ./gradlew publishPlugin
   ```

   Set `PUBLISH_CHANNEL=eap` to publish to a pre-release channel.

| Variable | Used by | Purpose |
|---|---|---|
| `CERTIFICATE_CHAIN` | `signPlugin`, `publishPlugin` | PEM certificate chain |
| `PRIVATE_KEY` | `signPlugin`, `publishPlugin` | PEM private key |
| `PRIVATE_KEY_PASSWORD` | `signPlugin`, `publishPlugin` | Key password |
| `PUBLISH_TOKEN` | `publishPlugin` | Marketplace permanent token |
| `PUBLISH_CHANNEL` | `publishPlugin` | Optional; defaults to `default` |

None of these are needed to build, test or run the plugin.

## Project layout

```
src/main/kotlin/pro/devsnip/api/core/          REST engine (no IntelliJ dependency): models, HTTP, OAuth, cURL, errors, history
src/main/kotlin/pro/devsnip/api/core/toolkit/  The seven Backend & API tools in Kotlin (native fallback)
src/main/kotlin/pro/devsnip/api/ide/           REST client service (state, password safe) and native UI
src/main/kotlin/pro/devsnip/api/ide/web/       Sidebar, tool, REST client and scanner tabs, themes, settings
src/main/kotlin/pro/devsnip/api/ide/engine/    Extension host: engine, VS Code API host, panels, editors, terminals, database drivers, actions, snippets
src/main/resources/webview/                    The extension's pages and bundled code (built by webview/build.mjs)
src/main/resources/META-INF/plugin.xml         Plugin descriptor
webview/                                       Page build, VS Code / Node shims and parity tests (Node; not needed to build the plugin)
scripts/                                       Database containers, release ZIP smoke test, toolkit metadata export
docs/                                          Migration map and release notes
src/test/kotlin/                               Unit, integration and headless-IDE tests
```

## Third-party components

- **Bundled in the plugin:** Gson (Apache-2.0), SnakeYAML (Apache-2.0), PostgreSQL JDBC
  (BSD-2-Clause), MariaDB Connector/J (LGPL-2.1), Microsoft JDBC Driver for SQL Server
  (MIT), Oracle JDBC ojdbc11 (Oracle Free Use Terms and Conditions), MongoDB Java driver
  (Apache-2.0), Jedis (MIT) with Apache Commons Pool (Apache-2.0), and org.json (Public
  Domain).
- **In the extension host:** sql.js (MIT), pako (MIT/Zlib) and bson (Apache-2.0).
- **In the pages:** VS Code Codicons (font CC BY 4.0, code MIT), node-forge (BSD-3-Clause),
  @noble/hashes and @noble/curves (MIT), buffer (MIT), path-browserify (MIT) and yaml (ISC).

The licence notices of the bundled JavaScript are kept at the end of `toolkit-runtime.js`
and `api-client-runtime.js`.

## Licence

[MIT](LICENSE)
