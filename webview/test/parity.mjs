#!/usr/bin/env node
/*
 * Checks that the browser bundle (toolkit-runtime.js, with the crypto/os/path
 * shims) gives the same results as the DevSnip Pro toolkit on real Node.
 *
 * Every tool is run with its defaults, each mode and each example, on both.
 * Cases whose Node result changes between two runs (random ids, keys,
 * timestamps) are compared by shape instead of value.
 *
 *   DEVSNIP_VSCODE_REPO=../../DevSnip-Pro node test/parity.mjs
 */
import { build } from "esbuild";
import fs from "fs";
import path from "path";
import vm from "vm";
import os from "os";
import nodeCrypto from "crypto";
import { execFileSync } from "child_process";
import { createRequire } from "module";
import { fileURLToPath } from "url";

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, "..");
const repo = path.resolve(process.env.DEVSNIP_VSCODE_REPO || path.join(root, "../../DevSnip-Pro"));
const bundlePath = path.resolve(root, "../src/main/resources/webview/toolkit-runtime.js");

// Reference: the same toolkit, bundled for Node with the real built-ins.
const reference = path.join(root, ".build/reference.cjs");
await build({
  stdin: { contents: 'module.exports = require("@devsnip/toolkits/registry");', resolveDir: root, loader: "js" },
  outfile: reference,
  bundle: true,
  format: "cjs",
  platform: "node",
  target: "node18",
  nodePaths: [path.join(repo, "node_modules")],
  alias: { "@devsnip": path.join(repo, "src"), vscode: path.join(root, "shims/vscode.cjs") },
  logLevel: "error"
});
const { ALL_TOOLS } = createRequire(import.meta.url)(reference);

// Candidate: the browser bundle in a fresh context with only browser globals.
const sandbox = {
  console, setTimeout, clearTimeout, setInterval, clearInterval, queueMicrotask,
  crypto: globalThis.crypto, TextEncoder, TextDecoder, atob, btoa, URL, URLSearchParams, Intl, structuredClone,
  __devsnipHost: { networkInterfaces: os.networkInterfaces(), platform: process.platform }
};
sandbox.globalThis = sandbox;
sandbox.window = sandbox;
vm.createContext(sandbox);
vm.runInContext(fs.readFileSync(bundlePath, "utf8"), sandbox, { filename: "toolkit-runtime.js" });
const browser = sandbox.DevSnipToolkit;

const NOW = new Date("2026-10-01T10:00:00Z");
const ctx = () => ({ now: () => NOW });

function formValues(tool, overrides) {
  const all = {};
  for (const f of tool.fields) if (f.default !== undefined) all[f.id] = f.default;
  Object.assign(all, overrides);
  const visible = {};
  for (const f of tool.fields) {
    if (f.showIf && !f.showIf.equals.includes(all[f.showIf.field])) continue;
    if (all[f.id] !== undefined) visible[f.id] = all[f.id];
  }
  return visible;
}

async function runReference(tool, values) {
  try {
    return { outcome: "success", result: JSON.parse(JSON.stringify(await tool.run(values, ctx()))) };
  } catch (error) {
    return error?.constructor?.name === "ToolInputError" ? { outcome: "input_error", error: error.message } : { outcome: "error", error: String(error?.message) };
  }
}

/** Timing stats ("Time": "0 ms") differ run to run on any machine; they are compared as "<time>". */
const untimed = value => JSON.parse(JSON.stringify(value), (key, v) => (v && typeof v === "object" && v.label === "Time" && typeof v.value === "string" && / ms$/.test(v.value) ? { ...v, value: "<time>" } : v));

/** Same structure with every string/number replaced by its type, for results that are random. */
const shape = value => Array.isArray(value) ? value.map(shape) : value && typeof value === "object" ? Object.fromEntries(Object.entries(value).map(([k, v]) => [k, shape(v)])) : typeof value;

const cases = [];
for (const tool of ALL_TOOLS) {
  cases.push([tool, {}, "defaults"]);
  const mode = tool.fields.find(f => f.id === "mode" && f.kind === "select");
  if (mode) for (const o of mode.options) cases.push([tool, { mode: o.value }, `mode ${o.value}`]);
  for (const ex of tool.examples ?? []) cases.push([tool, ex.values, `example: ${ex.label}`]);
}

let same = 0;
let shapeOnly = 0;
const failures = [];
for (const [tool, overrides, label] of cases) {
  const values = formValues(tool, overrides);
  const a = untimed(await runReference(tool, values));
  const b = untimed(await runReference(tool, values));
  const got = untimed(await browser.run(tool.id, values, ctx()));
  const deterministic = JSON.stringify(a) === JSON.stringify(b);
  const expected = deterministic ? a : shape(a);
  const actual = deterministic ? got : shape(got);
  if (JSON.stringify(expected) === JSON.stringify(actual)) {
    deterministic ? same++ : shapeOnly++;
  } else {
    failures.push({ tool: tool.id, label, expected: a, actual: got });
  }
}

// ---- Cryptography: keys of every kind, signed on one side and verified on the other.
const jwt = ALL_TOOLS.find(t => t.id === "dev.jwt");
const keys = [];
const add = (alg, kind, pair, privPem, pubPem) => {
  keys.push({ alg, kind: `${kind} PEM`, priv: privPem, pub: pubPem });
  keys.push({ alg, kind: `${kind} JWK`, priv: JSON.stringify(pair.privateKey.export({ format: "jwk" })), pub: JSON.stringify(pair.publicKey.export({ format: "jwk" })) });
};
const rsa = nodeCrypto.generateKeyPairSync("rsa", { modulusLength: 2048 });
for (const alg of ["RS256", "RS384", "RS512", "PS256", "PS384", "PS512"]) add(alg, "RSA PKCS#8", rsa, rsa.privateKey.export({ type: "pkcs8", format: "pem" }), rsa.publicKey.export({ type: "spki", format: "pem" }));
keys.push({ alg: "RS256", kind: "RSA PKCS#1 PEM", priv: rsa.privateKey.export({ type: "pkcs1", format: "pem" }), pub: rsa.publicKey.export({ type: "pkcs1", format: "pem" }) });
for (const [alg, curve] of [["ES256", "prime256v1"], ["ES384", "secp384r1"], ["ES512", "secp521r1"]]) {
  const ec = nodeCrypto.generateKeyPairSync("ec", { namedCurve: curve });
  add(alg, `EC ${curve}`, ec, ec.privateKey.export({ type: "pkcs8", format: "pem" }), ec.publicKey.export({ type: "spki", format: "pem" }));
  keys.push({ alg, kind: `EC ${curve} SEC1 PEM`, priv: ec.privateKey.export({ type: "sec1", format: "pem" }), pub: ec.publicKey.export({ type: "spki", format: "pem" }) });
}
const ed = nodeCrypto.generateKeyPairSync("ed25519");
add("EdDSA", "Ed25519", ed, ed.privateKey.export({ type: "pkcs8", format: "pem" }), ed.publicKey.export({ type: "spki", format: "pem" }));

async function signWith(runner, alg, key) {
  const values = formValues(jwt, { mode: "sign", alg, payload: '{"sub":"1","name":"Ada"}', secret: key, privateKey: key, key, expiresIn: 0, iat: false });
  const out = runner === "node" ? await runReference(jwt, values) : JSON.parse(JSON.stringify(await browser.run(jwt.id, values, ctx())));
  if (out.outcome !== "success") return { error: out.error };
  return { token: out.result.outputs[0].content };
}
async function verifyWith(runner, token, key) {
  const values = formValues(jwt, { mode: "decode", token, secret: key, key, publicKey: key });
  const out = runner === "node" ? await runReference(jwt, values) : JSON.parse(JSON.stringify(await browser.run(jwt.id, values, ctx())));
  return out.outcome === "success" ? out.result.stats.find(s => s.label === "Signature")?.value : `error: ${out.error}`;
}
let cryptoOk = 0;
for (const k of keys) {
  for (const [signer, verifier] of [["browser", "node"], ["node", "browser"]]) {
    const signed = await signWith(signer, k.alg, k.priv);
    const label = `${k.alg} ${k.kind}: sign in ${signer}, verify in ${verifier}`;
    if (signed.error) { failures.push({ tool: "dev.jwt", label, expected: "a token", actual: signed.error }); continue; }
    const verdict = await verifyWith(verifier, signed.token, k.pub);
    const tampered = await verifyWith(verifier, signed.token.slice(0, -6) + (signed.token.endsWith("AAAAAA") ? "BBBBBB" : "AAAAAA"), k.pub);
    if (verdict === "valid" && tampered !== "valid") cryptoOk++;
    else failures.push({ tool: "dev.jwt", label, expected: "valid / not valid", actual: `${verdict} / ${tampered}` });
  }
}

// Certificates: an RSA and an EC certificate made by openssl, read by both sides.
const certTool = ALL_TOOLS.find(t => t.fields.some(f => f.id === "pem") && /cert/i.test(t.id + t.title));
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "devsnip-cert-"));
const certs = [];
for (const [name, args] of [["rsa", ["-newkey", "rsa:2048"]], ["ec", ["-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1"]]]) {
  try {
    execFileSync("openssl", ["req", "-x509", ...args, "-nodes", "-keyout", path.join(tmp, `${name}.key`), "-out", path.join(tmp, `${name}.pem`), "-days", "30", "-subj", "/C=US/O=DevSnip Test/CN=devsnip.test", "-addext", "subjectAltName=DNS:devsnip.test,DNS:*.devsnip.test,IP:127.0.0.1"], { stdio: "ignore" });
    certs.push([name, fs.readFileSync(path.join(tmp, `${name}.pem`), "utf8")]);
  } catch {
    console.log(`(openssl not available: skipping the ${name} certificate check)`);
  }
}
for (const [name, pem] of certs) {
  const values = formValues(certTool, { pem, mode: certTool.fields.find(f => f.id === "mode")?.options?.[0]?.value });
  const a = await runReference(certTool, values);
  const b = JSON.parse(JSON.stringify(await browser.run(certTool.id, values, ctx())));
  if (JSON.stringify(a) === JSON.stringify(b)) cryptoOk++;
  else failures.push({ tool: certTool.id, label: `${name} certificate`, expected: a, actual: b });
}
console.log(`Cryptography: ${cryptoOk} of ${keys.length * 2 + certs.length} cross-checks passed (${certTool.id}, dev.jwt).`);

console.log(`${cases.length} cases over ${ALL_TOOLS.length} tools: ${same} identical, ${shapeOnly} random (same shape), ${failures.length} different.`);
for (const f of failures.slice(0, 12)) {
  const e = JSON.stringify(f.expected);
  const g = JSON.stringify(f.actual);
  let i = 0;
  while (i < e.length && e[i] === g[i]) i++;
  console.log(`\n✗ ${f.tool} / ${f.label}\n  node:    …${e.slice(Math.max(0, i - 80), i + 160)}\n  browser: …${g.slice(Math.max(0, i - 80), i + 160)}`);
}
process.exitCode = failures.length ? 1 : 0;
