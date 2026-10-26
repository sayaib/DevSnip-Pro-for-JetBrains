# Changelog

## [1.0.0] - 2026-10-26

The rest of DevSnip Pro for VS Code: every command, page and workflow of the extension (11.81.1) now runs in JetBrains IDEs. See docs/MIGRATION.md for the feature-by-feature map.

### Added
- **Extension host**: the VS Code extension's own code runs in a hidden page of the IDE's embedded browser, one per project, with the IDE providing what VS Code would (files, editors, dialogs, terminals, processes, HTTP, password safe). Its pages open as editor tabs.
- **Tools sidebar from the extension**: rank card, points and level, onboarding guide, What's New card, theme picker with locked themes and previews.
- **Milestones & Points**: points for every tool run (from any menu), daily check-in and bonus, streaks and streak freezes, levels, milestones, daily quests, weekly events and recap, lucky finds, daily spin, quiz and Bit Sprint, rewards, mystery boxes, cosmetics, theme unlocks and the Points & Premium Features page.
- **REST API Client points**: premium features cost points and refund on failure, as in VS Code (they were free in 0.1.x).
- **Code & Productivity**: Saved Snippets, Create Snippet (from the selection), Framework Snippets, Clean Debug Logs, Remove Unused Imports, README Viewer & Manager, Dependencies & Installation (npm, pnpm, Yarn, Bun, pip, Poetry, uv, Maven, Gradle, NuGet, pub), Unused Dependencies.
- **Debug logs**: Insert Debug Log (Ctrl+Alt+Shift+L on macOS) and Remove Debug Logs in This File for JS/TS, Python, Java, Kotlin, C#, Dart and Swift.
- **Snippets in code completion**: the framework snippets (type `ds-`) and your own snippets, expanded as live templates with VS Code's tab stops, choices and variables.
- **Security**: Security Hub, Workspace Security Audit, Cloud & Container Audit, Dependency & Config Check and Endpoint Security Scan.
- **Database Client**: PostgreSQL, CockroachDB, MySQL, MariaDB, SQL Server, Oracle, SQLite, ClickHouse, MongoDB and Redis, with the extension's explorer, grid editing, query console and connection store (passwords in the IDE password safe). Drivers: JDBC, the MongoDB Java driver and Jedis; SQLite runs in the IDE.
- **CSV & Excel viewer**: .xlsx/.xlsm open in the viewer; .csv/.tsv/.tab get a Table tab. Editing with undo/redo, Save and Save All.
- **Browse All Tools**, **Search All Tools (Regex)**, **Choose Theme**, **Get Started** (the walkthrough) and **What's New**.
- **Menus and shortcuts**: every command in Find Action and Tools | DevSnip Pro; the editor's DevSnip Pro submenu and the Project view's Scan for Vulnerabilities (OSV) and Open in CSV & Excel Viewer, shown as package.json's `when` clauses say.
- **Settings | Tools | DevSnip Pro** with the extension's settings (names, defaults and limits from package.json).
- **Anonymous usage statistics** (the extension's analytics), off unless you turn on "Share anonymous usage statistics".

### Fixed
- IntelliJ-based IDEs 2026.2 and later, where the embedded browser (JCEF) is a bundled plugin and runs out of process: the sidebar, tools, scanners, REST API Client and every page now work there (0.1.x pages failed to load on those versions).

### Changed
- The scanners' Scan Workspace and Scan for Vulnerabilities (files or folders) start a scan straight away, as in VS Code.
- Pages say "the IDE", "Find Action" and "Project view" where the extension's text named VS Code's UI.
- The plugin is larger (about 30 MB) because it bundles the database drivers.
- The OpenCode Integration is not included.

## [0.1.2] - 2026-10-25

Compatibility with IntelliJ IDEA 2026.3 EAP: no deprecated or scheduled-for-removal platform APIs (Plugin Verifier, 263.7307.44).

### Changed
- Tool, scanner and REST API Client tabs, generated files, logs and saved output open through `OpenFileDescriptor.navigate`, instead of `FileEditorManager.openFile(VirtualFile, boolean)` and `FileEditorManager.openTextEditor(...)` (deprecated on 2026.3). They open and focus exactly as before.
- A saved scanner report opens in the browser by its `file:` URL, instead of `BrowserUtil.browse(File)` (scheduled for removal).
- The Plugin Verifier also checks the 2026.3 EAP.

## [0.1.1] - 2026-10-25

Compatibility with current IDEs: no deprecated or experimental platform APIs (Plugin Verifier).

### Changed
- The scanners' "Run in Terminal" fixes run the confirmed command in your shell, in the project folder, as a tab in the Run tool window (output, exit code and Stop). It uses the platform's execution API, the same on every supported IDE, instead of `TerminalToolWindowManager.createShellWidget` (deprecated on 2026.2). The Terminal plugin is no longer an optional dependency. If the shell cannot start, the command is copied with a notification.
- Workspace trust uses the IDE's public `TrustedProjects.isProjectTrusted(Project)` where it exists, and the older check only on IDEs without it (2024.x), instead of the deprecated and experimental `TrustedProjects.isTrusted`. Unknown trust still counts as untrusted.

## [0.1.0] - 2026-10-25

First release: DevSnip Pro's look, Tools sidebar and toolkit from VS Code, the REST API client, and the OSV and SCA scanners.

### Added
- The DevSnip Pro look and Tools sidebar from VS Code, in the IDE's embedded browser: 13 sections, search, filters, favourites, recents and the 13 DevSnip themes (System Default follows the IDE theme).
- 100 toolkit tools across Backend & API, Web & Frontend, Mobile, Text, Converters, Database, Testing, Git, DevOps, Security, AI & ML and Data & RAG, running the extension's own code; every tool in Find Action and Tools | DevSnip Pro.
- The REST API Client as the VS Code extension's own client page (in the IDE's embedded browser): request tabs, saved requests and collections, history, environments, cookies, cURL, the response viewer, and the Software Developer / AI & ML tools (code generation, JWT, JSON tools, assertions, batch and load tests, chaining, SDK and mock export, LLM, embeddings and RAG tests, WebSocket client, endpoint security scan). Requests go through the plugin's Kotlin engine; every tool is available without a points price. Without JCEF a native client is used.
- REST and GraphQL requests with params, headers, and JSON, text or form bodies.
- Bearer, Basic, API key (header or query) and OAuth 2.0 authentication (client credentials, password and refresh token grants).
- Environments with `{{variables}}`; secret variables and OAuth tokens are stored in the IDE password safe.
- Response status, time, size, headers and formatted JSON, plus plain-language diagnosis of network, TLS and timeout errors.
- Retries with backoff, redirect limits, timeouts, proxy and SSL verification options, session cookies.
- cURL import and export; request history with JSON and CSV export.
- The Backend & API tools with output identical to VS Code: cURL Converter, API Response Inspector, URL & Query String Tool, CORS Builder & Debugger, OpenAPI / Swagger Toolkit, GraphQL Formatter & Types and API Resource Scaffolder (also as native tabs when the IDE has no embedded browser).
- OSV Vulnerability Scanner and SCA Scanner from DevSnip Pro for VS Code, as editor tabs from the sidebar's Security & Auth section and Find Action. They run the extension's own scanner code; the IDE reads the project's dependency files, makes the HTTPS requests (IDE proxy settings), runs Maven / Gradle dependency listing in trusted projects with consent, and applies confirmed fixes (terminal command or undoable manifest edit with a diff preview).
- Settings | Tools | DevSnip Pro Scanners, with the extension's scanner settings; the optional NVD API key is kept in the IDE password safe.
- Response bodies over 100 MB are refused with a clear message instead of being loaded into the IDE's memory.
- Installing, updating or disabling the plugin works without restarting the IDE.
