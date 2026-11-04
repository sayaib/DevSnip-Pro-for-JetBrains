# DevSnip Pro for JetBrains 1.2.0: release report

1.2.0 brings the JetBrains plugin up to date with DevSnip Pro for VS Code **11.88.0** (from
11.84.0): releases 11.85.0 to 11.88.0. The feature map is in [MIGRATION.md](MIGRATION.md).
Everything below ran on macOS (Apple silicon), JDK 17, Node 22.

## What came over, and how

Most of the update is the extension's own code, rebuilt with `webview/build.mjs` and running
unchanged in the IDE's pages and extension host. What needed IDE work:

| Extension change | What the IDE needed | Where |
|---|---|---|
| Cloud databases over REST (DynamoDB, Cosmos DB, Spanner, Firestore, BigQuery) use Node's `http`/`https` `request()` | `request()` over the REST engine's raw HTTP (`http.raw`): IDE proxy settings, `rejectUnauthorized: false` only for an emulator marked insecure, abort and timeout | `webview/shims/host-http.js` |
| Google service account keys are signed with `crypto.createSign` | `createSign` / `createVerify` on the crypto shim's RSA signing (node-forge) | `webview/shims/crypto.js` |
| Cloud tokens from `aws`, `az`, `gcloud` | The three CLIs allowed in `EngineHost.spawn`, in the home folder, also in untrusted projects (fixed arguments; they only print a token for the user's own account) | `EngineHost.CLOUD_CLIS` |
| An AWS profile's `credential_process` runs a shell command line (`exec`) | Refused with the reason; the IDE runs no shell command lines | `host-child_process.js` `exec` |
| Azure SQL signs in with an Entra ID access token (`mssql` `authentication`) | The token is passed to mssql-jdbc's `accessToken` | `db-mssql.js`, `DbBridge` |
| SQLite on a worker thread with `node:sqlite` | Not available in a page: `worker_threads` and `node:sqlite` resolve to a stub, so the adapter's own sql.js fallback runs (as on VS Code builds without `node:sqlite`) | `host-worker_threads.js` |
| Theme Studio: a customization and an IDE light/dark pairing | `ColorThemeKind`, a live `activeColorTheme` and `onDidChangeActiveColorTheme`, fed by the IDE's theme listener. The host now sends the IDE the effective theme and its customized rules, so the IDE's own tool, REST client and scanner tabs follow Theme Studio too (open tabs at once, new tabs from their first paint) | `vscode-host.js`, `host-runtime.js`, `DevSnipNative`, `DevSnipBrowser`, `WebviewAssets`, `DevSnipThemeListener` |
| Code previews use `--vscode-charts-orange` and `--vscode-editor-selectionBackground` | Both provided from the IDE theme (found by `WebviewAssetsTest`) | `IdeThemeCss` |
| Update notifications (Marketplace check, *Check for Updates*, `devsnip.updates.*`) | Left out of the menus and settings; the IDE updates plugins itself | `webview/src/export.js` |
| Feedback form reads `feedback.config.json` | Copied into the extension files when the extension has it (publishable Supabase key only, as in the VS Code package) | `webview/build.mjs` |
| Text that names VS Code (VS Code Adaptive, "Follow VS Code", Theme Studio hints, SQLite limits, AWS environment hint, feedback metadata) | Said the JetBrains way; the build reports any phrase that no longer exists | `webview/wording.mjs` |

## Tests

| Run | Result |
|---|---|
| `cd webview && npm test` | Every toolkit tool on Node vs. the browser bundle, the OSV and SCA scanners, the REST client's 50 feature messages and 37 catalog features: all match. Extension host: 38 checks ok, 0 failed. SQLite Database Client: ok. Cloud databases: ok |
| `webview/test/db-cloud.mjs` (new) | DynamoDB connects, lists tables and pages through the IDE's HTTP client with a valid SigV4 `Authorization`; a `credential_process` profile is refused with the reason; `createSign` gives the same RSA-SHA256 signature as Node |
| `webview/test/host.mjs` (new checks) | Theme Studio opens, is worded for the IDE, its customization is stored and reaches the IDE's pages for both surfaces, it hears the IDE switching to light, and reset clears it; *Check for Updates* and `devsnip.updates.*` are absent |
| `./gradlew test` | 153 tests, 0 failures |
| `./gradlew verifyPlugin -PverifyAllIdes=WebStorm,PyCharmProfessional` | Compatible with IntelliJ IDEA 2024.1.7 (241), 2026.2 (262), the 2026.3 EAP (263.7307.44), PyCharm 2026.2.4 and WebStorm 2026.2.4 |
| `scripts/smoke-test-zip.sh` with IntelliJ IDEA (the installed release) | The 1.2.0 ZIP installs into an empty configuration; every command group, the sidebar (showing *IDE Adaptive*), a tool run, the CSV viewer and Insert Debug Log pass the self-check; no errors attributed to the plugin |

## Audit

A review of the whole plugin after the sync: startup, threading, lifecycle, the database and HTTP
paths, dead code and compatibility. Every fix below has a test that failed before it, or a
measurement.

| Area | Finding | Fix | Evidence |
|---|---|---|---|
| Database Client | A capped query (`maxRows`) only stopped *reading* at the cap; the JDBC driver first fetched the whole result into the IDE's heap | `Statement.maxRows` is set to the cap + 1, so the server stops | `DbBridgeTest`: the first 10 of 3,000,000 rows, 878 ms before, 170–258 ms after (embedded PostgreSQL) |
| Extension host | A host that was not running (never started in that project, or stopped) queued every message sent to it, and every project's host broadcasts state changes (points after each command) to all of them | `DevSnipEngine.post` drops messages while the host is idle or stopped; a host that starts reads current state | `EngineTest`: 1000 queued before, 0 after |
| Pages | `DevSnipBrowser`'s queue and `loaded` flag were unguarded while ScannerHost posts process output from background threads | Guarded by one lock; flags volatile | Code review; all page tests pass |
| Tools sidebar | Every tool window event in the project (focus, resize, any other window) told the extension the sidebar's visibility, which refreshes its rank card | Sent only on change, and again after the host renders the view | Code review |
| IDE light/dark (new in this sync) | The theme listener posted to hosts that were not running | Only running hosts | Same mechanism as the host queue |
| Cloud HTTP (new in this sync) | The IDE's side of a cloud request had no timeout or size limit of its own | The request's timeout and the extension's 64 MB limit go to the IDE | `db-cloud.mjs` asserts both |
| Self-check | An exception on the UI thread left `DevSnipSelfTest` waiting 60 s | The future completes exceptionally | Code review |
| Dead code | A no-op `sqlServerBindOrder`; four unused imports | Removed | Compiles with no warnings |

Reviewed and left as they are:

- **Startup**: actions are prepared off the UI thread and registered in 5 ms; the extension host
  starts on first use (`engine before first use=IDLE` in the real-IDE check). The IDE's startup
  report shows no DevSnip Pro items; 4 runs each, with the plugin 2.7–3.1 s, without 3.4–3.9 s
  (that difference is ordering and disk cache, not the plugin).
- **Blocking calls**: every timed wait (`future.get`, latches) runs on a pooled thread.
  `ToolHost`'s `storage.set` waits for the UI thread on purpose: that is where the settings
  object is written.
- **Local sync server**: a 256-bit random token per host; nothing is answered without it.
- **File scoping**: paths are normalized before the project / storage checks, so `..` cannot
  leave them. A symlink inside the project that points outside it is followed; the code using
  these calls is the bundled extension, which VS Code gives full file access.
- **Size**: 17 MB ZIP; the JDBC drivers (Oracle 7.2 MB) and the extension's bundled code are
  most of it. The annotation-only jars (checker-qual, error_prone_annotations, 250 KB) were
  kept to avoid unresolved-class reports.
- **Compiler**: no warnings in main or test code.

Compatibility: Plugin Verifier reports *Compatible* for IntelliJ IDEA 2024.1.7 (241),
2026.2 (262) and the 2026.3 EAP (263.7307.44), PyCharm 2026.2.4 and WebStorm 2026.2.4
(`-PverifyAllIdes=WebStorm,PyCharmProfessional`). GoLand, PhpStorm, RubyMine, CLion and Rider
were not run here (about 6 GB more to download); the plugin depends only on
`com.intellij.modules.platform` and optional JCEF.

## Not tested

- MySQL, MariaDB, SQL Server and Oracle with the new row cap (Docker was not running here; the
  database container tests, `scripts/db-containers.sh`, cover them). The cap is the standard JDBC
  `Statement.setMaxRows`.
- The cloud services against live AWS, Azure and Google Cloud accounts, and the cloud CLIs
  themselves. The VS Code extension's release says the same of its own tests (local mock APIs
  only). RDS IAM sign-in for MySQL relies on MariaDB Connector/J's clear-password plugin over
  TLS, and Azure SQL's Entra ID token on mssql-jdbc's `accessToken`; neither has run against a
  live server.
- Sending feedback to the live Supabase table (it would create a real entry).
