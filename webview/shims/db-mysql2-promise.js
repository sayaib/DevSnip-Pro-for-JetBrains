/*
 * `mysql2/promise` for the Database Client's MySQL / MariaDB adapter, on the
 * IDE's MariaDB JDBC driver (DbBridge.kt), with the adapter's options:
 * dates and big numbers as the server's text, JSON parsed, BLOBs as Buffers.
 */
import { EventEmitter, bytes, param, rowObjects, rpc, tagOf } from "./db-wire.js";

function convert(value) {
  switch (tagOf(value)) {
    case "$json": try { return JSON.parse(value.$json); } catch { return value.$json; }
    case "$bytes": return bytes(value);
    case "$big": return value.$big;
    case "$num": return value.$num;
    case "$date": return new Date(Number(value.$date)).toISOString().replace("T", " ").replace(/\.\d+Z$/, "");
    case "$ts": return value.$ts;
    default: return value;
  }
}

/** One answer as mysql2 gives it: [rows, fields] for a SELECT, [ResultSetHeader] for anything else, nested for CALL. */
function shape(answer) {
  const parts = answer.results.map(set => set.columns
    ? { rows: rowObjects(set, convert), fields: set.columns.map(c => ({ name: c.name, columnType: c.jdbcType, type: c.type, table: c.table ?? "" })) }
    : { rows: { affectedRows: set.updateCount ?? 0, insertId: set.insertId ?? 0, warningStatus: 0, fieldCount: 0, info: "", serverStatus: 2, changedRows: 0 }, fields: undefined });
  if (parts.length === 0) return [{ affectedRows: 0, insertId: 0, warningStatus: 0 }, undefined];
  if (parts.length === 1) return [parts[0].rows, parts[0].fields];
  return [parts.map(p => p.rows), parts.map(p => p.fields)];
}

function errorOf(error) {
  if (error && !error.sqlMessage) error.sqlMessage = error.message;
  return error;
}

function sqlOf(options) {
  return typeof options === "string" ? options : options.sql;
}

class PoolConnection extends EventEmitter {
  constructor(pool, connection) {
    super();
    this.pool = pool;
    this.id = connection.id;
    this.threadId = connection.threadId;
    const self = this;
    /** The callback-style core connection the adapter streams a SELECT through. */
    this.connection = {
      query(options) {
        const emitter = new EventEmitter();
        let paused = false;
        self.connection.pause = () => { paused = true; };
        rpc("db.query", { id: self.id, sql: sqlOf(options), params: [], timeoutMs: options.timeout || 0, maxRows: 5000 }).then(answer => {
          const set = answer.results.find(r => r.columns) ?? { columns: [], rows: [] };
          emitter.emit("fields", set.columns.map(c => ({ name: c.name, columnType: c.jdbcType })));
          for (const row of rowObjects(set, convert)) {
            if (paused) return;
            emitter.emit("result", row);
          }
          if (!paused) emitter.emit("end");
        }, error => emitter.emit("error", errorOf(error)));
        return emitter;
      },
      pause() {}
    };
  }

  async query(options, values) {
    const sql = sqlOf(options);
    const params = (typeof options === "object" && options.values) || (Array.isArray(values) ? values : []);
    try {
      const answer = await rpc("db.query", { id: this.id, sql, params: params.map(param), timeoutMs: (typeof options === "object" && options.timeout) || 0, generatedKeys: !/^\s*(select|with|show|describe|desc|explain|call|use|set)\b/i.test(sql) });
      return shape(answer);
    } catch (error) {
      throw errorOf(error);
    }
  }

  async execute(options, values) {
    return this.query(options, values);
  }

  release() {
    this.pool.release(this);
  }

  destroy() {
    this.pool.connections.delete(this);
    void rpc("db.close", { id: this.id }).catch(() => undefined);
  }
}

class Pool extends EventEmitter {
  constructor(config) {
    super();
    this.config = config;
    this.connections = new Set();
    this.idle = [];
  }

  async open() {
    // The adapter's "connection" handlers run their SQL (SET SESSION TRANSACTION READ ONLY) on each new session.
    const init = [];
    this.emit("connection", { query: sql => { init.push(String(sql)); } });
    const ssl = this.config.ssl;
    try {
      return await rpc("db.connect", {
        kind: "mariadb",
        host: this.config.host || "localhost",
        port: Number(this.config.port) || 3306,
        user: this.config.user || "",
        password: this.config.password || "",
        database: this.config.database || "",
        ssl: ssl ? { rejectUnauthorized: ssl.rejectUnauthorized !== false } : null,
        connectTimeoutMs: this.config.connectTimeout || 10000,
        init
      });
    } catch (error) {
      throw errorOf(error);
    }
  }

  async getConnection() {
    const connection = this.idle.pop() ?? new PoolConnection(this, await this.open());
    this.connections.add(connection);
    return connection;
  }

  release(connection) {
    if (this.connections.has(connection)) this.idle.push(connection);
  }

  async query(options, values) {
    const connection = await this.getConnection();
    try {
      return await connection.query(options, values);
    } finally {
      connection.release();
    }
  }

  async execute(options, values) {
    return this.query(options, values);
  }

  async end() {
    const all = new Set([...this.connections, ...this.idle]);
    this.connections.clear();
    this.idle = [];
    await Promise.all([...all].map(c => rpc("db.close", { id: c.id }).catch(() => undefined)));
  }
}

export function createPool(config) {
  return new Pool(config);
}

export default { createPool };
