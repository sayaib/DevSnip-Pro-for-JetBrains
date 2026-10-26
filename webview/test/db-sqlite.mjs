#!/usr/bin/env node
/*
 * The Database Client end to end on SQLite, in the extension host with the
 * stand-in IDE: the extension's DatabaseService, its SQLite adapter and
 * sql.js (bundled in host-runtime.js) on a real database file.
 */
import assert from "assert";
import { execFileSync } from "child_process";
import path from "path";
import { createIde } from "./host-harness.mjs";

const ide = createIde({ quiet: true });
const db = path.join(ide.workspace, "app.db");
execFileSync("sqlite3", [db, "CREATE TABLE users(id INTEGER PRIMARY KEY, name TEXT NOT NULL, score REAL, data BLOB); INSERT INTO users(name, score, data) VALUES ('Ada', 9.5, x'0102'), ('Linus', 7, NULL);"]);
ide.start();
await ide.settle(100);
assert.equal((await ide.exec("sayaib.hue-console.databaseClient")).error, null);
const panel = ide.panelByType("devsnip.databaseClient");
assert.ok(panel && panel.html.includes("db-client.js"), "the Database Client page opens");

let nextId = 1;
async function call(method, params = {}) {
  const id = nextId++;
  ide.panelMessage(panel.panel, { type: "rpc", id, method, params });
  for (let i = 0; i < 200; i++) {
    await ide.settle(10);
    const reply = panel.posted.find(m => m && m.type === "rpc" && m.id === id);
    if (reply) {
      if (!reply.ok) throw new Error(`${method}: ${reply.error?.message} ${reply.error?.hint ?? ""}`);
      return reply.result;
    }
  }
  throw new Error(`${method}: no answer`);
}

const saved = await call("saveConnection", { name: "Local", connectionString: `sqlite://${db}` });
const id = saved.id ?? saved.connection?.id ?? (await call("init")).connections?.[0]?.id;
assert.ok(id, `a saved connection id (${JSON.stringify(saved).slice(0, 200)})`);
const info = await call("connect", { id });
console.log("connected:", JSON.stringify(info).slice(0, 160));
const objects = await call("listObjects", { id, database: "main" });
console.log("objects:", JSON.stringify(objects).slice(0, 200));
const page = await call("fetchPage", { id, request: { target: { database: "main", object: "users" }, page: 1, pageSize: 50 } });
console.log("page:", page.total, JSON.stringify(page.rows));
assert.equal(page.total, 2);
assert.equal(page.rows[0].name, "Ada");
assert.equal(page.rows[0].data.__dbv, "binary");
await call("insertRow", { id, target: { database: "main", object: "users" }, values: { name: { mode: "value", value: "Grace" }, score: { mode: "value", value: "8.25" } } });
const query = await call("runQuery", { id, text: "SELECT name, score FROM users ORDER BY id", context: { database: "main" } });
console.log("query:", JSON.stringify(query.rows));
assert.deepEqual(query.rows.map(r => r.name), ["Ada", "Linus", "Grace"]);
const check = execFileSync("sqlite3", [db, "SELECT count(*) FROM users"]).toString().trim();
assert.equal(check, "3", "the insert reached the file");
console.log("SQLite database client: ok");
ide.cleanup();
