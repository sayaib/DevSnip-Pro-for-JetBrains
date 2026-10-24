# DevSnip Pro webviews for JetBrains

Builds the VS Code extension's own UI (sidebar and toolkit pages, themes,
codicons) and its toolkit code for the plugin's embedded browser.

- `build.mjs`: bundles the toolkit for the browser and exports the pages into
  `../src/main/resources/webview` (committed).
- `src/export.js`: renders the pages with the extension's own functions.
- `src/toolkit-runtime.js`: runs tools in the page (the extension's runner logic).
- `src/bridge.js`: `acquireVsCodeApi()` and the tool context, talking to the IDE.
- `shims/`: `crypto`, `os` and `vscode` for the browser bundle and the exporter.
- `test/parity.mjs`: every tool on real Node vs. the browser bundle, plus
  JWT/certificate cross-checks.

```bash
npm install
DEVSNIP_VSCODE_REPO=../../DevSnip-Pro npm run build
npm test
```
