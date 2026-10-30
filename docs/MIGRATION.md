# DevSnip Pro for VS Code → JetBrains: inventory and migration

Source of truth: DevSnip Pro for VS Code **11.84.0** (`sayaib.hue-console`, repository
`sayaib/DevSnip-Pro`). This document lists everything the extension contributes and
where each piece lives in the JetBrains plugin.

## How the port works

The JetBrains plugin runs the VS Code extension's **own code** rather than a rewrite,
so behaviour, texts, business rules (points, quests, rewards, theme locks) and data
formats stay identical. There are three execution models:

| Model | What runs there | IDE side |
|---|---|---|
| **Toolkit / scanner / REST pages** (since 0.1.0) | The 100 toolkit tools, the OSV and SCA scanners and the REST API Client page, each in its own embedded-browser tab with the extension's code bundled into the page | `ToolHost`, `ScannerHost`, `RestClientHost` |
| **Extension host** (new in 1.0.0) | Everything else: the extension's `activate()` (`webview/src/host-runtime.js`) in a hidden embedded-browser page per project, on a VS Code API stand-in (`webview/shims/vscode-host.js`). Its webview panels open as editor tabs, its sidebar view fills the tool window, its custom editors are IDE file editors | `DevSnipEngine`, `EngineHost`, `EnginePanels`, `EngineEditors`, `EngineTerminals`, `DbBridge` |
| **Native IDE features** | Find Action / Tools menu / context menus / shortcut, snippet completion, settings pages, file editors for CSV/Excel | `DevSnipActions`, `SnippetCompletionContributor`, `DevSnipSettingsConfigurable`, `ExcelViewerProvider`, `CsvViewerProvider` |

The extension host answers the extension's synchronous Node calls (`fs.readFileSync`,
`fs.statSync`, …) through a synchronous request to the IDE's built-in local server
(`EngineSyncServer`, token-protected), and
everything else as asynchronous requests. Commands that open IDE pages (toolkit, REST,
scanners) are registered in the host too, so every command run, from any menu, is counted
for points, quests, "Recently used" and onboarding exactly as in VS Code.

## Contributions inventory

| Contribution (package.json) | VS Code | JetBrains |
|---|---|---|
| Activity bar view `myView` (Tools sidebar) | Webview view | **DevSnip Pro** tool window; the extension's `ToolsSidebarProvider` renders it (rank card, points, onboarding, theme picker with locks). Falls back to the IDE-rendered sidebar if the extension host cannot start |
| 145 commands | Command palette | Find Action and **Tools | DevSnip Pro** (the 14 palette-hidden aliases and the development-only `resetFeatureUsage` are omitted, as in VS Code's palette; `when` clauses are evaluated by `WhenClause`, which handles parentheses, `||`, `&&` and `!`) |
| `editor/context` → DevSnip Pro submenu (18 entries with `when` clauses) | Editor context menu | **DevSnip Pro** submenu in the editor popup, same `when` clauses evaluated (`WhenClause`) |
| `explorer/context`: Scan for Vulnerabilities (OSV), Open in CSV & Excel Viewer | Explorer | Project view context menu, same conditions; OSV scans the selected files/folders |
| `editor/title`: Open in CSV & Excel Viewer for CSV | Editor title button | The **Table** tab at the bottom of every .csv/.tsv/.tab editor |
| Commands' `enablement` (`!isWeb && !devsnip.webHost`, 11.83) | Hides commands on VS Code for the web | Always available: the IDE is a desktop host. `webview/wording.mjs` makes the extension's Node check say so in every browser bundle, and the build fails if a tool page has no tool behind it |
| Keybinding Ctrl+Alt+Shift+L → Insert Debug Log | Editor | `DevSnipPro.insertDebugLog`, Ctrl+Alt+Shift+L in the macOS keymaps. In the Windows/Linux keymap Ctrl+Alt+Shift+L is the IDE's Reformat File dialog, so no default is set there (assign one in Keymap) |
| Custom editor `devsnip.spreadsheetViewer` (.xlsx, .xlsm, default) | Custom editor | `ExcelViewerProvider` (the only editor for workbooks) |
| Custom editor `devsnip.csvViewer` (.csv, .tsv, .tab, optional) | Open With | `CsvViewerProvider` (Table tab next to the text); edits on the IDE undo stack, Save / Save All write the file, unsaved tables are offered for saving on close |
| 48 snippet files (40 user `custom_*.json`, 8 framework `snippets/*.json`) | IntelliSense | `SnippetCompletionContributor`: offered in code completion per language, expanded as live templates (tab stops, choices, mirrors, variables); user snippets are kept across plugin updates |
| Walkthrough `devsnip.getStarted` (7 steps, markdown pages) | Welcome walkthrough | **Get Started** page with the same steps, pages and completion tracking |
| 15 settings (`devsnip.*`) | Settings UI | **Settings | Tools | DevSnip Pro** (built from package.json: same names, defaults, limits) and **DevSnip Pro Scanners** |
| Languages `vue`, `toml` | Language ids | Not needed: the IDE recognises files; language ids are mapped by `VsCodeLanguages` |

## Commands: feature-to-feature map

"Extension host" = the extension's own command code runs in the IDE's extension host;
"Toolkit page" / "Native page" = the tool or page opens in its own tab (the extension's
code bundled into the page).

| Command | Id | Sidebar section | JetBrains |
|---|---|---|---|
| What's New | `whatsNew` | — | Extension host |
| Get Started | `getStarted` | — | Extension host |
| Browse All Tools | `advancedToolsHub` | — | Extension host |
| Browse Web & Frontend Tools | `webDevHub` | — | Extension host |
| Browse Mobile Development Tools | `mobileDevHub` | — | Extension host |
| Browse AI & ML Tools | `aiMlHub` | — | Extension host |
| Browse Data & RAG Tools | `ragHub` | — | Extension host |
| Browse Data & RAG Tools | `bigDataHub` | — | Extension host |
| Browse DevOps & Cloud Tools | `devopsGenerator` | — | Extension host |
| Choose Theme | `chooseTheme` | — | Extension host |
| Search All Tools (Regex) | `searchTools` | — | Extension host |
| REST API Client | `openGUI` | Backend & API | Native page (extension code in page) |
| cURL Converter | `curlConverter` | Backend & API | Toolkit page (extension code in page) |
| API Response Inspector | `apiResponseInspector` | Backend & API | Toolkit page (extension code in page) |
| URL & Query String Tool | `urlTools` | Backend & API | Toolkit page (extension code in page) |
| CORS Builder & Debugger | `corsHelper` | Backend & API | Toolkit page (extension code in page) |
| OpenAPI / Swagger Toolkit | `openApiTools` | Backend & API | Toolkit page (extension code in page) |
| GraphQL Formatter & Types | `graphqlTools` | Backend & API | Toolkit page (extension code in page) |
| API Resource Scaffolder | `apiScaffolder` | Backend & API | Toolkit page (extension code in page) |
| React / Next.js Generator | `reactGenerator` | Web & Frontend | Toolkit page (extension code in page) |
| HTML → JSX | `htmlToJsx` | Web & Frontend | Toolkit page (extension code in page) |
| CSS Units & Fluid Type | `cssUnits` | Web & Frontend | Toolkit page (extension code in page) |
| Color Converter & Palette | `colorConverter` | Web & Frontend | Toolkit page (extension code in page) |
| Color Palette | `colorPalette` | — | Toolkit page (extension code in page) |
| SEO & Social Meta Tags | `metaTagsGenerator` | Web & Frontend | Toolkit page (extension code in page) |
| Cache-Control Builder | `cacheControl` | Backend & API | Toolkit page (extension code in page) |
| adb & simctl Commands | `deviceCommands` | Mobile Development | Toolkit page (extension code in page) |
| Deep Links & Universal Links | `deepLinkHelper` | Mobile Development | Toolkit page (extension code in page) |
| App Permissions | `appPermissions` | Mobile Development | Toolkit page (extension code in page) |
| Local API from Devices | `mobileLocalApi` | Mobile Development | Toolkit page (extension code in page) |
| Environments & Flavors | `appEnvironments` | Mobile Development | Toolkit page (extension code in page) |
| Signing & Keystores | `appSigning` | Mobile Development | Toolkit page (extension code in page) |
| SDK & Build Compatibility | `sdkVersions` | Mobile Development | Toolkit page (extension code in page) |
| Mobile CI Workflow | `mobileCiGenerator` | Mobile Development | Toolkit page (extension code in page) |
| dp / px / pt & Asset Sizes | `densityConverter` | Mobile Development | Toolkit page (extension code in page) |
| Saved Snippets | `showSnippets` | Code & Productivity | Extension host |
| Create Snippet | `createCustomSnippet` | Code & Productivity | Extension host |
| Clean Debug Logs | `listAndRemoveConsoleLogs` | Testing & Debugging | Extension host |
| Remove Unused Imports | `removeUnusedImports` | Code & Productivity | Extension host |
| Dependencies & Installation | `dependencyManager` | Code & Productivity | Extension host |
| README Viewer & Manager | `readmeManager` | Code & Productivity | Extension host |
| Framework Snippets | `frameworkSnippets` | Code & Productivity | Extension host |
| Unused Dependencies | `unusedDependencies` | Code & Productivity | Toolkit page (extension code in page) |
| JSON / YAML / XML Formatter | `jsonFormatter` | Text & Formatters | Toolkit page (extension code in page) |
| XML → JSON Converter | `xmlToJson` | Text & Formatters | Toolkit page (extension code in page) |
| YAML / JSON Validator | `yamlJsonTool` | — | Toolkit page (extension code in page) |
| Diff Checker | `textDiff` | Text & Formatters | Toolkit page (extension code in page) |
| Regex Tester & Library | `regexLibrary` | Text & Formatters | Toolkit page (extension code in page) |
| Regex Tester | `regexBuilder` | — | Toolkit page (extension code in page) |
| Case Converter & Text Tools | `caseConverter` | Text & Formatters | Toolkit page (extension code in page) |
| Encode / Decode | `base64Encoder` | Encoders & Converters | Toolkit page (extension code in page) |
| URL Encoder/Decoder | `urlEncoder` | — | Toolkit page (extension code in page) |
| Timestamp Converter | `timestampConverter` | Encoders & Converters | Toolkit page (extension code in page) |
| UUID & ID Generator | `idGenerator` | Encoders & Converters | Toolkit page (extension code in page) |
| JSON to Types | `jsonToTypes` | Encoders & Converters | Toolkit page (extension code in page) |
| Data Converter | `dataConverter` | Encoders & Converters | Toolkit page (extension code in page) |
| Database Client | `databaseClient` | Database | Extension host |
| SQL Formatter & Linter | `sparkSqlFormatter` | Database | Toolkit page (extension code in page) |
| SQL Query Helper | `sqlQueryHelper` | Database | Toolkit page (extension code in page) |
| Database Connection Strings | `connectionString` | Database | Toolkit page (extension code in page) |
| SQL → MongoDB Query | `sqlToMongo` | Database | Toolkit page (extension code in page) |
| CSV / TSV Viewer | `csvViewer` | Spreadsheets & CSV | Extension host |
| Excel Viewer (.xlsx) | `excelViewer` | Spreadsheets & CSV | Extension host |
| Open in CSV & Excel Viewer | `openInSpreadsheetViewer` | — | Extension host |
| Build Error Explainer | `buildErrorExplainer` | Testing & Debugging | Toolkit page (extension code in page) |
| Log Analyzer & Formatter | `observabilityAnalyze` | Testing & Debugging | Toolkit page (extension code in page) |
| Mock Data Generator | `mockDataGenerator` | Testing & Debugging | Toolkit page (extension code in page) |
| JSON Schema Validator | `jsonSchemaValidator` | Testing & Debugging | Toolkit page (extension code in page) |
| Port & Network Toolkit | `networkTools` | DevOps & Cloud | Toolkit page (extension code in page) |
| Insert Debug Log | `insertDebugLog` | Testing & Debugging | Extension host |
| Remove Debug Logs in This File | `removeDebugLogsInFile` | — | Extension host |
| Run & Debug Configurations | `debugConfigGenerator` | Testing & Debugging | Toolkit page (extension code in page) |
| Git Command Recipes | `gitRecipes` | Git & Version Control | Toolkit page (extension code in page) |
| .gitignore Generator | `gitignoreGenerator` | Git & Version Control | Toolkit page (extension code in page) |
| Semver & App Versions | `semverCalculator` | Git & Version Control | Toolkit page (extension code in page) |
| Dockerfile | `dockerfileHelper` | DevOps & Cloud | Toolkit page (extension code in page) |
| Docker Compose | `composeHelper` | DevOps & Cloud | Toolkit page (extension code in page) |
| docker run → Compose | `dockerRunToCompose` | DevOps & Cloud | Toolkit page (extension code in page) |
| CI Pipeline | `ciPipelineGenerator` | DevOps & Cloud | Toolkit page (extension code in page) |
| .env Checker | `envChecker` | DevOps & Cloud | Toolkit page (extension code in page) |
| Kubernetes & Helm | `kubernetesHelper` | DevOps & Cloud | Toolkit page (extension code in page) |
| Cloud Deploy Workflow | `cloudDeployGenerator` | DevOps & Cloud | Toolkit page (extension code in page) |
| Cron Expression Helper | `cronHelper` | DevOps & Cloud | Toolkit page (extension code in page) |
| Nginx Config | `nginxConfig` | DevOps & Cloud | Toolkit page (extension code in page) |
| Health Check Endpoints | `healthCheckGenerator` | DevOps & Cloud | Toolkit page (extension code in page) |
| Terraform Starter | `terraformGenerator` | DevOps & Cloud | Toolkit page (extension code in page) |
| PM2 Ecosystem | `pm2Config` | DevOps & Cloud | Toolkit page (extension code in page) |
| Observability Starter | `observabilityStarter` | DevOps & Cloud | Toolkit page (extension code in page) |
| JWT Decoder & Signer | `jwtDecoder` | Security & Auth | Toolkit page (extension code in page) |
| Workspace Security Audit | `securityAudit` | Security & Auth | Extension host |
| Hash, HMAC & Webhooks | `hashGenerator` | Security & Auth | Toolkit page (extension code in page) |
| OAuth 2.0 & PKCE | `oauthPkce` | Security & Auth | Toolkit page (extension code in page) |
| CSP & Security Headers | `securityHeaders` | Security & Auth | Toolkit page (extension code in page) |
| Endpoint Security Scan | `endpointSecurityScan` | Security & Auth | Extension host |
| Cookie Inspector & Builder | `cookieInspector` | Security & Auth | Toolkit page (extension code in page) |
| Dependency & Config Check | `dependencyAudit` | Security & Auth | Extension host |
| OSV Vulnerability Scanner | `osvScanner` | Security & Auth | Native page (extension code in page) |
| Scan Workspace Dependencies for Vulnerabilities (OSV) | `osvScanWorkspace` | — | Native page (extension code in page) |
| SCA Scanner | `scaScanner` | Security & Auth | Native page (extension code in page) |
| Scan Dependencies (SCA: vulnerabilities, exploited CVEs, dependency health) | `scaScanWorkspace` | — | Native page (extension code in page) |
| Scan for Vulnerabilities (OSV) | `osvScanFile` | — | Native page (extension code in page) |
| Certificate & SSL Pinning | `certInspector` | Security & Auth | Toolkit page (extension code in page) |
| Cloud & Container Audit | `cloudSecurityAudit` | Security & Auth | Extension host |
| Prompt Builder | `promptTemplate` | AI & ML | Toolkit page (extension code in page) |
| LLM Models, Tokens & Cost | `tokenCounter` | AI & ML | Toolkit page (extension code in page) |
| LLM Model Comparison | `modelComparison` | — | Toolkit page (extension code in page) |
| LLM Client Setup | `llmClientSetup` | AI & ML | Toolkit page (extension code in page) |
| LLM Output & JSON | `llmJsonValidator` | AI & ML | Toolkit page (extension code in page) |
| LLM Response Formatter | `llmResponseFormatter` | — | Toolkit page (extension code in page) |
| LLM API Tester | `llmApiTester` | AI & ML | Toolkit page (extension code in page) |
| JSON → TOON for Prompts | `jsonToToon` | AI & ML | Toolkit page (extension code in page) |
| AI App Starter | `aiAppStarter` | AI & ML | Toolkit page (extension code in page) |
| AI/ML Code Snippets | `mlCodeGen` | AI & ML | Toolkit page (extension code in page) |
| GPU Memory & Speed | `gpuVram` | AI & ML | Toolkit page (extension code in page) |
| Model Serving Starter | `mlopsGenerator` | AI & ML | Toolkit page (extension code in page) |
| Model Metrics | `metricsCalculator` | AI & ML | Toolkit page (extension code in page) |
| Dataset Split Planner | `datasetSplit` | AI & ML | Toolkit page (extension code in page) |
| Learning-Rate Schedule | `lrScheduler` | AI & ML | Toolkit page (extension code in page) |
| Model Card | `modelCard` | AI & ML | Toolkit page (extension code in page) |
| Chunking Tester | `chunkingTester` | Data & RAG | Toolkit page (extension code in page) |
| RAG Pipeline Generator | `ragPipeline` | Data & RAG | Toolkit page (extension code in page) |
| Vector Store Setup | `vectorStoreSetup` | Data & RAG | Toolkit page (extension code in page) |
| Embedding Model Guide | `embeddingModelGuide` | Data & RAG | Toolkit page (extension code in page) |
| Retrieval Configuration | `retrievalConfig` | Data & RAG | Toolkit page (extension code in page) |
| Context Window Budget | `contextWindow` | Data & RAG | Toolkit page (extension code in page) |
| Grounded Prompt Assembler | `ragPromptBuilder` | Data & RAG | Toolkit page (extension code in page) |
| Chunk & Index Size Calculator | `embeddingCost` | Data & RAG | Toolkit page (extension code in page) |
| Retrieval Evaluation | `ragEvalScores` | Data & RAG | Toolkit page (extension code in page) |
| Answer Grounding Checker | `ragHallucinationAnalyzer` | Data & RAG | Toolkit page (extension code in page) |
| Vector Similarity | `vectorSimilarity` | Data & RAG | Toolkit page (extension code in page) |
| Near-Duplicate Chunk Finder | `semanticDedup` | Data & RAG | Toolkit page (extension code in page) |
| Hybrid Search Fusion (RRF) | `hybridSearchRrf` | Data & RAG | Toolkit page (extension code in page) |
| Chunk Metadata Validator | `chunkMetadataValidator` | Data & RAG | Toolkit page (extension code in page) |
| Data Transformer | `dataTransform` | Data & RAG | Toolkit page (extension code in page) |
| Data Profiler & Quality Check | `dataQualityChecker` | Data & RAG | Toolkit page (extension code in page) |
| JSON Lines Inspector | `jsonlViewer` | Data & RAG | Toolkit page (extension code in page) |
| Schema Viewer & Diff | `schemaViewer` | Data & RAG | Toolkit page (extension code in page) |
| Schema Diff | `schemaDiff` | — | Toolkit page (extension code in page) |
| Partition & File Size Planner | `partitionCalc` | Data & RAG | Toolkit page (extension code in page) |
| Spark Cluster & Cost | `sparkCostEstimator` | Data & RAG | Toolkit page (extension code in page) |
| Delta Lake Log Analyzer | `deltaLakeAnalyzer` | Data & RAG | Toolkit page (extension code in page) |
| Security Hub | `securityHub` | — | Extension host |
| Milestone & Points Tracker | `milestoneTracker` | — | Extension host |
| Points & Premium Features | `premiumStatus` | — | Extension host |
| Reset Feature Usage Counters (development) | `resetFeatureUsage` | — | Not ported (development only) |


## Services and integrations

| VS Code service | JetBrains |
|---|---|
| `globalState` (points and milestones, sidebar state, onboarding, theme, database connections, collections) | `EngineState` (application-level, shared by every project's host; changes reach the other hosts). First start carries over the sidebar state and theme 0.1.x kept |
| `workspaceState` | Per project (`PropertiesComponent`) |
| `secrets` (database passwords, NVD key, OAuth tokens) | IDE password safe |
| `workspace.fs`, `findFiles`, Node `fs` | `EngineHost`: the project, the extension's folder and storage, picked files, Maven/Gradle caches and Maven settings; writes only to the project, the extension's own folders and files the user chose |
| `child_process` (Dependencies & Installation, `which`) | `EngineHost.spawn`: package managers, build tools, Node only; in trusted projects; in the project's folders; output streamed |
| Integrated terminal (`createTerminal` + `sendText`) | `EngineTerminals`: the user's shell in a real terminal emulator (`TerminalExecutionConsole`) in the Run tool window, so interactive programs work |
| `axios`, `https`, `tls` (dependency registries, endpoint security scan, analytics, ClickHouse `fetch`) | The REST client's Kotlin HTTP engine (`RawHttp`, `TlsProbe`), IDE proxy settings |
| Database drivers `pg`, `mysql2`, `mssql`, `oracledb`, `mongodb`, `ioredis` | `DbBridge` on JDBC (PostgreSQL, MariaDB Connector/J for MySQL and MariaDB, Microsoft SQL Server, Oracle thin), the MongoDB Java driver and Jedis; the extension's adapters and SQL builder are unchanged. SQLite runs in the host itself (sql.js) |
| Text editors, documents, `applyEdit`, `insertSnippet` | `EngineEditors`: one undoable IDE command per edit; snippets as live templates |
| Messages, quick picks, input boxes, dialogs, progress, output channels, status bar | IDE notifications/dialogs, list popups (live preview for the theme picker), file choosers, background tasks, log tabs, status bar text |
| Analytics (PostHog) | Ported, but only runs when the user turns on **Share anonymous usage statistics** (off by default; VS Code relies on its own telemetry switch, which the IDE has no counterpart for) |

## Platform-specific differences (documented limitations)

| Area | Difference | Why |
|---|---|---|
| Insert Debug Log shortcut | Not bound by default on Windows/Linux keymaps | Ctrl+Alt+Shift+L is the IDE's Reformat File shortcut there |
| Custom editors | Unsaved spreadsheet edits are not kept across an IDE restart (VS Code's hot exit) | JetBrains has no hot-exit API for custom editors; the plugin asks to save on close |
| Run & Debug Configurations tool | Generates VS Code `launch.json`, as in VS Code | It is a generator whose output format is VS Code's; unchanged on purpose |
| PostgreSQL client certificates | The key must be PKCS#8 (`BEGIN PRIVATE KEY`) | The JDBC driver does not read PKCS#1 PEM keys; the error says how to convert |
| SQL Server | SQL Server logins only (as in VS Code) | Same as the extension |
| VS Code for the web (11.83) | Not applicable | The JetBrains plugin is always the extension's Desktop edition, with all tools |
| IDEs without JCEF | Only the REST client and the seven Backend & API tools (native Swing) | The extension's code needs the embedded browser |
| Resetting feature counters | Not ported | Development-only command in VS Code |
