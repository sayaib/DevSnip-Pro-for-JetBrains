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
  JWT/certificate cross-checks; then `test/scanners.mjs`.
- `src/scanner-runtime.js`: the OSV and SCA scanners (the extension's panels,
  engine and providers) for the page; `shims/vscode-browser.js`, `node-fs.js`,
  `node-child_process.js` and `src/scanner-fetch.js` hand their I/O to the IDE.
- `test/scanners.mjs`: both scanners in a browser-like context vs. the
  extension's engine on Node, on `test/fixtures` (fabricated advisories, no network).

```bash
npm install
DEVSNIP_VSCODE_REPO=../../DevSnip-Pro npm run build
npm test
```
