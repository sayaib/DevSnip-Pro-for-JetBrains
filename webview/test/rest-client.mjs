#!/usr/bin/env node
/*
 * Checks the REST API Client's in-page feature system (api-client-runtime.js,
 * with the axios / tls / vscode shims) against the extension's own handlers
 * on real Node (real axios), message for message.
 *
 * The candidate runs in a fresh context with only browser globals. Its IDE is
 * emulated the way the plugin answers the bridge: http.raw sends the request
 * (here with fetch, streaming the body for responseType "stream"), http.send
 * stands for the client's own sender, storage is a map. Both sides talk to the
 * same local HTTP server (an OpenAI-compatible chat endpoint, an echo route),
 * so nothing leaves the machine.
 *
 *   DEVSNIP_VSCODE_REPO=../../DevSnip-Pro node test/rest-client.mjs
 */
import { build } from "esbuild";
import fs from "fs";
import http from "http";
import path from "path";
import vm from "vm";
import { createRequire } from "module";
import { fileURLToPath } from "url";
import { reword } from "../wording.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, "..");
const repo = path.resolve(process.env.DEVSNIP_VSCODE_REPO || path.join(root, "../../DevSnip-Pro"));
const bundlePath = path.resolve(root, "../src/main/resources/webview/api-client-runtime.js");

// ---------------------------------------------------------------- server
const server = http.createServer((req, res) => {
  let body = "";
  req.on("data", chunk => (body += chunk));
  req.on("end", () => {
    if (req.url.endsWith("/chat/completions")) {
      const request = JSON.parse(body || "{}");
      if (request.stream) {
        res.writeHead(200, { "Content-Type": "text/event-stream" });
        const words = ["Hello", " from", " the", " stream"];
        let i = 0;
        const tick = () => {
          if (i < words.length) {
            res.write(`data: ${JSON.stringify({ choices: [{ delta: { content: words[i++] } }] })}\n\n`);
            setTimeout(tick, 5);
          } else {
            res.end("data: [DONE]\n\n");
          }
        };
        tick();
        return;
      }
      const last = request.messages?.[request.messages.length - 1]?.content ?? "";
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        id: "chatcmpl-1",
        model: request.model,
        choices: [{ index: 0, finish_reason: "stop", message: { role: "assistant", content: `echo: ${last}` } }],
        usage: { prompt_tokens: 7, completion_tokens: 3, total_tokens: 10 }
      }));
      return;
    }
    if (req.url.startsWith("/fail")) {
      res.writeHead(500, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ error: { message: "upstream exploded" } }));
      return;
    }
    res.writeHead(200, { "Content-Type": "application/json", "Set-Cookie": "sid=abc; Path=/" });
    res.end(JSON.stringify({ method: req.method, url: req.url, body, token: "tok-1", user: { id: 42 } }));
  });
});
await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
const base = `http://127.0.0.1:${server.address().port}`;

// ------------------------------------------------------------- reference
const milestoneStub = {
  name: "milestone-stub",
  setup(b) {
    b.onResolve({ filter: /(^|\/)milestoneTracker$/ }, () => ({ path: path.join(root, "shims/milestone-tracker.js") }));
  }
};
const referencePath = path.join(root, ".build/api-client-reference.cjs");
await build({
  stdin: {
    contents: [
      'const f = require("@devsnip/commands/api-client-features");',
      'const a = require("@devsnip/premium/feature-access");',
      'const r = require("@devsnip/premium/feature-registry");',
      'const c = require("@devsnip/services/collections");',
      "module.exports = { ...f, ...a, ...r, ...c };"
    ].join("\n"),
    resolveDir: root,
    loader: "js"
  },
  outfile: referencePath,
  bundle: true,
  format: "cjs",
  platform: "node",
  target: "node18",
  nodePaths: [path.join(repo, "node_modules")],
  plugins: [milestoneStub],
  alias: { "@devsnip": path.join(repo, "src"), vscode: path.join(root, "shims/vscode.cjs") },
  logLevel: "error"
});
const ref = createRequire(import.meta.url)(referencePath);

/** A fixed sender for both sides (what http.send answers with in the plugin). */
async function sendHttp(request) {
  const url = String(request.url || "");
  const response = await fetch(url, { method: request.method || "GET", headers: request.headers || {}, body: request.data || undefined });
  const text = await response.text();
  let data = text;
  try { data = JSON.parse(text); } catch { /* text */ }
  return { status: response.status, statusText: response.statusText, headers: {}, data, responseTime: 1, size: `${text.length} B`, attempts: 1 };
}

const START_BALANCE = 100000;

function referenceContext() {
  const state = new Map();
  const extensionContext = {
    globalState: { get: (k, d) => (state.has(k) ? state.get(k) : d), update: async (k, v) => { state.set(k, v); }, keys: () => [...state.keys()] },
    secrets: { get: async () => undefined, store: async () => undefined }
  };
  // The same points ledger as the page's: premium features cost points from the milestone stats, as in VS Code.
  const STATS = "devsnip_user_stats";
  state.set(STATS, { totalPoints: START_BALANCE, lifetimePoints: START_BALANCE });
  const balance = () => state.get(STATS).totalPoints;
  // Both sides use the milestone stand-in, which reports the balance it is given (here the reference's own).
  const publish = () => { globalThis.__devsnipPoints = balance(); };
  publish();
  const access = new ref.FeatureAccessService(extensionContext, {
    balance,
    spend: async amount => { if (balance() < amount) return false; state.get(STATS).totalPoints -= amount; publish(); return true; },
    refund: async amount => { state.get(STATS).totalPoints += amount; publish(); }
  });
  const posted = [];
  return {
    posted,
    context: { access, collections: new ref.CollectionStore(extensionContext, access), extensionContext, post: m => posted.push(m), sendHttp }
  };
}

// ------------------------------------------------------------- candidate
function candidate() {
  let balance = START_BALANCE;
  const posted = [];
  const storage = new Map();
  const streams = new Map();
  const calls = [];
  const sandbox = {
    console, setTimeout, clearTimeout, setInterval, clearInterval, queueMicrotask,
    TextEncoder, TextDecoder, atob, btoa, URL, URLSearchParams, Intl, structuredClone, crypto: globalThis.crypto
  };
  sandbox.globalThis = sandbox;
  sandbox.window = sandbox;
  sandbox.__devsnipHost = { points: START_BALANCE };
  sandbox.__devsnipApiHost = {
    deliver: message => posted.push(message),
    onStream: (id, listener) => (listener ? streams.set(id, listener) : streams.delete(id)),
    storage: { get: k => storage.get(k), set: async (k, v) => { storage.set(k, v); }, keys: () => [...storage.keys()] },
    async rpc(method, params) {
      calls.push(method);
      if (method === "http.send") return sendHttp(params.request);
      // The IDE asks the extension host's milestone tracker (EnginePoints.kt); here, the same arithmetic.
      if (method === "points.spend") {
        if (balance < params.amount) return { ok: false, balance };
        balance -= params.amount;
        return { ok: true, balance };
      }
      if (method === "points.refund") {
        balance += params.amount;
        return { ok: true, balance };
      }
      if (method === "http.raw") {
        let response;
        try {
          response = await fetch(params.url, { method: params.method, headers: params.headers, body: params.body });
        } catch (error) {
          throw Object.assign(new Error(error.message), { code: "ECONNREFUSED" });
        }
        const headers = {};
        response.headers.forEach((value, key) => { headers[key] = value; });
        if (params.stream) {
          const reader = response.body.getReader();
          const decoder = new TextDecoder();
          (async () => {
            for (;;) {
              const { done, value } = await reader.read();
              if (done) break;
              streams.get(params.id)?.("data", decoder.decode(value, { stream: true }));
            }
            streams.get(params.id)?.("end");
          })();
          return { status: response.status, statusText: response.statusText, headers };
        }
        return { status: response.status, statusText: response.statusText, headers, body: await response.text() };
      }
      throw new Error(`unexpected rpc ${method}`);
    }
  };
  vm.createContext(sandbox);
  vm.runInContext(fs.readFileSync(bundlePath, "utf8"), sandbox, { filename: "api-client-runtime.js" });
  return { api: sandbox.DevSnipApiClient, posted, calls };
}

// ------------------------------------------------------------ comparison
const VOLATILE = /(^at$|ms$|Ms$|latency|elapsed|savedAt|updatedAt|perSecond|PerSecond|^id$|^saved$|^diagnostics$|^raw$|^fastest$|^slowest$|^cheapest$)/;
function stable(value) {
  if (Array.isArray(value)) return value.map(stable);
  if (value && typeof value === "object") {
    const out = {};
    for (const key of Object.keys(value).sort()) if (!VOLATILE.test(key)) out[key] = stable(value[key]);
    return out;
  }
  return value;
}
const results = messages => messages.filter(m => m.command !== "featureCatalog").map(stable);

const JWT = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
const request = { method: "POST", url: `${base}/echo`, headers: { "Content-Type": "application/json", "X-Trace": "1" }, data: '{"a":1}' };
const ai = { provider: "custom", baseUrl: `${base}/v1`, model: "test-model", apiKey: "k" };

const cases = [
  ...["javascript-fetch", "javascript-axios", "python", "go", "java", "csharp"].map(language => ({ command: "feature:generateCode", language, method: "POST", url: `${base}/echo`, headers: { A: "1" }, body: '{"a":1}' })),
  { command: "feature:generateCode", language: "cobol", url: base },
  { command: "feature:inspectJwt", token: JWT },
  { command: "feature:inspectJwt", token: "" },
  { command: "feature:jsonTools", action: "format", text: '{"b":[1,2],"a":{"c":true}}' },
  { command: "feature:jsonTools", action: "minify", text: '{ "b": [1, 2] }' },
  { command: "feature:jsonTools", action: "query", text: '{"data":{"items":[{"id":7}]}}', path: "data.items[0].id" },
  { command: "feature:jsonTools", action: "validate", text: '{"id":"x"}', schema: '{"type":"object","properties":{"id":{"type":"number"}},"required":["id","name"]}' },
  { command: "feature:jsonTools", action: "format", text: "{oops" },
  { command: "feature:runAssertions", rules: [
    { target: "status", operator: "equals", expected: 200 },
    { target: "json", operator: "equals", selector: "user.id", expected: 42 },
    { target: "header", operator: "contains", selector: "content-type", expected: "json" },
    { target: "body", operator: "contains", expected: "tok-" },
    { target: "latency", operator: "lessThan", expected: 500 },
    { target: "size", operator: "greaterThan", expected: 1 }
  ], response: { status: 200, responseTime: 120, headers: { "content-type": "application/json" }, data: { user: { id: 42 }, token: "tok-1" } } },
  { command: "feature:runAssertions", rules: [], response: {} },
  { command: "feature:diffResponses", left: '{"a":1,"b":[1,2],"c":{"d":1}}', right: '{"a":2,"b":[1],"e":null}' },
  { command: "feature:estimateTokens", provider: "openai", model: "gpt-4o-mini", prompt: "How many tokens is this sentence, roughly?", expectedCompletionTokens: 200 },
  { command: "feature:validateAiJson", text: 'Sure: ```json\n{"name":"x","n":1}\n```', schema: '{"type":"object","required":["name","n"]}' },
  { command: "feature:saveRequest", featureId: "collections-basic", request: { id: "r1", name: "Echo", folder: "Demo", method: "POST", url: `${base}/echo`, headers: { A: "1" }, params: {}, body: '{"a":1}', extract: { user: "user.id" } } },
  { command: "feature:saveRequest", featureId: "collections-basic", request: { id: "r2", name: "Second", folder: "Demo", method: "GET", url: `${base}/echo?u={{user}}`, headers: {}, params: { q: "{{user}}" } } },
  { command: "feature:saveRequest", featureId: "collections-basic", request: { name: "" } },
  { command: "feature:listCollections", featureId: "collections-basic" },
  { command: "feature:runChain", ids: ["r1", "r2"] },
  { command: "feature:runChain", ids: [] },
  { command: "feature:deleteRequest", featureId: "collections-basic", id: "r2" },
  { command: "feature:listCollections", featureId: "collections-basic" },
  { command: "feature:batchTest", count: 6, concurrency: 3, request },
  { command: "feature:unlockWithPoints", featureId: "sdk-export", request },
  { command: "feature:unlockWithPoints", featureId: "mock-generator", request },
  { command: "feature:unlockWithPoints", featureId: "load-test", request },
  { command: "feature:unlockWithPoints", featureId: "load-test", request: {} },
  { command: "feature:unlockWithPoints", featureId: "nope", request },
  { command: "feature:savePromptVersion", name: "greeting", text: "Hello\nworld", note: "first" },
  { command: "feature:savePromptVersion", name: "greeting", text: "Hello\nthere\nworld", note: "second" },
  { command: "feature:listPromptVersions", name: "greeting" },
  { command: "feature:diffPromptVersions", name: "greeting", left: 1, right: 2 },
  { command: "feature:llmRequest", featureId: "llm-request", ...ai, prompt: "ping" },
  { command: "feature:llmRequest", featureId: "prompt-test", ...ai, messages: [{ role: "system", content: "be brief" }, { role: "user", content: "pong" }] },
  { command: "feature:llmRequest", featureId: "llm-request", ...ai, baseUrl: `${base}/fail`, prompt: "x" },
  { command: "feature:compareModels", prompt: "hi", targets: [{ label: "A", ...ai }, { label: "B", ...ai, model: "other" }] },
  { command: "feature:benchmarkModel", ...ai, prompt: "hi", runs: 3, concurrency: 2 },
  { command: "feature:stream", ...ai, prompt: "go" },
  { command: "feature:stream", ...ai, prompt: "go", diagnostics: true },
  { command: "feature:aiAnalytics" },
  { command: "feature:clearAiAnalytics" },
  { command: "feature:aiAnalytics" },
  { command: "feature:websocketGrant", url: "wss://example.com/socket" },
  { command: "feature:websocketGrant", url: "https://example.com" },
  { command: "feature:doesNotExist" }
];

const reference = referenceContext();
const page = candidate();
let failures = 0;
for (const message of cases) {
  const refStart = reference.posted.length;
  await ref.handleFeatureMessage(structuredClone(message), reference.context);
  const pageStart = page.posted.length;
  await page.api.handle(structuredClone(message));
  const expected = JSON.stringify(results(reference.posted.slice(refStart)));
  const actual = JSON.stringify(results(page.posted.slice(pageStart)));
  if (expected !== actual) {
    failures++;
    console.error(`MISMATCH ${message.command} ${message.featureId || message.language || message.action || ""}\n  node:    ${expected.slice(0, 600)}\n  browser: ${actual.slice(0, 600)}`);
  }
}

// Premium features are priced as in VS Code: the page's catalog is the extension's.
const catalogMessages = page.posted.filter(m => m.command === "featureCatalog");
const features = catalogMessages.at(-1).categories.flatMap(c => c.groups.flatMap(g => g.features));
const referenceCatalog = reference.posted.filter(m => m.command === "featureCatalog").at(-1);
const priced = features.filter(f => f.pointCost);
// The page's text is the extension's with the JetBrains wording (wording.mjs).
const sameCatalog = JSON.stringify(stable(catalogMessages.at(-1))) === reword(JSON.stringify(stable(referenceCatalog)), "");
if (!catalogMessages.length || !priced.length || !sameCatalog) {
  failures++;
  console.error(`Catalog: the page's prices differ from the extension's (${priced.length} priced).`);
  const a = JSON.stringify(stable(catalogMessages.at(-1))), b = reword(JSON.stringify(stable(referenceCatalog)), "");
  let i = 0; while (i < a.length && a[i] === b[i]) i++;
  console.error(`  page:      …${a.slice(Math.max(0, i - 120), i + 120)}\n  extension: …${b.slice(Math.max(0, i - 120), i + 120)}`);
}
if (!page.api.owns({ command: "feature:jsonTools" }) || page.api.owns({ command: "feature:oauthToken" }) || page.api.owns({ command: "feature:applyOauthToken" }) || page.api.owns({ command: "testAPI" })) {
  failures++;
  console.error("owns(): OAuth and the client's own commands must go to the IDE.");
}
if (!page.calls.includes("http.raw") || !page.calls.includes("http.send")) {
  failures++;
  console.error(`The candidate did not use the IDE for HTTP (${[...new Set(page.calls)].join(", ")}).`);
}

server.close();
console.log(`${cases.length} feature messages compared, ${features.length} catalog features: ${failures ? `${failures} failure(s)` : "all match"}.`);
process.exit(failures ? 1 : 0);
