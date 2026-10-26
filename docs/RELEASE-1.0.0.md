# DevSnip Pro for JetBrains 1.0.0: release report

1.0.0 brings every remaining feature of DevSnip Pro for VS Code (11.81.1) to the JetBrains
plugin. The feature inventory and the feature-to-feature map are in
[MIGRATION.md](MIGRATION.md). Everything below ran on macOS (Apple silicon), JDK 17, Gradle 9.1,
Node 22.

## What was missing in 0.1.x and how it was added

0.1.x had the Tools sidebar (rendered by the IDE), the 100 toolkit tools, the REST API Client and
the two scanners. 45 of the extension's 146 commands were missing, along with points and
milestones, theme locks, onboarding, snippets in completion, the context menus, the custom
editors and most settings.

Instead of rewriting those modules, 1.0.0 adds an **extension host**: the extension's own
`activate()` and modules run in a hidden embedded-browser page per project, and the IDE provides
the VS Code API they call (`EngineHost`). This keeps the business rules (points, daily cap, quests,
streak freezes, reward prices, theme locks), the texts and the data formats identical to VS Code.

| New IDE component | Role |
|---|---|
| `DevSnipEngine` | Starts the host page (offscreen JCEF), routes messages, runs commands with the editor state, answers synchronous Node calls |
| `EngineHost` | The VS Code / Node API the extension calls: files (scoped), find files, documents and edits, dialogs, notifications, quick picks, progress, logs, status bar, password safe, settings, globalState / workspaceState, processes, terminals, HTTP/TLS (via the REST engine) |
| `EnginePanels`, `EnginePanelEditor`, `EngineSidebarView`, `DevSnipToolWindowPanel` | Webview panels as editor tabs, the sidebar view in the tool window |
| `EngineCustomEditor`, `ExcelViewerProvider`, `CsvViewerProvider`, `CustomEditorSaver` | The CSV & Excel viewer as IDE file editors (undo/redo, Save, Save All) |
| `EngineEditors`, `EngineDocuments`, `VsCodeLanguages` | Editor/document snapshots, undoable edits, workspace edits, language ids, document events |
| `VsCodeSnippets`, `SnippetCompletionContributor` | VS Code snippet bodies as live templates; snippets in code completion |
| `EngineTerminals` | Interactive terminals (TerminalExecutionConsole) for `createTerminal` |
| `EngineQuickPick`, `EngineWatchers` | Quick picks (with live preview), file system watchers |
| `DbBridge`, `MongoOps` | JDBC (PostgreSQL, MariaDB/MySQL, SQL Server, Oracle), MongoDB and Redis for the Database Client's driver stand-ins |
| `EngineState`, `DevSnipSettings`, `DevSnipSettingsConfigurable` | Shared globalState (with migration from 0.1.x), the extension's settings and their page |
| `DevSnipActions`, `WhenClause`, `InsertDebugLogAction`, `DevSnipNative` | Every command in Find Action / Tools menu, the context menus with package.json's `when` clauses, the shortcut, and the IDE's own pages |
| `EnginePoints` | The REST API Client's points ledger (premium features cost points again) |
| `DevSnipSelfTest` | A diagnostics run in a real IDE (only with `-Ddevsnip.selfTest`) |

| New web component | Role |
|---|---|
| `webview/src/host-runtime.js` | The extension's activate() for the IDE, walkthrough page, markdown preview |
| `webview/shims/vscode-host.js`, `host-channel.js` | The VS Code API, the async and sync channels |
| `host-fs.js`, `host-child_process.js`, `host-os.js`, `host-util.js`, `host-zlib.js`, `host-globals.js`, `host-fetch.js` | Node built-ins over the IDE |
| `db-pg.js`, `db-mysql2-promise.js`, `db-mssql.js`, `db-oracledb.js`, `db-mongodb.js`, `db-ioredis.js`, `db-wire.js` | The Database Client's Node drivers over `DbBridge` |
| `webview/wording.mjs` | The extension's text that names VS Code's UI, said the JetBrains way (exact phrases, checked at build) |

Reused unchanged from the VS Code extension: every module under `src/` that the host runs
(sidebar, milestone tracker, rewards, quests, activities, premium access, theme service,
onboarding, analytics, snippets, debug logs, hygiene tools, README manager, dependency manager,
security tools and rule engines, database service, adapters and SQL builder, spreadsheet
parser/editor/viewer, tool hub, markdown renderer) and every page script in `media/`.

## Bugs found and fixed during this release

| # | Severity | Problem | Fix |
|---|---|---|---|
| 1 | High | 45 commands, points, milestones, theme locks, onboarding, snippets, menus, custom editors and most settings did not exist | The extension host and the components above |
| 2 | High | REST API Client premium features were free (points were not ported) | Real ledger through the extension's milestone tracker; refunds on failure; parity test now compares with points |
| 3 | Medium | OSV "Scan Workspace" and "Scan for Vulnerabilities" (files) only opened the scanner | They start the scan, on the files chosen (`ScanRequest`, `__scan`) |
| 4 | Medium | Edits from the host called `requestWriting` on the EDT (slow-operation error in the IDE log) | Removed; read-only documents are refused as VS Code does |
| 5 | Medium | The document listener was registered under the root disposable (memory-leak report) | Registered under the extension host |
| 6 | Low | Pages said "VS Code" for the IDE (e.g. "loaded by VS Code at startup", "reload the window") | Build-time wording pass with a check that every phrase still exists |
| 7 | Low | Theme previews of locked themes did not reach the IDE's own pages | The host reports the applied theme; tool, scanner and REST pages follow it |
| 8 | High | Oracle connections failed in the Database Client: the adapter loads the driver with `require("oracledb")` and calls `createPool` on the module, which the stand-in only had as a default export | The stand-in exports the driver's functions and constants as the module's own (found against a real Oracle 23) |
| 9 | High | Redis connections failed: the adapter does `new (require("ioredis"))()`, and the stand-in module was not itself the class | The stand-in is a CommonJS module whose export is the class (found against a real Redis 7) |
| 10 | High | Redis: awaiting the client sent a `THEN` command, because the stand-in turned every property into a Redis command | Promise and introspection properties are never commands |
| 11 | Critical | On IntelliJ 2026.2 and later every embedded-browser page failed: JCEF moved out of the runtime into the bundled "Web Browser (JCEF)" plugin (`com.intellij.modules.jcef`), whose classes a plugin only sees through a dependency. The Plugin Verifier does not report it (it treats JCEF as part of the JDK). 0.1.x had the same problem there | Optional dependency on `com.intellij.modules.jcef` (`devsnip-jcef.xml`): JCEF's classes are visible on 2026.2+, and 2024.x keeps using the runtime's |
| 12 | Critical | On 2026.2+ the extension host never started: JCEF runs out of process there and ignores a message query created after its browser | The query and handlers are created first, then the browser (`createImmediately()`) |
| 13 | Critical | On 2026.2+ the extension's synchronous file calls returned nothing: out-of-process JCEF no longer passes `prompt()` to the IDE (Saved Snippets, Get Started, What's New, SQLite were affected) | Synchronous calls go to the IDE's built-in local server (`EngineSyncServer`), with a random token per host page and the page's CSP allowing only that address |
| 14 | Low | Looking up the built-in server's port waited on the UI thread (IDE error on 2026.2) | Looked up on a background thread before the host page is made |

## Test results

| Check | Command | Result |
|---|---|---|
| Unit, integration and headless-IDE tests | `./gradlew test` | **144 passed, 0 failed** (129 before; new: `EngineTest` 11, `DbBridgeTest` 4) |
| Database drivers against real servers | `DbBridgeTest` | PostgreSQL 16 (embedded): value tags, scripts with several results, error position, cancel from another thread. MongoDB 7 (local `mongod`): Extended JSON round trip, counts, updates, listings |
| Database Client end to end against real servers | `scripts/db-containers.sh start`, `./gradlew writeTestClasspath`, `cd webview && npm run test:db` | MySQL 8.4, MariaDB 11.4, SQL Server 2022, Oracle 23 Free, ClickHouse 24.8 and Redis 7.4 in Docker. The extension's DatabaseService and adapters (in the extension host bundle), the driver stand-ins and the IDE's drivers (`DbBridge`, run by `DbBridgeServer`): connect, list databases and tables, describe, page, insert / update / delete from the grid, a bad query's error, cancelling a running query (stops within seconds); Redis commands, key browsing, reading and editing a key. **The same steps with the extension on the real Node drivers (pg, mysql2, mssql, oracledb, ioredis, VS Code's own) give identical column descriptions, grid values and query results** |
| Extension host, every command group | `cd webview && node test/host.mjs` | 28 checks pass: activation (no failed group), points for tool runs, sidebar provider, Insert / Remove Debug Logs, Create / Saved / Framework Snippets, the 13 pages, the workspace audit finding a hard-coded secret, CSV viewer, regex tool search, theme locks, the points ledger, no unknown requests |
| Database Client end to end (SQLite) | `node test/db-sqlite.mjs` | Save connection, connect, list, page (binary marker), insert, query, change on disk: pass |
| Toolkit parity | `node test/parity.mjs` | 249 cases: 247 identical, 2 random by design, 0 different |
| REST client parity (with points) | `node test/rest-client.mjs` | 50 feature messages and the priced catalog match the extension |
| Scanner parity | `node test/scanners.mjs` | All 4 suites pass |
| Real IDE (IntelliJ IDEA 2024.1.7 sandbox, clean config) | `DEVSNIP_SELFTEST=<project> ./gradlew runIde` | Extension host ready (147 commands), synchronous channel answers; 19 commands open their pages with the expected content; the extension's sidebar shows the onboarding guide, rank and points; the CSV viewer renders the grid; Insert Debug Log inserts `console.log("[app.js:2] total:", total);`; **no errors from the plugin in idea.log** |
| Plugin structure | `./gradlew verifyPluginStructure` | Pass |
| Plugin Verifier | `./gradlew verifyPlugin` | **Compatible** with IC 2024.1.7, IU 2026.2.3 and IU 2026.3 EAP (263.7307.44); no deprecated, scheduled-for-removal or experimental API usages |
| Installed from the ZIP | `scripts/smoke-test-zip.sh <IDE> <project>` (the ZIP unpacked into a new, empty IDE configuration, as Install Plugin from Disk does) | **IntelliJ IDEA Community 2024.1.7 and IntelliJ IDEA 2026.2.3: every self-check passes** (extension host ready, synchronous channel, 19 pages, sidebar, CSV viewer, Insert Debug Log), no errors from the plugin in the IDE log. 2026.3 EAP: the extension host starts and the channel answers; the EAP then quits because EAP builds need a JetBrains account login |
| Package | `./gradlew buildPlugin` | `build/distributions/devsnip-pro-jetbrains-1.0.0.zip`, 17.7 MB (drivers included; no SLF4J bundled) |

## Not verified here

- CockroachDB (it uses the PostgreSQL driver path, tested on PostgreSQL) and TLS connections to
  database servers (the containers ran without TLS).
- The Database Client's UI clicked by hand: its page was opened in the real IDE, and its requests
  were tested end to end in the harness.
- Interactive terminal and package installs in Dependencies & Installation in a
  running IDE (the console is created on 2024.1 in a test; the process rules are unit-level).
- Clicking through pages by hand, Windows and Linux, and IDEs other than IntelliJ IDEA (the
  plugin depends only on the platform module).
- Analytics sending to PostHog (off by default; not turned on in tests).

## Publishing steps (need your credentials)

1. Create the signing certificate and key ([Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)),
   export `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`, run `./gradlew signPlugin`.
2. Upload `build/distributions/devsnip-pro-jetbrains-1.0.0-signed.zip` on plugins.jetbrains.com (the first
   upload is by hand; JetBrains reviews it). Later versions: `PUBLISH_TOKEN=... ./gradlew publishPlugin`.
3. On the listing: screenshots (`screenshots/` has five from 0.1.x; add the Database Client, Milestones &
   Points and the sidebar's rank card), the MIT licence, the source URL, and a privacy note: no data is
   collected unless "Share anonymous usage statistics" is turned on.
4. Licences: the bundled drivers are listed in README "Third-party components"; ojdbc11 is under the
   Oracle Free Use Terms and Conditions, MariaDB Connector/J under LGPL-2.1 (unmodified, as a separate jar).

## Remaining limitations

See "Platform-specific differences" in [MIGRATION.md](MIGRATION.md).
