#!/usr/bin/env node
/*
 * Checks the OSV and SCA scanners as the JetBrains plugin runs them
 * (scanner-runtime.js: the extension's panels and engine with the browser
 * shims, in a fresh context with only browser globals) against the
 * extension's own engine on real Node.
 *
 * Both scan a fixture project (test/fixtures/scan-project) against a fake
 * OSV.dev and CISA KEV built from test/fixtures/osv-db.json (fabricated
 * advisories); nothing leaves the machine. The IDE side is played by a small
 * Node host that answers the page's RPCs the way ScannerHost.kt does.
 *
 *   DEVSNIP_VSCODE_REPO=../../DevSnip-Pro node test/scanners.mjs
 */
import { build } from "esbuild";
import assert from "assert";
import fs from "fs";
import os from "os";
import path from "path";
import vm from "vm";
import { createRequire } from "module";
import { fileURLToPath } from "url";

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, "..");
const repo = path.resolve(process.env.DEVSNIP_VSCODE_REPO || path.join(root, "../../DevSnip-Pro"));
const bundlePath = path.resolve(root, "../src/main/resources/webview/scanner-runtime.js");
const db = JSON.parse(fs.readFileSync(path.join(here, "fixtures/osv-db.json"), "utf8"));

// ---- The fixture, copied to a temporary workspace (with an installed package that must be skipped).
const temp = fs.mkdtempSync(path.join(os.tmpdir(), "devsnip-scanner-test-"));
const workspace = path.join(temp, "scan-project");
fs.cpSync(path.join(here, "fixtures/scan-project"), workspace, { recursive: true });
fs.mkdirSync(path.join(workspace, "web/node_modules/left-pad"), { recursive: true });
fs.writeFileSync(path.join(workspace, "web/node_modules/left-pad/package.json"), JSON.stringify({ name: "left-pad", version: "1.0.0", dependencies: { lodash: "4.0.0" } }));
const home = path.join(temp, "home");
const storage = path.join(temp, "storage");
fs.mkdirSync(path.join(home, ".m2/repository"), { recursive: true });
fs.mkdirSync(storage, { recursive: true });

// ---- Fake OSV.dev and CISA KEV (the extension's FakeOsv, by package and version range).
const records = new Map(db.records.map(r => [r.id, r]));
const http = [];
function semverLess(a, b) {
  const pa = a.split(/[.-]/).map(Number);
  const pb = b.split(/[.-]/).map(Number);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) if ((pa[i] || 0) !== (pb[i] || 0)) return (pa[i] || 0) < (pb[i] || 0);
  return false;
}
function affected(record, ecosystem, name, version) {
  return record.affected.some(a => a.package.ecosystem === ecosystem && a.package.name.toLowerCase() === name.toLowerCase() &&
    a.ranges.some(r => {
      const introduced = r.events.find(e => e.introduced)?.introduced ?? "0";
      const fixed = r.events.find(e => e.fixed)?.fixed;
      return version === undefined || ((introduced === "0" || !semverLess(version, introduced)) && (!fixed || semverLess(version, fixed)));
    }));
}
function fakeNetwork(url, method, body) {
  http.push(`${method} ${url}`);
  const json = (status, value) => ({ status, headers: { "content-type": "application/json" }, text: JSON.stringify(value) });
  if (url === "https://api.osv.dev/v1/querybatch" && method === "POST") {
    const { queries } = JSON.parse(body);
    return json(200, {
      results: queries.map(q => {
        const hits = [...records.values()].filter(r => affected(r, q.package.ecosystem, q.package.name, q.version));
        return hits.length ? { vulns: hits.map(r => ({ id: r.id, modified: r.modified })) } : {};
      })
    });
  }
  const vuln = /^https:\/\/api\.osv\.dev\/v1\/vulns\/(.+)$/.exec(url);
  if (vuln) {
    const record = records.get(decodeURIComponent(vuln[1]));
    return record ? json(200, record) : json(404, { code: 5, message: "Vulnerability not found" });
  }
  if (url === "https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json") return json(200, db.kev);
  return json(404, {});
}

// ---- Reference: the extension's engine on Node, with the real built-ins.
const reference = path.join(root, ".build/scanner-reference.cjs");
await build({
  stdin: {
    contents: `
      exports.runScan = require("@devsnip/osv/scan").runScan;
      Object.assign(exports, require("@devsnip/osv/client"));
      exports.createPomRepository = require("@devsnip/osv/maven/resolver").createPomRepository;
      exports.resolveMavenProjects = require("@devsnip/osv/maven/index").resolveMavenProjects;
      exports.resolveGradleProjects = require("@devsnip/osv/gradle/index").resolveGradleProjects;
      exports.analyseScan = require("@devsnip/sca/engine").analyseScan;
      exports.detectProjects = require("@devsnip/sca/projects").detectProjects;
      exports.loadKev = require("@devsnip/sca/providers/kev").loadKev;`,
    resolveDir: root,
    loader: "js"
  },
  outfile: reference,
  bundle: true,
  format: "cjs",
  platform: "node",
  target: "node18",
  nodePaths: [path.join(repo, "node_modules")],
  alias: { "@devsnip": path.join(repo, "src"), vscode: path.join(root, "shims/vscode.cjs") },
  logLevel: "error"
});
const ref = createRequire(import.meta.url)(reference);

// ---- The IDE side, as ScannerHost.kt answers it.
/** VS Code glob semantics (as ScannerHost.globRegex): ** spans folders (also none), {a,b} alternatives. */
function globRegex(glob) {
  let out = "";
  for (let i = 0; i < glob.length; i++) {
    const c = glob[i];
    if (c === "*" && glob[i + 1] === "*") {
      if (glob[i + 2] === "/") { out += "(?:[^/]*/)*"; i += 2; } else { out += ".*"; i++; }
    } else if (c === "*") out += "[^/]*";
    else if (c === "?") out += "[^/]";
    else if (c === "{") out += "(?:";
    else if (c === "}") out += ")";
    else if (c === ",") out += "|";
    else out += c.replace(/[.+^$()|[\]\\]/g, "\\$&");
  }
  return new RegExp(`^${out}$`);
}

function walk(dir, base, exclude, out) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
    const full = path.join(dir, entry.name);
    const rel = path.relative(base, full).split(path.sep).join("/");
    if (entry.isDirectory()) {
      if (exclude && exclude.test(`${rel}/x`)) continue;
      walk(full, base, exclude, out);
    } else if (entry.isFile() && !(exclude && exclude.test(rel))) out.push({ full, rel });
  }
  return out;
}

const log = [];
const dialogs = [];
const saved = {};
function hostRpc(method, p) {
  switch (method) {
    case "ws.findFiles": {
      const base = p.base || workspace;
      const include = globRegex(p.include);
      const exclude = p.exclude ? globRegex(p.exclude) : null;
      return { paths: walk(base, base, exclude, []).filter(f => include.test(f.rel)).slice(0, p.max).map(f => f.full), facts: { exists: [] } };
    }
    case "fs.stat": {
      try {
        const s = fs.statSync(p.path);
        return { type: s.isDirectory() ? 2 : 1, size: s.size, mtime: s.mtimeMs };
      } catch {
        return null;
      }
    }
    case "fs.read": try { return fs.readFileSync(p.path, "utf8"); } catch { return null; }
    case "fs.write": fs.mkdirSync(path.dirname(p.path), { recursive: true }); fs.writeFileSync(p.path, p.text); return null;
    case "fs.mkdir": fs.mkdirSync(p.path, { recursive: true }); return null;
    case "fs.rm": fs.rmSync(p.path, { recursive: p.recursive, force: true }); return null;
    case "fs.mkdtemp": return fs.mkdtempSync(p.prefix);
    case "http.fetch": return fakeNetwork(p.url, p.method, p.body);
    case "http.abort": return null;
    case "ui.message": dialogs.push(p); return null;
    case "log.append": log.push(...p.lines.map(l => l.text)); return null;
    case "session.save": saved[p.key] = p.value; return null;
    case "secrets.get": return null;
    case "progress.start": return 1;
    case "progress.report": case "progress.end": case "state.update": case "panel.reveal": return null;
    default: throw new Error(`Unexpected RPC ${method}`);
  }
}

const settings = {
  "devsnip.osvScanner": { buildToolResolution: "never", downloadPoms: false, buildToolTimeoutSeconds: 300 },
  "devsnip.sca": { healthChecks: "off", knownExploited: true, nvdEnrichment: false, bomUpgradeSearch: false }
};

/** Opens a scanner page in a fresh browser-like context and returns its message channel. */
function openPage(page) {
  const received = [];
  // Each page gets its own storage, so no advisory cache carries over between checks.
  const globalStorage = fs.mkdtempSync(path.join(storage, `${page}-`));
  const sandbox = {
    console, setTimeout, clearTimeout, setInterval, clearInterval, queueMicrotask, TextEncoder, TextDecoder, URL, URLSearchParams,
    AbortController, AbortSignal, DOMException, Event, EventTarget, structuredClone, atob, btoa, Intl,
    addEventListener: () => undefined,
    __devsnipHost: {
      page, platform: process.platform === "win32" ? "win32" : "linux", home, tmpdir: os.tmpdir(), pathEnv: "", globalStorage,
      version: "test", ideVersion: "Test IDE", trusted: true, visible: true, settings,
      workspace: { name: "scan-project", path: workspace }, workspaceState: {}, session: {}, facts: { exists: [], texts: {} }
    },
    __devsnipDeliver: message => received.push(JSON.parse(JSON.stringify(message)))
  };
  sandbox.__devsnipRpc = (method, params) => new Promise((resolve, reject) => setTimeout(() => {
    try { resolve(JSON.parse(JSON.stringify(hostRpc(method, JSON.parse(JSON.stringify(params))) ?? null))); } catch (error) { reject(error); }
  }, 0));
  sandbox.globalThis = sandbox;
  sandbox.window = sandbox;
  vm.createContext(sandbox);
  vm.runInContext(fs.readFileSync(bundlePath, "utf8"), sandbox, { filename: "scanner-runtime.js" });
  return {
    received,
    send: message => sandbox.__devsnipToExtension(message),
    async waitFor(type, timeoutMs = 30000) {
      const started = Date.now();
      for (;;) {
        const hit = received.find(m => m.type === type);
        if (hit) return hit;
        const failed = received.find(m => m.type === "error");
        if (failed) throw new Error(`The scanner reported: ${failed.message}`);
        if (Date.now() - started > timeoutMs) throw new Error(`No "${type}" message; got ${received.map(m => m.type).join(", ")}`);
        await new Promise(r => setTimeout(r, 20));
      }
    }
  };
}

/** Timestamps and durations differ run to run; they are compared as their type. */
const TIMED = /(At|Ms|^durationMs|^timings)$/;
function untimed(value, key = "") {
  if (Array.isArray(value)) return value.map(v => untimed(v));
  if (value && typeof value === "object") {
    if (key === "timings") return Object.keys(value).sort();
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, TIMED.test(k) ? typeof v : untimed(v, k)]));
  }
  if (typeof value === "string") return value.replace(/\d+(\.\d+)? ?(ms|s)\b/g, "<time>");
  return value;
}

// ---- What the reference scans: the files discovery must find, read from disk.
const EXPECTED = ["api/pom.xml", "requirements.txt", "web/package-lock.json", "web/package.json", "common.txt"];
const files = EXPECTED.map(rel => ({ path: rel, text: fs.readFileSync(path.join(workspace, rel), "utf8"), ...(rel === "common.txt" ? { hint: "requirements" } : {}) }));
const LIMITATIONS = ["Installed packages and caches (node_modules, .venv, site-packages, …) are skipped; their manifests belong to dependencies, not this project."];
const localRepo = path.join(home, ".m2", "repository");

async function referenceScan(title, name) {
  const repository = ref.createPomRepository({
    readLocal: async relative => { try { return fs.readFileSync(path.join(localRepo, relative), "utf8"); } catch { return undefined; } },
    fetchText: undefined, cache: undefined, concurrency: 8, onFetch: () => undefined
  });
  const strategy = { repository, repositoryLabel: `POMs from the local Maven repository ${localRepo} only` };
  const client = new ref.OsvClient({
    userAgent: `DevSnip-Pro/test (VS Code Test IDE; ${title})`,
    fetch: async (url, init) => {
      const r = fakeNetwork(url, init.method, init.body);
      return { ok: r.status < 300, status: r.status, headers: { get: () => null }, text: async () => r.text };
    }
  });
  const result = await ref.runScan(files, {
    client, cache: new ref.OsvCache(2000), toolVersion: "test", workspace: { name, folders: ["scan-project"] }, source: "workspace",
    limitations: LIMITATIONS, unsupported: [],
    resolveMaven: (poms, signal) => ref.resolveMavenProjects(poms, strategy, { signal }),
    resolveGradle: (gradle, signal) => ref.resolveGradleProjects(gradle, { ...strategy, relativeDir: () => undefined }, { signal })
  });
  return { result, client };
}

let failures = 0;
async function check(name, fn) {
  try {
    await fn();
    console.log(`ok   ${name}`);
  } catch (error) {
    failures++;
    console.log(`FAIL ${name}\n     ${String(error?.stack || error).split("\n").slice(0, 12).join("\n     ")}`);
  }
}

await check("OSV scanner: discovery finds the project's dependency files and skips installed packages", async () => {
  const page = openPage("osv");
  page.send({ type: "ready" });
  const init = await page.waitFor("init");
  assert.strictEqual(init.workspace.name, "scan-project");
  page.send({ type: "scan" });
  const { result } = await page.waitFor("result");
  assert.deepStrictEqual(result.files.map(f => f.path).sort(), [...EXPECTED].sort());
  assert.ok(!http.some(r => r.includes("left-pad")));
  assert.deepStrictEqual(saved.result?.summary, result.summary, "the IDE keeps the last result");
  globalThis.osvResult = result;
  // For a look at the real page with a real result (WebviewAssets previews in build/webview-preview).
  fs.mkdirSync(path.join(root, ".build"), { recursive: true });
  fs.writeFileSync(path.join(root, ".build/osv-result.json"), JSON.stringify(result));
});

await check("OSV scanner: same result as the extension's engine on Node", async () => {
  const { result } = await referenceScan("OSV scan", "scan-project");
  const candidate = globalThis.osvResult;
  assert.ok(candidate, "no candidate result");
  assert.deepStrictEqual(untimed(candidate), untimed(JSON.parse(JSON.stringify(result))));
  assert.deepStrictEqual(candidate.findings.map(f => f.id).sort(), ["GHSA-test-0001-lodash", "GHSA-test-0002-requests", "GHSA-test-0003-jinja2", "GHSA-test-0004-jackson"]);
});

await check("OSV scanner: export, copy and remediation go through the IDE only with the current result", async () => {
  const page = openPage("osv");
  page.send({ type: "ready" });
  await page.waitFor("init");
  page.send({ type: "runCommand", command: "rm -rf /", cwd: "" });
  const toast = await page.waitFor("toast");
  assert.match(toast.message, /not part of the current scan/);
});

await check("SCA scanner: detects projects and matches the extension's analysis", async () => {
  const page = openPage("sca");
  page.send({ type: "ready" });
  await page.waitFor("init");
  const projects = await page.waitFor("projects");
  assert.ok(projects.projects.length >= 3, `projects: ${JSON.stringify(projects.projects.map(p => p.dir))}`);
  page.received.length = 0;
  page.send({ type: "scan" });
  const { result } = await page.waitFor("result");

  const { result: scan } = await referenceScan("SCA scan", "scan-project");
  const providers = {
    healthScope: "off",
    kev: signal => ref.loadKev(async (url, sig) => { const r = fakeNetwork(url, "GET"); return { status: r.status, json: async () => JSON.parse(r.text) }; }, undefined, { signal })
  };
  const texts = new Map(files.map(f => [f.path, f.text]));
  const expected = await ref.analyseScan(scan, providers, {
    projects: ref.detectProjects(files, "scan-project"), texts, toolVersion: scan.toolVersion, startedAt: Date.now(),
    discovery: { durationMs: 0, leftOutAsBuildOutput: 0 }
  });
  assert.deepStrictEqual(untimed(result), untimed(JSON.parse(JSON.stringify(expected))));
  fs.writeFileSync(path.join(root, ".build/sca-result.json"), JSON.stringify({ result, projects: projects.projects }));
  assert.ok(result.summary.exploited >= 1, "the fixture KEV entry marks jackson-databind as exploited");
});

fs.rmSync(temp, { recursive: true, force: true });
if (failures) {
  console.log(`\n${failures} scanner check(s) failed.`);
  process.exitCode = 1;
} else {
  console.log("\nThe OSV and SCA scanners match the extension.");
}
