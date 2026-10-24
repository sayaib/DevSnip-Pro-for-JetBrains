# DevSnip Pro for JetBrains 0.1.0: release report

Audit, fixes and verification for the first JetBrains Marketplace release
(2026-10-25). Everything below was run on macOS (Apple silicon), JDK 17.0.16,
Gradle 9.1.0, Node 22.17.1.

## Bugs found and fixed

| # | Severity | Area | Problem | Fix |
|---|---|---|---|---|
| 1 | High | REST API Client | Response bodies were buffered without limit in the IDE's heap, and gzip/deflate bodies were inflated without limit. A large download or a compression bomb could exhaust the IDE's memory and take the whole IDE down. | Bodies over 100 MB (declared, streamed or decompressed) fail with "The response is too large" and are not retried (`HttpEngine`, `ResponseDecoder`, `RequestErrors`). |
| 2 | High | Sidebar, tools, scanners | Every page of a kind was written to one shared file (`sidebar.html`, `tool-<id>.html`, `scanner-<page>.html`) holding that browser's JCEF query callback. With two projects open, or a page reopened while another was loading, a page could load the other browser's file, so its messages went to the wrong project or nowhere. | Each browser writes its own page file (`<page>-<instance>.html`) and deletes it when it closes (`WebviewAssets`, `DevSnipBrowser`). |
| 3 | High | Plugin updates | The ~119 Find Action / Tools menu actions were registered in code and never unregistered. On a Marketplace update without restart, the new version reused the old version's action objects (old classloader); an install into a running IDE added none until restart. | `DevSnipPluginListener` (a `DynamicPluginListener`) removes them before unload and adds them, with editor tracking, after a dynamic load; registration replaces stale actions. |
| 4 | Medium | Use Editor / Insert | `EditorTracker` kept a static `WeakHashMap<Project, Editor>`: the editor value held its project strongly, so closed projects were never collected. It was written from a background thread (the startup activity), and its message bus connection had no parent disposable. | A project service holding a `WeakReference` to the editor, connected with the service as parent, initial lookup on the EDT. |
| 5 | Medium | Scanners | `http.fetch` ran on the EDT, including the password safe (Keychain) lookup of the NVD key, which can block the IDE UI. | Fetch runs on a pooled thread. An abort that arrives before its request starts now prevents the request instead of being lost. |
| 6 | Medium | Marketplace metadata | `build.gradle.kts` overrode the plugin name with the outdated "DevSnip Pro: REST API Client" (plugin.xml: "DevSnip Pro: API Client & Developer Toolkit"); the change notes left out the scanners and 100 tools; the description said "Everything runs locally" although the scanners call OSV.dev, registries and NVD. | Name comes from plugin.xml only; change notes and description describe the release and its network use accurately. |
| 7 | Low | Page scripts | The pages' scripts were written non-atomically once per version folder: a write interrupted by a crash left a truncated `toolkit-runtime.js` that was reused forever. Old version folders were never removed. | Atomic write, re-written when the size differs, Windows-safe fallback when a file is in use; old version folders removed. |
| 8 | Low | REST API Client | The Auth tab's Bearer help text "Bearer &lt;token&gt;" was HTML, so `<token>` vanished. | Escaped. |
| 9 | Low | Toolkit file pick | A `null` `maxBytes` from a page threw instead of meaning "no limit". | Handled. |
| 11 | High | REST API Client | The sidebar's REST API Client opened the old native Swing panel, not the VS Code extension's client page. The port of that page (commit `f4c4822` on `feature/rest-client-webview`) had never been merged into this branch. | Merged it into this branch and reconciled it with the scanners: separate VS Code API shims (`vscode-browser.js` for the scanners, `vscode-rest.js` for the REST client), one bridge for all four page kinds, both bundles in the build, the duplicate `DevSnip Pro` notification group removed, and the REST page given its own page file per browser (fix 2). |
| 10 | Low | Docs | CHANGELOG split the release between "Unreleased" and 0.1.0; README claimed "no deprecated API usages" and that most sidebar sections were not ported. | Corrected (see README "Publishing"). |

Checked and found working, no change needed: the sidebar, tool and scanner
message handlers (every message type the pages send has a handler, except
onboarding/rewards messages from UI that is hidden in the IDE), the REST
client's threading and cancellation, secret storage, history redaction,
scanner path, process and URL allow-lists, and the committed webview bundles
(a rebuild from the VS Code source differs only in a random CSP nonce).

## Test results

| Check | Command | Result |
|---|---|---|
| Compile (Kotlin, Java) | `./gradlew build` | No errors, no warnings |
| Unit, integration and headless-IDE tests | `./gradlew test` | **127 passed, 0 failed** (105 before, 7 new for the fixes, 15 from the REST client page) |
| Toolkit parity with VS Code | `cd webview && npm test` | 249 cases over 100 tools: 247 identical, 2 random by design (same shape), 0 different; 50 of 50 crypto cross-checks |
| Scanner parity with VS Code | `cd webview && npm test` | OSV discovery, OSV vs. extension engine, OSV export/remediation, SCA analysis: all pass |
| REST client parity with VS Code | `cd webview && npm test` | 50 feature messages compared, 37 catalog features: all match |
| Plugin structure | `./gradlew verifyPluginStructure` | Pass |
| Plugin Verifier | `./gradlew verifyPlugin` | **Compatible** with IC-241.19416.15 (2024.1.7) and IU-262.10968.63 (2026.2.3) |
| Dynamic plugin | `IdeIntegrationTest` | `DynamicPlugins.checkCanUnloadWithoutRestart` returns no blocker |
| Clean sandbox IDE | `./gradlew runIde` with a project | Plugin loaded, project opened, no errors from the plugin in `idea.log` |
| Pages in Chromium | `build/webview-preview` in a browser | Sidebar renders and sends `expanded` / `run`; cURL Converter runs in-page (`success`), live re-run on edit; OSV scanner page starts with no console errors; REST API Client page renders the VS Code client and Send posts `testAPI` to the IDE |

New tests: oversized bodies (declared, chunked, gzip bomb, not retried);
an abort that overtakes its scanner request; one page file per browser;
complete script folder; Use Editor finds the project's editor; actions removed
and re-added around unload; the platform's dynamic-unload check.

## Feature by feature

| Feature | How it was verified |
|---|---|
| Tools sidebar (sections, search, favourites, recents, themes) | Rendered in Chromium; `WebviewAssetsTest` (103 commands, 13 sections, every theme variable provided) |
| 100 toolkit tools | Node parity suite against the extension's TypeScript; every page assembles (`WebviewAssetsTest`); cURL Converter driven in Chromium |
| Find Action / Tools menu | `IdeIntegrationTest` (every supported command has an action; register/unregister) |
| REST API Client page (VS Code UI) | `RestClientHostTest` (message protocol on the real engine), `webview/test/rest-client.mjs`, `WebviewAssetsTest`; rendered and Send checked in Chromium |
| Native REST client (no JCEF): send, cancel, errors, Fix button, cURL, history | `ApiClientPanelTest` (real panel, headless IDE), `HttpEngineIntegrationTest`, `CurlTest`, `HistoryAndRedactionTest` |
| Auth (Bearer, Basic, API key, OAuth 2.0) | `RequestBuilderTest`, `OAuthClientTest` |
| Environments and secrets | `ApiClientServiceTest` (secrets kept out of XML state) |
| TLS, timeouts, refused, unknown host, redirects, retries, oversized bodies | `HttpEngineIntegrationTest`, `HttpsVerificationTest` |
| Seven native Backend & API tools (no-JCEF fallback) | `BackendApiParityTest` (256 recorded VS Code results), `ToolPanelTest` |
| OSV and SCA scanners | Node scanner suites; `ScannerHostTest` (file, network, process and secret rules); page start in Chromium |
| Scanner settings | `ScannerHostTest` (values reach the page, bad values fall back) |

## Remaining limitations and manual steps

**Before publishing (manual, need your credentials):**

1. Create the signing certificate and key ([Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)),
   export `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`, run `./gradlew signPlugin`.
2. Upload `build/distributions/devsnip-pro-jetbrains-0.1.0-signed.zip` on plugins.jetbrains.com
   (the first version must be uploaded by hand; JetBrains then reviews it).
3. On the listing, add screenshots (Marketplace recommends them), the MIT license, and the
   source URL (https://github.com/sayaib/DevSnip-Pro-for-JetBrains).

**Not verified here (needs a person at the IDE, or external services):**

- Clicking through the JCEF pages inside a running IDE (the sandbox IDE started and loaded the
  plugin; the pages themselves were driven in standalone Chromium, the engine JCEF uses, and the
  IDE side by headless tests). Suggested manual pass, about 10 minutes:
  `./gradlew runIde`, open a project, open the DevSnip Pro tool window, open 3–4 tools from
  different sections, the REST API Client (send `GET https://httpbin.org/get`, try a collection and one AI & ML tool), and an OSV scan
  of a project with a `package-lock.json`; switch the IDE between light and dark.
- Live calls to OSV.dev, registries, Maven Central, NVD and the CISA KEV feed (tests use local
  mock servers and fixtures by design). The scanners show the services' own errors if they are
  unreachable.
- Windows and Linux (the code handles their paths, executables and line endings; tests ran on macOS).
- JetBrains IDEs other than IntelliJ IDEA (the plugin depends only on `com.intellij.modules.platform`).

**Known limitations (documented in README):**

- `TrustedProjects.isTrusted` (experimental on 2024.1, deprecated on 2026.2) and
  `TerminalToolWindowManager.createShellWidget` (deprecated on 2026.2) are the only APIs that work
  on every supported version; both fall back safely if removed.
- Proxy authentication is not supported (explicit proxy or IDE proxy without credentials).
- Some scanner page text says "VS Code" (the extension's own wording); the explorer's
  "Scan with OSV" entry and the sidebar's host-only features are not ported.
