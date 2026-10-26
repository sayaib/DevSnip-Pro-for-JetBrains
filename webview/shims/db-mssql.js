/*
 * `mssql` for the Database Client's SQL Server adapter, on the IDE's
 * Microsoft JDBC driver (DbBridge.kt). Parameters @p1..@pN are sent with
 * sp_executesql and typed from their values, as tedious types them; results
 * come back as tedious gives them (bigint as text, decimals as numbers,
 * dates as Date).
 */
import { EventEmitter, bytes, param, rowObjects, rpc, tagOf } from "./db-wire.js";

function convert(value) {
  switch (tagOf(value)) {
    case "$json": return value.$json;
    case "$bytes": return bytes(value);
    case "$big": return value.$big;
    case "$num": return Number(value.$num);
    case "$date": return new Date(Number(value.$date));
    case "$ts": return value.$ts;
    default: return value;
  }
}

function recordset(set) {
  const rows = rowObjects(set, convert);
  const columns = {};
  set.columns.forEach((c, i) => { columns[c.name] = { index: i, name: c.name, type: c.type }; });
  Object.defineProperty(rows, "columns", { value: columns, enumerable: false });
  return rows;
}

class Request extends EventEmitter {
  constructor(pool) {
    super();
    this.pool = pool;
    this.params = [];
    this.stream = false;
  }

  input(name, typeOrValue, value) {
    const index = Number(/^p(\d+)$/.exec(name)?.[1] ?? this.params.length + 1) - 1;
    this.params[index] = arguments.length === 3 ? value : typeOrValue;
    return this;
  }

  async query(sql) {
    const connection = await this.pool.connection();
    let answer;
    try {
      answer = await rpc("db.query", { id: connection.id, sql: String(sql), params: this.params.map(param), timeoutMs: this.pool.config.requestTimeout || 0, maxRows: this.stream ? 5000 : 0 });
    } catch (error) {
      if (this.stream) {
        this.emit("error", error);
        return undefined;
      }
      throw error;
    }
    const sets = answer.results.filter(r => r.columns).map(recordset);
    const rowsAffected = answer.results.filter(r => !r.columns).map(r => r.updateCount ?? 0);
    const result = { recordset: sets[0], recordsets: sets, rowsAffected, output: {} };
    if (this.stream) {
      for (const set of sets) {
        this.emit("recordset", set.columns);
        for (const row of set) {
          if (this.cancelled) return result;
          this.emit("row", row);
        }
      }
      this.emit("done", { rowsAffected });
    }
    return result;
  }

  cancel() {
    this.cancelled = true;
    this.pool.connection().then(c => rpc("db.cancel", { id: c.id })).catch(() => undefined);
  }
}

export class ConnectionPool extends EventEmitter {
  constructor(config) {
    super();
    this.config = config;
    this.opened = null;
  }

  connection() {
    if (!this.opened) return Promise.reject(new Error("The connection is not open."));
    return this.opened;
  }

  connect() {
    const c = this.config;
    const options = c.options || {};
    this.opened ??= rpc("db.connect", {
      kind: "sqlserver",
      host: c.server || "localhost",
      port: c.port ?? null,
      instanceName: options.instanceName || "",
      user: c.user || "",
      password: c.password || "",
      database: c.database || "",
      ssl: options.encrypt ? {} : null,
      trustServerCertificate: options.trustServerCertificate !== false,
      applicationName: options.appName || "DevSnip Pro",
      connectTimeoutMs: c.connectionTimeout || 10000
    }).catch(error => {
      this.opened = null;
      throw error;
    });
    return this.opened.then(() => this);
  }

  request() {
    return new Request(this);
  }

  async close() {
    const opened = this.opened;
    this.opened = null;
    if (opened) await opened.then(c => rpc("db.close", { id: c.id })).catch(() => undefined);
  }
}

export default { ConnectionPool };
