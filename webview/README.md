# DevSnip Pro webviews for JetBrains

Builds the VS Code extension's own UI (sidebar and toolkit pages, themes,
codicons) and its toolkit code for the plugin's embedded browser.

- `build.mjs`: bundles the toolkit for the browser and exports the pages into
  `../src/main/resources/webview` (committed).
- `src/export.js`: renders the pages with the extension's own functions.
- `src/toolkit-runtime.js`: runs tools in the page (the extension's runner logic).
- `src/api-client-runtime.js`: the REST API Client's feature system (`api-client-features.ts`) in the page, with the FeatureContext served by the IDE.
- `src/bridge.js`: `acquireVsCodeApi()`, the tool context and the REST client runtime's RPCs, talking to the IDE.
- `shims/`: `crypto`, `os` and `vscode` for the browser bundles and the exporter; `axios`, `https`, `tls`, `url`, `vscode-browser` and `milestone-tracker` route the REST client services' network, TLS, dialogs and files to the IDE.
- `test/rest-client.mjs`: the REST client's features in the page vs. the extension's handlers on real Node.
- `test/parity.mjs`: every tool on real Node vs. the browser bundle, plus
  JWT/certificate cross-checks.

```bash
npm install
DEVSNIP_VSCODE_REPO=../../DevSnip-Pro npm run build
npm test
```
