#!/usr/bin/env node
/*
 * Builds DevSnip Pro's webviews for the JetBrains plugin from the VS Code
 * extension's source (the single source of truth for the UI):
 *
 *   media/*.js, codicons     copied as they are
 *   toolkit-runtime.js       every toolkit tool, bundled for the browser
 *   api-client-runtime.js    the REST API Client's feature system, bundled
 *                            for the browser (HTTP, files: the IDE)
 *   sidebar/tool shells,     exported by src/export.js
 *   data, themes
 *
 * Output: ../src/main/resources/webview (committed, so building the plugin
 * does not need Node). Run after updating the VS Code extension:
 *
 *   cd webview && npm install && DEVSNIP_VSCODE_REPO=../../DevSnip-Pro npm run build
 *
 * The VS Code repository is only read.
 */
import { build } from "esbuild";
import { execFileSync } from "child_process";
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";
import { WORDING, reword } from "./wording.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(process.env.DEVSNIP_VSCODE_REPO || path.join(here, "../../DevSnip-Pro"));
const src = path.join(repo, "src");
const out = path.resolve(here, "../src/main/resources/webview");
if (!fs.existsSync(path.join(src, "toolkits/registry.ts"))) {
  console.error(`DevSnip Pro VS Code sources not found at ${repo}. Set DEVSNIP_VSCODE_REPO.`);
  process.exit(1);
}
fs.rmSync(out, { recursive: true, force: true });
fs.mkdirSync(out, { recursive: true });

const shim = name => path.join(here, "shims", name);

// The extension's text, said the JetBrains way (wording.mjs), in every bundle and copied file.
const usedWording = new Set();
const wording = {
  name: "devsnip-wording",
  setup(b) {
    b.onLoad({ filter: /\.(ts|js|mjs|cjs)$/ }, args => {
      if (!args.path.startsWith(repo) || args.path.includes("node_modules")) return undefined;
      const text = fs.readFileSync(args.path, "utf8");
      const out = reword(text, args.path, usedWording);
      return out === text ? undefined : { contents: out, loader: args.path.endsWith(".ts") ? "ts" : "js", resolveDir: path.dirname(args.path) };
    });
  }
};
const repoModules = path.join(repo, "node_modules");

// 1. The toolkit, for the browser (Node built-ins replaced by shims).
await build({
  entryPoints: [path.join(here, "src/toolkit-runtime.js")],
  outfile: path.join(out, "toolkit-runtime.js"),
  bundle: true,
  format: "iife",
  platform: "browser",
  target: "chrome110",
  minify: true,
  legalComments: "eof",
  nodePaths: [repoModules, path.join(here, "node_modules")],
  alias: {
    "@devsnip": src,
    crypto: shim("crypto.js"),
    os: shim("os.js"),
    path: path.join(here, "node_modules/path-browserify"),
    "node:path": path.join(here, "node_modules/path-browserify"),
    vscode: shim("vscode.cjs")
  },
  inject: [shim("globals.js")],
  plugins: [wording],
  define: { "process.env.NODE_ENV": '"production"', "process.platform": '"browser"', global: "globalThis" },
  logLevel: "warning"
});

// 1b. The OSV and SCA scanners: the extension's panels, engine and providers, with the
// VS Code and Node APIs they use served by the IDE (shims/vscode-browser.js and friends).
const scannerStubs = {
  name: "devsnip-scanner-stubs",
  setup(b) {
    // Analytics, onboarding and the theme service stay in VS Code; the IDE does their part.
    b.onResolve({ filter: /^\.\.\/(analytics|onboarding\/activation|theme\/service)$/ }, () => ({ path: shim("scanner-stubs.js") }));
  }
};
await build({
  entryPoints: [path.join(here, "src/scanner-runtime.js")],
  outfile: path.join(out, "scanner-runtime.js"),
  bundle: true,
  format: "iife",
  platform: "browser",
  target: "chrome110",
  minify: true,
  legalComments: "eof",
  nodePaths: [repoModules, path.join(here, "node_modules")],
  plugins: [scannerStubs, wording],
  alias: {
    "@devsnip": src,
    crypto: shim("crypto.js"),
    os: shim("os.js"),
    fs: shim("node-fs.js"),
    child_process: shim("node-child_process.js"),
    path: shim("scanner-path.js"),
    "node:path": shim("scanner-path.js"),
    "path-browserify": path.join(here, "node_modules/path-browserify"),
    vscode: shim("vscode-browser.js")
  },
  inject: [shim("scanner-globals.js")],
  define: { "process.env.NODE_ENV": '"production"', global: "globalThis" },
  logLevel: "warning"
});

// 1c. The REST API Client's feature system (api-client-features.ts and its
// services), for the browser. Network, TLS, files and dialogs go to the IDE
// (shims/axios.js, tls.js, vscode-rest.js); the points tracker is not
// ported, so its import is a stub.
const milestoneStub = {
  name: "devsnip-milestone-stub",
  setup(b) {
    b.onResolve({ filter: /(^|\/)milestoneTracker$/ }, () => ({ path: shim("milestone-tracker.js") }));
  }
};
await build({
  entryPoints: [path.join(here, "src/api-client-runtime.js")],
  outfile: path.join(out, "api-client-runtime.js"),
  bundle: true,
  format: "iife",
  platform: "browser",
  target: "chrome110",
  minify: true,
  legalComments: "eof",
  nodePaths: [repoModules, path.join(here, "node_modules")],
  plugins: [milestoneStub, wording],
  alias: {
    "@devsnip": src,
    axios: shim("axios.js"),
    https: shim("https.js"),
    tls: shim("tls.js"),
    url: shim("url.js"),
    crypto: shim("crypto.js"),
    os: shim("os.js"),
    path: path.join(here, "node_modules/path-browserify"),
    vscode: shim("vscode-rest.js")
  },
  inject: [shim("globals.js")],
  define: { "process.env.NODE_ENV": '"production"', "process.platform": '"browser"', global: "globalThis" },
  logLevel: "warning"
});

// 1d. The extension host: the extension's activate() for everything the IDE
// does not run natively (sidebar, points, snippets, security tools, database
// client, spreadsheet viewer, ...), on the VS Code API in shims/vscode-host.js.
// The IDE answers its requests (EngineHost.kt).
const DB_DRIVERS = ["pg", "mysql2/promise", "mssql", "oracledb", "mongodb", "ioredis"];
const dbDrivers = {
  name: "devsnip-db-drivers",
  setup(b) {
    const escaped = DB_DRIVERS.map(d => d.replace(/[.*+?^${}()|[\]\\/]/g, "\\$&")).join("|");
    b.onResolve({ filter: new RegExp(`^(${escaped})$`) }, args => {
      const file = path.join(here, "shims", `db-${args.path.replace(/[@/]/g, "-").replace(/^-/, "")}.js`);
      return { path: fs.existsSync(file) ? file : shim("db-pending.js") };
    });
  }
};
await build({
  entryPoints: [path.join(here, "src/host-runtime.js")],
  outfile: path.join(out, "host-runtime.js"),
  bundle: true,
  format: "iife",
  platform: "browser",
  target: "chrome110",
  minify: true,
  legalComments: "eof",
  nodePaths: [repoModules, path.join(here, "node_modules")],
  plugins: [dbDrivers, wording],
  alias: {
    "@devsnip": src,
    "@devsnip-manifest": path.join(repo, "package.json"),
    vscode: shim("vscode-host.js"),
    fs: shim("host-fs.js"),
    "node:fs": shim("host-fs.js"),
    child_process: shim("host-child_process.js"),
    os: shim("host-os.js"),
    util: shim("host-util.js"),
    zlib: shim("host-zlib.js"),
    crypto: shim("crypto.js"),
    "node:crypto": shim("crypto.js"),
    axios: shim("axios.js"),
    https: shim("https.js"),
    tls: shim("tls.js"),
    url: shim("url.js"),
    path: path.join(here, "node_modules/path-browserify"),
    "node:path": path.join(here, "node_modules/path-browserify"),
    // SQLite runs in the host itself: sql.js's asm.js build needs no WebAssembly file.
    "sql.js": path.join(repoModules, "sql.js/dist/sql-asm.js")
  },
  inject: [shim("host-globals.js")],
  define: { "process.env.NODE_ENV": '"production"', global: "globalThis", "require.resolve": "__devsnipRequireResolve" },
  logLevel: "warning"
});
fs.copyFileSync(path.join(here, "static/engine.html"), path.join(out, "engine.html"));

// 1e. The extension's own files the host reads at run time (media, snippets,
// package.json, walkthrough pages, codicons), unpacked by the IDE into the
// extension folder; files.json lists them.
const extensionOut = path.join(out, "extension");
const extensionFiles = [];
const copyInto = (from, to) => {
  const target = path.join(extensionOut, to);
  fs.mkdirSync(path.dirname(target), { recursive: true });
  if (/\.(js|md|json|html)$/.test(from)) fs.writeFileSync(target, reword(fs.readFileSync(from, "utf8"), from, usedWording));
  else fs.copyFileSync(from, target);
  extensionFiles.push(to.split(path.sep).join("/"));
};
const copyTree = (dir, prefix) => {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const from = path.join(dir, entry.name);
    if (entry.isDirectory()) copyTree(from, path.join(prefix, entry.name));
    else copyInto(from, path.join(prefix, entry.name));
  }
};
copyInto(path.join(repo, "package.json"), "package.json");
copyInto(path.join(repo, "analytics.config.json"), "analytics.config.json");
copyInto(path.join(repo, "logo.png"), "logo.png");
copyInto(path.join(here, "../CHANGELOG.md"), "CHANGELOG.md");
copyTree(path.join(repo, "media"), "media");
copyTree(path.join(repo, "snippets"), "snippets");
copyTree(path.join(repo, "custom"), "custom");
for (const file of ["codicon.css", "codicon.ttf"]) copyInto(path.join(repoModules, "@vscode/codicons/dist", file), path.join("node_modules/@vscode/codicons/dist", file));
fs.writeFileSync(path.join(extensionOut, "files.json"), JSON.stringify(extensionFiles.sort()));

// 2. Shells, data and themes, rendered by the extension's own code on Node.
const exporter = path.join(here, ".build/export.cjs");
await build({
  entryPoints: [path.join(here, "src/export.js")],
  outfile: exporter,
  bundle: true,
  format: "cjs",
  platform: "node",
  target: "node18",
  nodePaths: [repoModules],
  alias: { "@devsnip": src, vscode: shim("vscode.cjs") },
  plugins: [wording],
  logLevel: "warning"
});
execFileSync(process.execPath, [exporter, out], { stdio: "inherit", env: { ...process.env, DEVSNIP_VSCODE_REPO_RESOLVED: repo } });

// 3. The pages' own scripts and the codicon font, unchanged.
for (const file of ["tools-sidebar.js", "toolkit.js", "osv-scanner.js", "sca-scanner.js", "sca-model.js", "reward-effects.js"]) {
  const from = path.join(repo, "media", file);
  fs.writeFileSync(path.join(out, file), reword(fs.readFileSync(from, "utf8"), from, usedWording));
}
const unused = WORDING.filter((_, i) => !usedWording.has(i));
if (unused.length) console.warn(`Wording no longer found in the extension (update wording.mjs):\n${unused.map(([from]) => `  ${from}`).join("\n")}`);
const codicons = path.join(repoModules, "@vscode/codicons/dist");
fs.copyFileSync(path.join(codicons, "codicon.css"), path.join(out, "codicon.css"));
fs.copyFileSync(path.join(codicons, "codicon.ttf"), path.join(out, "codicon.ttf"));
fs.copyFileSync(path.join(here, "src/bridge.js"), path.join(out, "bridge.js"));

const version = JSON.parse(fs.readFileSync(path.join(repo, "package.json"), "utf8")).version;
fs.writeFileSync(path.join(out, "SOURCE.txt"), `Built from DevSnip Pro for VS Code ${version} (${repo}).\nDo not edit: run webview/build.mjs.\n`);
console.log(`Webviews written to ${path.relative(process.cwd(), out)} from VS Code extension ${version}.`);
