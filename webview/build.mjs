#!/usr/bin/env node
/*
 * Builds DevSnip Pro's webviews for the JetBrains plugin from the VS Code
 * extension's source (the single source of truth for the UI):
 *
 *   media/*.js, codicons     copied as they are
 *   toolkit-runtime.js       every toolkit tool, bundled for the browser
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
  define: { "process.env.NODE_ENV": '"production"', "process.platform": '"browser"', global: "globalThis" },
  logLevel: "warning"
});

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
  logLevel: "warning"
});
execFileSync(process.execPath, [exporter, out], { stdio: "inherit" });

// 3. The pages' own scripts and the codicon font, unchanged.
for (const file of ["tools-sidebar.js", "toolkit.js"]) fs.copyFileSync(path.join(repo, "media", file), path.join(out, file));
const codicons = path.join(repoModules, "@vscode/codicons/dist");
fs.copyFileSync(path.join(codicons, "codicon.css"), path.join(out, "codicon.css"));
fs.copyFileSync(path.join(codicons, "codicon.ttf"), path.join(out, "codicon.ttf"));
fs.copyFileSync(path.join(here, "src/bridge.js"), path.join(out, "bridge.js"));

const version = JSON.parse(fs.readFileSync(path.join(repo, "package.json"), "utf8")).version;
fs.writeFileSync(path.join(out, "SOURCE.txt"), `Built from DevSnip Pro for VS Code ${version} (${repo}).\nDo not edit: run webview/build.mjs.\n`);
console.log(`Webviews written to ${path.relative(process.cwd(), out)} from VS Code extension ${version}.`);
