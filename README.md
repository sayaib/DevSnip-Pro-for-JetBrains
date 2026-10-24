# DevSnip Pro for JetBrains: API Client & Backend Toolkit

A JetBrains IDE plugin that ports the **backend and API module** of the
[DevSnip Pro VS Code extension](https://github.com/sayaib/DevSnip-Pro): its
HTTP request engine, authentication, request/response processing, error
diagnosis, environments, cURL import/export and request history. It also ports
the rest of the extension's **Backend & API** section: cURL Converter, API
Response Inspector, URL & Query String Tool, CORS Builder & Debugger,
OpenAPI / Swagger Toolkit, GraphQL Formatter & Types and API Resource
Scaffolder. Everything lives in the **DevSnip Pro** tool window, one tab per
tool, and under **Tools | DevSnip Pro: Backend & API**.

The VS Code extension is not modified.

## What the audit found

The VS Code extension has **no DevSnip-owned backend server or hosted API**.
Its "backend" for the API client is the in-process request engine in the
extension host. The table maps each part to its Kotlin port:

| VS Code source (TypeScript) | What it does | Kotlin port |
|---|---|---|
| `src/commands/api-test.ts`: `ApiRequest`, `buildRequestParts()` | Variable resolution, URL validation, query params, JSON/text/form/GraphQL bodies, Bearer/Basic/API key auth | `core/Models.kt`, `core/RequestBuilder.kt` |
| `api-test.ts`: `makeRequest()` (axios) | Sending, timeouts, retries with exponential backoff, redirects, TLS verification, proxy, cookies, timing, size limit | `core/HttpEngine.kt` (`java.net.http`) |
| `api-test.ts`: `decodeBody()`, `formatBytes()` | JSON, text and binary detection, 2 MB display limit | `core/ResponseDecoder.kt` |
| `api-test.ts`: `describeRequestError()` | Turns network errors into a title, a hint and the field to fix | `core/RequestErrors.kt` (also maps JDK exceptions to the same error codes) |
| `api-test.ts`: `generateCurlCommand()`, `parseCurlCommand()`, `shellQuote()` | cURL export and import | `core/Curl.kt` |
| `api-test.ts`: `redactUrl()`, `historySnapshot()`, `exportHistory()` | History without secrets, JSON/CSV export | `core/Redaction.kt`, `core/History.kt` |
| `api-test.ts`: environments in `globalState` | `{{variable}}` environments | `ide/ApiClientService.kt` (secret variables go to the IDE password safe) |
| `services/dev-operations.ts`: `requestOAuthToken()` | OAuth 2.0 client credentials, password and refresh token grants | `core/OAuth.kt` |
| `devsnip.apiTimeout` setting | Default request timeout | Settings tab, stored by `ApiClientService` |
| Webview UI (`api-client-webview.ts`) | Request builder and response viewer | `ide/ui/ApiClientPanel.kt` (Swing tool window) |

The only outbound service the extension calls is PostHog telemetry
(`src/analytics/`). It is not an API feature and was **not** ported. Premium
gating (`src/premium/`) is a local points balance tied to the milestone
tracker, not a server, so it is also out of scope.

The port follows the TypeScript closely so both clients behave the same:
the same defaults (30 s timeout, retry on 429/502/503/504, 5 redirects), the
same `User-Agent`, the same error texts and the same redaction rules. There
is no second backend: each behaviour is implemented once, in `core/`, which
has no IntelliJ dependency.

## Backend & API toolkit

These seven tools are ports of the VS Code extension's toolkit
(`src/toolkits/`). They run locally and never call the network.

| Tool | What it does | Ported from |
|---|---|---|
| cURL Converter | cURL (bash, cmd, browser "Copy as cURL") to fetch, Axios, Node, Python, Go, PHP, Java, Dart http/dio, Kotlin OkHttp, Swift, raw HTTP and HTTPie | `engines/web-curl.ts` |
| API Response Inspector | Raw HTTP response or body: status meaning, pagination, rate limits, content-type problems, field list | `engines/data-inspect.ts` |
| URL & Query String Tool | Parse URLs (leaked tokens, double encoding), build encoded URLs, query string to and from nested JSON | `engines/web-http.ts` |
| CORS Builder & Debugger | Config for Express, NestJS, Next.js, Fastify, nginx and FastAPI; a browser-accurate header check; console error explainer | `engines/web-security.ts` |
| OpenAPI / Swagger Toolkit | Lint endpoints, TypeScript types, a typed fetch client, cURL examples, a spec from sample JSON (YAML or JSON in) | `engines/web-apispec.ts` |
| GraphQL Formatter & Types | Format and minify, operations and variables, request body, cURL and fetch, SDL to TypeScript | `engines/web-apispec.ts` |
| API Resource Scaffolder | CRUD APIs for Express, NestJS, Next.js, Fastify (Prisma, Mongoose or memory), Spring Boot, ASP.NET Core and FastAPI | `engines/web-scaffold*.ts` |

Each tab shows the tool's form (with its examples and **Use Editor** to read
the current selection). Results can be copied, inserted at the caret, saved
as a file, or, for scaffolds, saved into the project; existing files are
never overwritten without asking.

**Same results as VS Code.** The tools' fields, defaults and examples are
exported from the extension's source (`scripts/export-backend-api.js`), and
`BackendApiParityTest` runs every tool over 256 inputs recorded from the
extension's own TypeScript. The test requires identical output: same code,
same messages, same tables. The only allowance is the wording of JSON and
YAML parser errors, which comes from V8 in VS Code and from the JVM here.

## REST API Client features

- **Requests:** any method, query params and headers tables (rows can be
  disabled), JSON, text, form URL-encoded and GraphQL (query, variables,
  operation name) bodies, and a Format JSON button.
- **Auth:** Bearer token, Basic (an empty password works, as for Stripe), API key
  in a header or the query string, and OAuth 2.0 (client credentials,
  password or refresh token grant, with client credentials in the body or
  as HTTP Basic).
- **Environments:** `{{name}}` variables in the URL, params, headers, body,
  auth and OAuth fields. An undefined variable is named in the error.
- **Responses:** status (coloured), time, size, retry and redirect counts,
  formatted JSON body, a headers table, and a Diagnostics tab. Binary bodies are described,
  not dumped, and gzip/deflate bodies are decoded.
- **Errors:** DNS, refused connections, timeouts, certificate and TLS problems,
  redirect loops, resets and proxy errors each get a plain explanation and a
  **Fix in…** button that opens the right tab or field.
- **Settings:** timeout (saved as the default), retries, retry delay and
  status codes, redirect following and limit, SSL verification, and an HTTP proxy
  (left blank, the IDE proxy settings are used).
- **Tools:** Import cURL, Copy as cURL (with stored cookies), and history
  (the last 50 requests): open an entry, clear, export as JSON or CSV, clear cookies.
- **Responsiveness:** requests run on a background task with a progress
  indicator. **Send** turns into **Cancel**, and the status-bar progress can also
  cancel. Cancelling also stops retry backoff.

## Security

- **No secrets in code or config.** Nothing is hardcoded. Tests use only
  local mock servers and throwaway values.
- **Password safe.** Environment variables whose names contain `token`,
  `secret`, `password`, `passwd`, `pwd`, `key`, `auth`, `cookie`, `session`,
  `signature` or `credential` are stored with `PasswordSafe` (macOS Keychain,
  Windows Credential Manager, KeePass or libsecret). The XML state
  (`devsnip-api-client.xml`, not roamed) holds only blank placeholders for them.
  OAuth access tokens are stored the same way, and the UI shows only a masked
  preview (`eyJhbG...7890 (52 chars)`).
- **Typed credentials stay in memory.** Tokens and passwords typed into the
  Auth tab are never persisted. History keeps a header or auth field only
  when it is a `{{variable}}` reference, and redacts credential-like query values
  (`api_key=[redacted]`).
- **Exports.** History exports leave out request bodies and snapshots. CSV
  cells starting with `= + - @` are escaped to prevent formula injection.
- **Logs.** Only debug-level lines with the method, redacted URL, status
  and timing. No headers, bodies or credentials. `toString()` of the request, auth
  and OAuth types masks secrets.
- **Redirects.** `Authorization`, `Cookie` and `Proxy-Authorization` are
  dropped when a redirect goes to another host.
- **Cookies.** Kept in memory for the IDE session only (the VS Code client
  persisted them). Response headers show `Set-Cookie` names, not values.
- **Copy as cURL.** The copied command includes the request's credentials,
  as in VS Code, and the UI says so.

## Requirements

- JDK 17 or newer to run Gradle. No IDE install is needed: Gradle downloads
  IntelliJ IDEA Community 2024.1.7 for compiling (about 1 GB the first time).
- Network access on the first build (Gradle, Maven Central, JetBrains repositories).
- **No environment variables are required** to build, test or use it (only to sign and publish, see below). The plugin needs no API keys,
  accounts or backend URLs. You supply target URLs and credentials in the
  tool window or in environments.

## Build, test and run

```bash
./gradlew test
```

```bash
./gradlew buildPlugin
```

The plugin ZIP is written to `build/distributions/devsnip-pro-jetbrains-0.1.0.zip`.

```bash
./gradlew runIde
```

This starts a sandbox IntelliJ IDEA with the plugin. Open **View | Tool Windows | DevSnip Pro**.

```bash
./gradlew verifyPlugin
```

This runs JetBrains Plugin Verifier against IDEA 2024.1.7 and 2026.2.3. It downloads both IDEs.

To install into your own IDE: **Settings | Plugins | ⚙ | Install Plugin from
Disk…**, choose the ZIP, then restart.

Compatibility: built against platform 241 (2024.1) with Java 17 bytecode,
`since-build` 241, no `until-build`. It depends only on
`com.intellij.modules.platform`, so it loads in every IntelliJ-based IDE.

## Publishing to JetBrains Marketplace

Verified for this release (0.1.0):

- **Tests:** 82 unit, integration and headless-IDE tests pass (`./gradlew test`).
- **Plugin Verifier:** Compatible with IntelliJ IDEA 2024.1.7 (IC-241.19416.15) and 2026.2.3 (IU-262.10968.63), with no deprecated or internal API usages.
- **Structure:** `verifyPluginStructure` passes. The listing assets are `pluginIcon.svg`, change notes, description and `LICENSE` (MIT).

### 1. Signing key (one time)

Create a certificate following [Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html).
Keep the key outside the repository.

### 2. First release (manual upload)

JetBrains requires the first version of a plugin to be uploaded through the website.

```bash
export CERTIFICATE_CHAIN="$(cat /secure/path/chain.crt)"
export PRIVATE_KEY="$(cat /secure/path/private.pem)"
export PRIVATE_KEY_PASSWORD='...'
./gradlew signPlugin
```

Upload `build/distributions/devsnip-pro-jetbrains-0.1.0-signed.zip` at
[plugins.jetbrains.com](https://plugins.jetbrains.com) → your profile → **Add new plugin**.
Choose the MIT license and add the source URL. JetBrains reviews new plugins before they are listed, which usually takes a few working days.

### 3. Later releases

Bump `pluginVersion` in `gradle.properties`, add an entry to `CHANGELOG.md` and the `changeNotes` in
`build.gradle.kts`, create a token under **My Tokens** on the Marketplace, and run:

```bash
export PUBLISH_TOKEN='perm:...'
./gradlew publishPlugin
```

`publishPlugin` signs, verifies and uploads the plugin. Set `PUBLISH_CHANNEL=eap` to publish to a pre-release channel.

| Variable | Used by | Purpose |
|---|---|---|
| `CERTIFICATE_CHAIN` | `signPlugin`, `publishPlugin` | PEM certificate chain |
| `PRIVATE_KEY` | `signPlugin`, `publishPlugin` | PEM private key |
| `PRIVATE_KEY_PASSWORD` | `signPlugin`, `publishPlugin` | Key password |
| `PUBLISH_TOKEN` | `publishPlugin` | Marketplace permanent token |
| `PUBLISH_CHANNEL` | `publishPlugin` | Optional; defaults to `default` |

None of these are needed to build, test or run the plugin.

## Testing locally

All automated tests run against local servers on `127.0.0.1` with ephemeral
ports (`com.sun.net.httpserver`), so no production or third-party API is
contacted. The one exception is a DNS lookup of the reserved
`devsnip-test.invalid` name.

| Suite | Covers |
|---|---|
| `RequestBuilderTest` | URL validation, variables, query merging and encoding, header case, each auth style, JSON/text/form/GraphQL bodies, body-carrying methods, secret-free `toString()` |
| `CurlTest` | Export, quoting checked against a real `/bin/sh` (injection attempts), import of common flags, round trip, errors |
| `ResponseDecoderTest`, `RequestErrorsTest` | JSON/text/binary/charset detection, truncation, gzip, sizes, every error description, JDK exception mapping |
| `HistoryAndRedactionTest` | URL redaction, snapshots without secrets, 50-entry cap, storage round trip, JSON/CSV export |
| `HttpEngineIntegrationTest` | Real HTTP: status, headers, timing, size, retries, 303/307 redirects, redirect loops, cross-host credential stripping, cookies, gzip, restricted headers, timeout, refused connection, unknown host, cancellation (in flight and during backoff), GraphQL, HEAD/DELETE |
| `HttpsVerificationTest` | A self-signed HTTPS server fails with "SSL certificate problem" and works with verification off |
| `OAuthClientTest` | Each grant's form, Basic client auth, token masking, endpoint errors, input validation |
| `ApiClientServiceTest` | Secret variables kept out of persisted state, state reload, history without secrets, failures recorded, OAuth token storage |
| `ApiClientPanelTest` | The real tool window in a headless IDE: Send, Cancel, error diagnosis and Fix button, Copy as cURL, opening history |
| `BackendApiParityTest` | All seven toolkit tools against 256 results recorded from the VS Code extension (defaults, every mode and example, edge cases, errors) |
| `ToolPanelTest` | The generic tool panel in a headless IDE: every tool runs its defaults, form input, conditional fields, errors, generated files |

To try it by hand against a local server:

```bash
python3 -m http.server 8000
```

Then send `GET http://127.0.0.1:8000/` from the tool window. For JSON
APIs, any local dev server works, or create an environment with
`baseUrl = http://localhost:3000` and send `{{baseUrl}}/your/endpoint`.

## Known limitations

- **Not ported yet** from the VS Code REST client: saved request collections,
  assertions, batch and load runs, SDK export, mock generator, security
  header scan, WebSocket and JWT inspection. Other sidebar sections (Auth &
  security, frontend, database, DevOps, AI/RAG, OSV/SCA scanners) are not ported.
- Toolkit code outputs are shown as plain monospace text, without syntax highlighting.
- No multipart/form-data or file-upload bodies. Bodies are text.
- The JDK HTTP client manages the `Host`, `Connection`, `Content-Length`, `Expect`
  and `Upgrade` headers itself. If you type them, they are skipped with a warning.
- The explicit proxy is HTTP only, without proxy authentication. With no explicit
  proxy, the IDE proxy settings are used.
- Cookies are simple per-host `name=value` pairs, ignoring path, expiry and
  domain attributes (as in the VS Code client), and last only for the IDE session.
- The response viewer is a plain monospace text area, without syntax
  highlighting or search.
- The timeout covers a whole attempt, redirects included. Each retry gets a new timeout.
- History and environments are application-wide, not per project.

## Project layout

```
src/main/kotlin/pro/devsnip/api/core/   Engine (no IntelliJ dependency): models, builder, HTTP, OAuth, cURL, errors, history
src/main/kotlin/pro/devsnip/api/core/toolkit/  Backend & API toolkit: framework, JS/YAML compatibility, engines, tools
src/main/resources/toolkit/             Tool metadata exported from the VS Code extension
scripts/export-backend-api.js           Re-exports metadata and reference results from the VS Code source
src/main/kotlin/pro/devsnip/api/ide/    Service (state, password safe) and tool window UI
src/main/resources/META-INF/plugin.xml  Plugin descriptor
src/test/kotlin/                        Unit, integration and headless IDE tests
```
