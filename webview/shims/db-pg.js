/*
 * The `pg` driver for the Database Client's PostgreSQL / CockroachDB adapter,
 * on the IDE's PostgreSQL JDBC driver (DbBridge.kt). As the adapter
 * configures pg: booleans, small integers, floats, bytea and JSON are parsed;
 * every other value stays the server's text.
 */
import { EventEmitter, bytes, param, rowObjects, rpc, tagOf, verbs } from "./db-wire.js";

/** Type ids by name, for `fields[].dataTypeID`. */
const OIDS = {
  bool: 16, bytea: 17, char: 18, name: 19, int8: 20, int2: 21, int4: 23, text: 25, oid: 26, json: 114, xml: 142, float4: 700, float8: 701,
  money: 790, bpchar: 1042, varchar: 1043, date: 1082, time: 1083, timestamp: 1114, timestamptz: 1184, interval: 1186, timetz: 1266,
  bit: 1560, varbit: 1562, numeric: 1700, uuid: 2950, jsonb: 3802, inet: 869, cidr: 650, macaddr: 829
};

function convert(value) {
  switch (tagOf(value)) {
    case "$json": try { return JSON.parse(value.$json); } catch { return value.$json; }
    case "$bytes": return bytes(value);
    case "$big": return value.$big;
    case "$num": return value.$num;
    case "$date": return new Date(Number(value.$date));
    case "$ts": return value.$ts;
    case "$interval": return value.$interval;
    default: return value;
  }
}

function result(set, command) {
  if (!set.columns) return { command, rowCount: set.updateCount ?? 0, rows: [], fields: [], oid: 0 };
  const fields = set.columns.map(c => ({ name: c.name, dataTypeID: OIDS[String(c.type).toLowerCase()] ?? 25, format: "text" }));
  return { command: command || "SELECT", rowCount: set.rows.length, rows: rowObjects(set, convert), fields, oid: 0 };
}

function sslOptions(ssl) {
  if (!ssl) return null;
  if (ssl === true) return { rejectUnauthorized: true, verifyHost: true };
  return {
    rejectUnauthorized: ssl.rejectUnauthorized !== false,
    verifyHost: typeof ssl.checkServerIdentity !== "function",
    ca: ssl.ca ? String(ssl.ca) : null,
    cert: ssl.cert ? String(ssl.cert) : null,
    key: ssl.key ? String(ssl.key) : null
  };
}

async function open(config) {
  return rpc("db.connect", {
    kind: "postgresql",
    host: config.host || "localhost",
    port: Number(config.port) || 5432,
    user: config.user || "",
    password: config.password || "",
    database: config.database || "",
    ssl: sslOptions(config.ssl),
    options: config.options || "",
    applicationName: config.application_name || "DevSnip Pro",
    connectTimeoutMs: config.connectionTimeoutMillis || 10000
  });
}

class Client extends EventEmitter {
  constructor(pool, connection) {
    super();
    this.pool = pool;
    this.id = connection.id;
    this.processID = connection.pid;
  }

  async query(textOrConfig, values, callback) {
    const text = typeof textOrConfig === "string" ? textOrConfig : textOrConfig.text;
    const params = (typeof textOrConfig === "object" && textOrConfig.values) || (Array.isArray(values) ? values : []);
    const answer = await rpc("db.query", { id: this.id, sql: text, params: params.map(param), timeoutMs: this.pool.config.statement_timeout || 0 });
    const commands = verbs(text);
    const results = answer.results.map((set, i) => result(set, commands[i] ?? ""));
    const out = results.length === 1 ? results[0] : results.length ? results : result({ updateCount: 0 }, commands[0] ?? "");
    if (typeof callback === "function") callback(null, out);
    return out;
  }

  release(destroy) {
    void rpc("db.close", { id: this.id }).catch(() => undefined);
    this.pool.clients.delete(this);
    return destroy;
  }
}

export class Pool extends EventEmitter {
  constructor(config = {}) {
    super();
    this.config = config;
    this.clients = new Set();
    this.shared = null;
  }

  /** The pool's own connection for one-off queries (the IDE runs them one at a time on it). */
  sharedConnection() {
    this.shared ??= open(this.config).catch(error => {
      this.shared = null;
      throw error;
    });
    return this.shared;
  }

  async query(textOrConfig, values) {
    const connection = await this.sharedConnection();
    return new Client(this, connection).query(textOrConfig, values);
  }

  /** A connection of its own (the console), returned with release(). */
  async connect() {
    const client = new Client(this, await open(this.config));
    this.clients.add(client);
    return client;
  }

  async end() {
    const shared = this.shared;
    this.shared = null;
    if (shared) await shared.then(c => rpc("db.close", { id: c.id })).catch(() => undefined);
    await Promise.all([...this.clients].map(c => rpc("db.close", { id: c.id }).catch(() => undefined)));
    this.clients.clear();
  }
}

export const types = { getTypeParser: () => value => value, setTypeParser: () => undefined };
export default { Pool, Client, types };
