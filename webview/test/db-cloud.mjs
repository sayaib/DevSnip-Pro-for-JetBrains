#!/usr/bin/env node
/*
 * The Database Client's cloud connectors in the extension host with the
 * stand-in IDE: DynamoDB's signed REST calls go through the IDE's HTTP
 * client (shims/host-http.js → http.raw), a Google service account key is
 * signed by the crypto shim exactly as Node signs it, and an AWS profile's
 * credential_process is refused with the reason.
 */
import assert from "assert";
import crypto from "crypto";
import fs from "fs";
import os from "os";
import path from "path";
import { createIde } from "./host-harness.mjs";
import * as shimCrypto from "../shims/crypto.js";

let failures = 0;
async function check(name, run) {
  try {
    await run();
    console.log(`ok   ${name}`);
  } catch (error) {
    failures++;
    console.error(`FAIL ${name}\n     ${error.stack?.split("\n").slice(0, 3).join("\n     ")}`);
  }
}

const items = Array.from({ length: 3 }, (_, i) => ({ pk: { S: `k${i}` }, n: { N: String(i) } }));
const seen = [];
const http = p => {
  seen.push(p);
  const op = String(p.headers["x-amz-target"] ?? "").split(".").pop();
  const json = body => ({ status: 200, statusText: "OK", headers: { "content-type": ["application/x-amz-json-1.0"] }, body: JSON.stringify(body) });
  switch (op) {
    case "ListTables": return json({ TableNames: ["orders"] });
    case "DescribeTable": return json({ Table: { TableName: "orders", ItemCount: 3, KeySchema: [{ AttributeName: "pk", KeyType: "HASH" }], AttributeDefinitions: [{ AttributeName: "pk", AttributeType: "S" }] } });
    case "Scan": case "ExecuteStatement": return json({ Items: items });
  }
  return { status: 400, statusText: "Bad Request", headers: {}, body: JSON.stringify({ __type: "com.amazonaws.dynamodb.v20120810#UnknownOperationException", message: `No ${op}` }) };
};

// An AWS profile whose credentials come from a credential_process command line.
const awsDir = fs.mkdtempSync(path.join(os.tmpdir(), "devsnip-aws-"));
fs.writeFileSync(path.join(awsDir, "config"), "[profile tool]\nregion = us-east-1\ncredential_process = /usr/local/bin/get-creds --profile tool\n");
const ide = createIde({ quiet: true, http, env: { AWS_CONFIG_FILE: path.join(awsDir, "config"), AWS_SHARED_CREDENTIALS_FILE: path.join(awsDir, "credentials") } });
ide.start();
await ide.settle(100);
assert.equal((await ide.exec("sayaib.hue-console.databaseClient")).error, null);
const panel = ide.panelByType("devsnip.databaseClient");

let nextId = 1;
async function call(method, params = {}) {
  const id = nextId++;
  ide.panelMessage(panel.panel, { type: "rpc", id, method, params });
  for (let i = 0; i < 300; i++) {
    await ide.settle(10);
    const reply = panel.posted.find(m => m && m.type === "rpc" && m.id === id);
    if (reply) {
      if (!reply.ok) throw Object.assign(new Error(`${method}: ${reply.error?.message} ${reply.error?.hint ?? ""}`), { reply });
      return reply.result;
    }
  }
  throw new Error(`${method}: no answer`);
}

await check("DynamoDB connects, lists and pages through the IDE's HTTP client, signed with SigV4", async () => {
  const saved = await call("saveConnection", { name: "Orders", connectionString: "dynamodb://AKIAIOSFODNN7EXAMPLE:secret@us-west-2?endpoint=https%3A%2F%2Fddb.example.test" });
  const id = saved.id ?? saved.connection?.id ?? (await call("init")).connections?.[0]?.id;
  await call("connect", { id });
  const first = seen.find(p => /ListTables|DescribeTable/.test(p.headers["x-amz-target"] ?? ""));
  assert.ok(first, "a DynamoDB call reached the IDE");
  assert.equal(first.method, "POST");
  assert.equal(first.url, "https://ddb.example.test/");
  assert.match(first.headers.authorization, /^AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE\/\d{8}\/us-west-2\/dynamodb\/aws4_request, SignedHeaders=content-type;host;x-amz-date;x-amz-target, Signature=[0-9a-f]{64}$/);
  assert.equal(first.rejectUnauthorized, true);
  assert.equal(first.maxBytes, 64 * 1024 * 1024, "the IDE refuses bodies over 64 MB");
  assert.ok(first.timeoutMs > 0, "the IDE stops at the request's own timeout");
  assert.ok(!("content-length" in first.headers), "the IDE sets Content-Length itself");
  const objects = await call("listObjects", { id, database: "us-west-2" });
  assert.deepEqual(objects.map(o => o.name), ["orders"]);
  const page = await call("fetchPage", { id, request: { target: { database: "us-west-2", object: "orders" }, page: 1, pageSize: 10 } });
  assert.deepEqual(page.rows.map(r => r.pk), ["k0", "k1", "k2"]);
});

await check("an AWS credential_process is refused with the reason", async () => {
  const saved = await call("saveConnection", { name: "Tool", connectionString: "dynamodb://us-east-1?profile=tool" });
  const id = saved.id ?? saved.connection?.id;
  await assert.rejects(call("connect", { id }), /does not run shell command lines such as credential_process/);
});

await check("createSign (Google service account keys) matches Node's RSA-SHA256 signature", async () => {
  const { privateKey } = crypto.generateKeyPairSync("rsa", { modulusLength: 2048, privateKeyEncoding: { type: "pkcs8", format: "pem" }, publicKeyEncoding: { type: "spki", format: "pem" } });
  const input = "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJ4In0";
  const expected = crypto.createSign("RSA-SHA256").update(input).sign(privateKey, "base64");
  assert.equal(shimCrypto.createSign("RSA-SHA256").update(input).sign(privateKey, "base64"), expected);
  assert.equal(shimCrypto.createVerify("RSA-SHA256").update(input).verify(crypto.createPublicKey(privateKey).export({ type: "spki", format: "pem" }), expected, "base64"), true);
});

ide.cleanup();
fs.rmSync(awsDir, { recursive: true, force: true });
console.log(failures ? `\n${failures} failure(s).` : "\nCloud databases: ok");
process.exit(failures ? 1 : 0);
