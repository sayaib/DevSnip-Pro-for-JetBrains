#!/usr/bin/env node
/*
 * The Database Client against real servers, end to end: the extension's own
 * DatabaseService and adapters (in host-runtime.js), the driver stand-ins
 * (shims/db-*.js) and the IDE's drivers (DbBridge.kt, run by DbBridgeServer
 * from the test classpath). Start the servers first (scripts/db-containers.sh)
 * and write the classpath (./gradlew writeTestClasspath).
 *
 *   node test/db-servers.mjs [kind...]
 */
import assert from "assert";
import { spawn } from "child_process";
import fs from "fs";
import path from "path";
import readline from "readline";
import { fileURLToPath } from "url";
import { createIde } from "./host-harness.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const classpath = fs.readFileSync(path.resolve(here, "../../build/test-classpath.txt"), "utf8").trim();

/** One bridge process for all servers. */
const server = spawn(process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, "bin/java") : "java", ["-cp", classpath, "pro.devsnip.api.core.DbBridgeServer"], { stdio: ["pipe", "pipe", "inherit"] });
const waiting = new Map();
let nextId = 1;
let deliver = () => undefined;
readline.createInterface({ input: server.stdout }).on("line", line => {
  let message;
  try { message = JSON.parse(line); } catch { return; }
  if (message.type === "__stream") return deliver(message);
  const call = waiting.get(message.id);
  waiting.delete(message.id);
  if (!call) return;
  if (message.error !== undefined) call.reject(Object.assign(new Error(message.error), message.errorData || {}));
  else call.resolve(message.result);
});
const bridge = {
  handles: method => /^(db|mongo|redis)\.|^http\.(raw|cancel)$/.test(method),
  call: (method, params) => new Promise((resolve, reject) => {
    const id = nextId++;
    waiting.set(id, { resolve, reject });
    server.stdin.write(JSON.stringify({ id, method, params }) + "\n");
  })
};

/** The VS Code side: the same DatabaseService with the real Node drivers from the extension's node_modules. */
import { build } from "esbuild";
import { createRequire } from "module";
const repo = path.resolve(process.env.DEVSNIP_VSCODE_REPO || path.join(here, "../../../DevSnip-Pro"));
const referencePath = path.join(here, "../.build/db-reference.cjs");
await build({
  stdin: { contents: 'module.exports = { ...require("@devsnip/database/service"), ...require("@devsnip/database/store") };', resolveDir: here, loader: "js" },
  outfile: referencePath, bundle: true, format: "cjs", platform: "node", target: "node18",
  nodePaths: [path.join(repo, "node_modules")],
  external: ["pg", "mysql2", "mysql2/*", "mssql", "oracledb", "mongodb", "ioredis", "@ioredis/commands", "sql.js", "pg-native", "node:sqlite"],
  alias: { "@devsnip": path.join(repo, "src"), vscode: path.join(here, "../shims/vscode.cjs") },
  logLevel: "error"
});
const nodeRequire = createRequire(path.join(repo, "package.json"));
const reference = createRequire(import.meta.url)(referencePath);
// The bundle's require() of a driver resolves from the extension's node_modules.
const Module = nodeRequire("module");
const resolveFrom = Module._resolveFilename;
Module._resolveFilename = function (request, parent, ...rest) {
  try { return resolveFrom.call(this, request, parent, ...rest); } catch (error) {
    if (/^(pg|mysql2|mssql|oracledb|mongodb|ioredis|@ioredis\/commands)(\/|$)/.test(request)) return nodeRequire.resolve(request);
    throw error;
  }
};

function referenceCall() {
  const state = new Map();
  const secrets = new Map();
  const store = new reference.ConnectionStore(
    { get: (k, d) => (state.has(k) ? state.get(k) : d), update: async (k, v) => { state.set(k, v); }, keys: () => [...state.keys()] },
    { get: async k => secrets.get(k), store: async (k, v) => { secrets.set(k, v); }, delete: async k => { secrets.delete(k); } }
  );
  const ui = { confirm: async () => true, confirmByTyping: async () => true, pickSqliteFile: async () => undefined, copy: async () => undefined, openDocument: async () => undefined, baseDir: () => undefined };
  const service = new reference.DatabaseService(store, ui, () => undefined);
  const call = async (method, params = {}) => {
    try {
      return JSON.parse(JSON.stringify(await service.handle(method, params) ?? null));
    } catch (error) {
      throw new Error(`${method}: ${error.message}${error.hint ? ` (${error.hint})` : ""}`);
    }
  };
  return { call, close: async () => { for (const c of await call("init").then(r => r?.connections ?? []).catch(() => [])) await call("disconnect", { id: c.id }).catch(() => undefined); } };
}

const SERVERS = {
  mysql: { url: "mysql://root:devsnip@127.0.0.1:33306/app", table: "people" },
  mariadb: { url: "mariadb://root:devsnip@127.0.0.1:33307/app", table: "people" },
  sqlserver: { url: "Server=127.0.0.1,31433;Database=master;User Id=sa;Password=DevSnip!2026x;Encrypt=false", table: "people", schema: "dbo" },
  oracle: { url: "oracle://system:devsnip@127.0.0.1:31521/FREEPDB1", table: "PEOPLE", schema: "SYSTEM" },
  clickhouse: { url: "clickhouse://default:devsnip@127.0.0.1:38123/default", table: "people" },
  redis: { url: "redis://:devsnip@127.0.0.1:36379/0" },
  postgres: { url: "postgresql://postgres:devsnip@127.0.0.1:35432/app", table: "people", schema: "public" },
  // The server only accepts TLS (scripts/db-containers.sh).
  "postgres-tls": { url: "postgresql://postgres:devsnip@127.0.0.1:35433/app?sslmode=require", table: "people", schema: "public" },
  cockroachdb: { url: "cockroachdb://root@127.0.0.1:36257/app?sslmode=disable", table: "people", schema: "public" }
};

const DDL = {
  mysql: ["DROP TABLE IF EXISTS people", "CREATE TABLE people (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(50) NOT NULL, score DECIMAL(6,2), born DATETIME, big BIGINT, meta JSON, raw BLOB)", "INSERT INTO people (name, score, born, big, meta, raw) VALUES ('Ada', 9.50, '1815-12-10 08:00:00', 9007199254740993, '{\"lang\":\"en\"}', X'0102'), ('Linus', 7.25, NULL, 1, NULL, NULL)"],
  sqlserver: ["IF OBJECT_ID('dbo.people') IS NOT NULL DROP TABLE dbo.people", "CREATE TABLE dbo.people (id INT IDENTITY PRIMARY KEY, name NVARCHAR(50) NOT NULL, score DECIMAL(6,2), born DATETIME2, big BIGINT, raw VARBINARY(10))", "INSERT INTO dbo.people (name, score, born, big, raw) VALUES (N'Ada', 9.50, '1815-12-10T08:00:00', 9007199254740993, 0x0102), (N'Linus', 7.25, NULL, 1, NULL)"],
  oracle: ["BEGIN EXECUTE IMMEDIATE 'DROP TABLE PEOPLE'; EXCEPTION WHEN OTHERS THEN NULL; END;", "CREATE TABLE PEOPLE (ID NUMBER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, NAME VARCHAR2(50) NOT NULL, SCORE NUMBER(6,2), BORN DATE, BIG NUMBER(20), RAW_DATA RAW(10))", "INSERT INTO PEOPLE (NAME, SCORE, BORN, BIG, RAW_DATA) VALUES ('Ada', 9.5, TO_DATE('1815-12-10 08:00:00','YYYY-MM-DD HH24:MI:SS'), 9007199254740993, HEXTORAW('0102'))", "INSERT INTO PEOPLE (NAME, SCORE) VALUES ('Linus', 7.25)"],
  clickhouse: ["DROP TABLE IF EXISTS people", "CREATE TABLE people (id UInt32, name String, score Decimal(6,2), born Nullable(DateTime), big Int64) ENGINE = MergeTree ORDER BY id", "INSERT INTO people VALUES (1, 'Ada', 9.50, '2015-12-10 08:00:00', 9007199254740993), (2, 'Linus', 7.25, NULL, 1)"]
};
DDL.mariadb = DDL.mysql;
DDL.postgres = ["DROP TABLE IF EXISTS people", "CREATE TABLE people (id SERIAL PRIMARY KEY, name VARCHAR(50) NOT NULL, score DECIMAL(6,2), born TIMESTAMP, big BIGINT, meta JSONB, raw BYTEA)", "INSERT INTO people (name, score, born, big, meta, raw) VALUES ('Ada', 9.50, '1815-12-10 08:00:00', 9007199254740993, '{\"lang\":\"en\"}', '\\x0102'), ('Linus', 7.25, NULL, 1, NULL, NULL)"];
DDL["postgres-tls"] = DDL.postgres;
DDL.cockroachdb = DDL.postgres;

let failures = 0;
const results = [];

async function run(kind, mode = "ide") {
  const spec = SERVERS[kind];
  const record = {};
  let ide;
  let call;
  let ref;
  if (mode === "node") {
    ref = referenceCall();
    call = ref.call;
  } else {
  ide = createIde({ quiet: true, rpc: bridge });
  // The user confirms what the client asks (Run a destructive statement, Delete rows).
  ide.answers.message = { shift: () => p => (p.modal && p.items.length ? 0 : null) };
  deliver = message => ide.deliver(message);
  ide.start();
  await ide.settle(150);
  assert.equal((await ide.exec("sayaib.hue-console.databaseClient")).error, null);
  const panel = ide.panelByType("devsnip.databaseClient");
  let rpcId = 1;
  call = async (method, params = {}) => {
    const id = rpcId++;
    ide.panelMessage(panel.panel, { type: "rpc", id, method, params });
    for (let i = 0; i < 1200; i++) {
      await ide.settle(25);
      const reply = panel.posted.find(m => m && m.type === "rpc" && m.id === id);
      if (reply) {
        if (!reply.ok) throw new Error(`${method}: ${reply.error?.message}${reply.error?.hint ? ` (${reply.error.hint})` : ""}`);
        return reply.result;
      }
    }
    throw new Error(`${method}: no answer in 30 s`);
  };
  }
  const steps = [];
  const step = async (name, fn) => {
    try {
      const detail = await fn();
      steps.push(`ok   ${name}${detail ? `: ${detail}` : ""}`);
    } catch (error) {
      failures++;
      steps.push(`FAIL ${name}: ${error.message.split("\n")[0]}`);
    }
  };
  let id;
  await step("save and connect", async () => {
    const saved = await call("saveConnection", { name: kind, connectionString: spec.url });
    id = saved.id;
    const info = await call("connect", { id });
    return info.serverVersion ?? info.version ?? JSON.stringify(info).match(/"(?:server)?[vV]ersion":"([^"]+)"/)?.[1];
  });
  if (!id) return await finish();
  const database = kind === "sqlserver" ? "master" : kind === "oracle" ? undefined : kind === "redis" ? "0" : kind === "clickhouse" ? "default" : "app";
  await step("list databases", async () => (await call("listDatabases", { id })).slice?.(0, 6).join(", ") ?? "");
  if (kind === "redis") {
    await step("run commands", async () => {
      await call("runQuery", { id, text: "DEL devsnip:greeting devsnip:user devsnip:list", database });
      const r = await call("runQuery", { id, text: "SET devsnip:greeting hello\nHSET devsnip:user name Ada lang en\nRPUSH devsnip:list a b c\nGET devsnip:greeting", database });
      return JSON.stringify(r.rows ?? r.message).slice(0, 120);
    });
    await step("browse keys", async () => {
      const page = await call("fetchPage", { id, request: { target: { database, object: "keys" }, page: 1, pageSize: 50, query: "devsnip:*" } });
      record.page = { rows: page.rows.map(r => ({ ...r, ttl: r.ttl === null ? null : "set" })) };
      assert.ok(page.rows.length >= 3, `rows: ${JSON.stringify(page.rows)}`);
      return page.rows.map(r => `${r.key}(${r.type})`).join(" ");
    });
    await step("read and edit a key", async () => {
      const key = await call("readKey", { id, database, key: "devsnip:user" });
      record.key = { ...key, ttlMs: key.ttlMs < 0 ? key.ttlMs : "set" };
      assert.ok(JSON.stringify(key.value).includes("Ada"), JSON.stringify(key));
      await call("updateRow", { id, target: { database, object: "keys" }, key: { key: "devsnip:greeting" }, values: { value: { mode: "value", value: "bonjour" } } });
      const r = await call("runQuery", { id, text: "GET devsnip:greeting", database });
      assert.ok(JSON.stringify(r).includes("bonjour"), JSON.stringify(r));
      return "updated";
    });
    return await finish();
  }
  await step("create a table with the console", async () => {
    for (const sql of DDL[kind]) await call("runQuery", { id, text: sql, database, schema: spec.schema });
    return `${DDL[kind].length} statements`;
  });
  const target = { database: kind === "oracle" ? undefined : database, schema: spec.schema, object: spec.table };
  await step("list tables", async () => {
    const objects = await call("listObjects", { id, database: target.database ?? "", schema: spec.schema });
    assert.ok(objects.some(o => o.name.toLowerCase() === spec.table.toLowerCase()), JSON.stringify(objects).slice(0, 200));
    return `${objects.length} objects`;
  });
  await step("describe columns", async () => {
    const columns = await call("describe", { id, target });
    record.describe = columns;
    return columns.map(c => `${c.name}:${c.category}${c.primaryKey ? "*" : ""}`).join(" ");
  });
  let page;
  await step("page of rows with values as the Node driver gives them", async () => {
    page = await call("fetchPage", { id, request: { target, page: 1, pageSize: 50, sort: { column: kind === "oracle" ? "ID" : "id", dir: "asc" } } });
    record.page = { rows: page.rows, total: page.total, keyColumns: page.keyColumns, keyStrategy: page.keyStrategy, columns: page.columns };
    record.query = (await call("runQuery", { id, text: kind === "oracle" ? "SELECT NAME, SCORE, BORN, BIG FROM PEOPLE ORDER BY ID" : kind === "sqlserver" ? "SELECT name, score, born, big FROM dbo.people ORDER BY id" : "SELECT name, score, born, big FROM people ORDER BY id", database, schema: spec.schema })).rows;
    assert.equal(page.total, 2, `total ${page.total}`);
    const ada = page.rows[0];
    const name = ada.name ?? ada.NAME;
    assert.equal(name, "Ada");
    return JSON.stringify(ada).slice(0, 230);
  });
  if (kind !== "clickhouse") {
    await step("insert, update and delete rows from the grid", async () => {
      const col = n => (kind === "oracle" ? n.toUpperCase() : n);
      await call("insertRow", { id, target, values: { [col("name")]: { mode: "value", value: "Grace" }, [col("score")]: { mode: "value", value: "8.75" } } });
      let rows = (await call("fetchPage", { id, request: { target, page: 1, pageSize: 50, sort: { column: col("id"), dir: "asc" } } })).rows;
      assert.equal(rows.length, 3, "insert");
      const grace = rows.find(r => r[col("name")] === "Grace");
      const key = { [col("id")]: grace[col("id")] };
      await call("updateRow", { id, target, key, strategy: page.keyStrategy, values: { [col("score")]: { mode: "value", value: "9.10" } } });
      rows = (await call("fetchPage", { id, request: { target, page: 1, pageSize: 50 } })).rows;
      const updated = rows.find(r => r[col("name")] === "Grace")[col("score")];
      assert.ok(String(updated).startsWith("9.1"), `updated score ${updated}`);
      await call("deleteRows", { id, target, keys: [key], strategy: page.keyStrategy });
      rows = (await call("fetchPage", { id, request: { target, page: 1, pageSize: 50 } })).rows;
      assert.equal(rows.length, 2, "delete");
      return `key strategy ${page.keyStrategy}`;
    });
  }
  await step("query console with an error position", async () => {
    try {
      await call("runQuery", { id, text: kind === "oracle" ? "SELECT * FRM PEOPLE" : "SELECT * FRM people", database, schema: spec.schema });
    } catch (error) {
      return error.message.slice(0, 120);
    }
    throw new Error("the bad query ran");
  });
  await step("cancel a long query", async () => {
    const sleep = { mysql: "SELECT SLEEP(20)", mariadb: "SELECT SLEEP(20)", sqlserver: "WAITFOR DELAY '00:00:20'", oracle: "BEGIN DBMS_SESSION.SLEEP(20); END;", clickhouse: "SELECT sleepEachRow(1) FROM numbers(20) SETTINGS max_block_size = 1", postgres: "SELECT pg_sleep(20)", "postgres-tls": "SELECT pg_sleep(20)", cockroachdb: "SELECT pg_sleep(20)" }[kind];
    const started = Date.now();
    const running = call("runQuery", { id, text: sleep, database, schema: spec.schema, runId: "long" }).then(() => "finished", e => e.message);
    await new Promise(r => setTimeout(r, 2000));
    await call("cancelQuery", { id, runId: "long" });
    const outcome = await running;
    const seconds = (Date.now() - started) / 1000;
    assert.ok(seconds < 15, `still ran ${seconds}s (${outcome})`);
    return `${outcome.slice(0, 60)} after ${seconds.toFixed(1)}s`;
  });
  return await finish();

  async function finish() {
    if (mode === "ide") {
      results.push(`\n${kind}\n  ${steps.join("\n  ")}`);
      ide.cleanup();
    } else {
      const bad = steps.filter(s => s.startsWith("FAIL"));
      if (bad.length) results.push(`\n${kind} (Node drivers, reference)\n  ${bad.join("\n  ")}`);
      await ref.close();
    }
    return record;
  }
}

const kinds = process.argv.slice(2).length ? process.argv.slice(2) : Object.keys(SERVERS);
/** Volatile parts (elapsed times, generated ids) are left out of the comparison. */
const stable = value => JSON.parse(JSON.stringify(value ?? null, (k, v) => (/^(elapsedMs|id|ID)$/.test(k) ? undefined : v)));
for (const kind of kinds) {
  try {
    const failed = failures;
    const expected = await run(kind, "node");
    if (failures > failed) {
      results.push(`  (the reference run failed; the IDE run is compared with nothing)`);
      failures = failed;
    }
    const actual = await run(kind, "ide");
    for (const part of Object.keys(expected)) {
      const a = JSON.stringify(stable(actual[part])), e = JSON.stringify(stable(expected[part]));
      if (a === e) results.push(`  same as VS Code: ${part}`);
      else {
        failures++;
        let i = 0;
        while (i < a.length && a[i] === e[i]) i++;
        results.push(`  DIFFERENT from VS Code: ${part}\n    IDE:     …${a.slice(Math.max(0, i - 80), i + 120)}\n    VS Code: …${e.slice(Math.max(0, i - 80), i + 120)}`);
      }
    }
  } catch (error) {
    failures++;
    results.push(`\n${kind}\n  FAIL ${error.stack}`);
  }
}
console.log(results.join("\n"));
server.stdin.end();
console.log(failures ? `\n${failures} failure(s).` : "\nEvery database works end to end.");
process.exit(failures ? 1 : 0);
