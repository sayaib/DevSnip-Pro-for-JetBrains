# Changelog

## [Unreleased]

### Added
- OSV Vulnerability Scanner and SCA Scanner from DevSnip Pro for VS Code, as editor tabs from the sidebar's Security & Auth section and Find Action. They run the extension's own scanner code; the IDE reads the project's dependency files, makes the HTTPS requests (IDE proxy settings), runs Maven / Gradle dependency listing in trusted projects with consent, and applies confirmed fixes (terminal command or undoable manifest edit with a diff preview).
- Settings | Tools | DevSnip Pro Scanners, with the extension's scanner settings; the optional NVD API key is kept in the IDE password safe.

## [0.1.0] - 2026-10-24

First preview: the REST API client and the Backend & API toolkit from DevSnip Pro for VS Code.

### Added
- The DevSnip Pro look and Tools sidebar from VS Code, in the IDE's embedded browser: 13 sections, search, filters, favourites, recents and the 13 DevSnip themes (System Default follows the IDE theme).
- 100 toolkit tools across Web & Frontend, Mobile, Text, Converters, Database, Testing, Git, DevOps, Security, AI & ML and Data & RAG, running the extension's own code; every tool in Find Action and Tools | DevSnip Pro.
- REST and GraphQL requests with params, headers, and JSON, text or form bodies.
- Bearer, Basic, API key (header or query) and OAuth 2.0 authentication (client credentials, password and refresh token grants).
- Environments with `{{variables}}`; secret variables and OAuth tokens are stored in the IDE password safe.
- Response status, time, size, headers and formatted JSON, plus plain-language diagnosis of network, TLS and timeout errors.
- Retries with backoff, redirect limits, timeouts, proxy and SSL verification options, session cookies.
- cURL import and export; request history with JSON and CSV export.
- The rest of the Backend & API section, with output identical to VS Code: cURL Converter, API Response Inspector, URL & Query String Tool, CORS Builder & Debugger, OpenAPI / Swagger Toolkit, GraphQL Formatter & Types and API Resource Scaffolder.
- Tools | DevSnip Pro: Backend & API menu.
