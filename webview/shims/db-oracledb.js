/*
 * `oracledb` (Thin mode) for the Database Client's Oracle adapter, on the
 * IDE's Oracle JDBC driver (DbBridge.kt). The adapter's fetchTypeHandler
 * (NUMBER as exact text, dates as wall-clock text, intervals as text) is
 * applied to every value, as node-oracledb applies it.
 */
import { bytes, param, rpc, tagOf } from "./db-wire.js";

const DB_TYPE = name => ({ name, toString: () => name });
const types = {
  NUMBER: DB_TYPE("DB_TYPE_NUMBER"), DATE: DB_TYPE("DB_TYPE_DATE"), TIMESTAMP: DB_TYPE("DB_TYPE_TIMESTAMP"),
  TIMESTAMP_TZ: DB_TYPE("DB_TYPE_TIMESTAMP_TZ"), TIMESTAMP_LTZ: DB_TYPE("DB_TYPE_TIMESTAMP_LTZ"), CLOB: DB_TYPE("DB_TYPE_CLOB"),
  NCLOB: DB_TYPE("DB_TYPE_NCLOB"), BLOB: DB_TYPE("DB_TYPE_BLOB"), INTERVAL_DS: DB_TYPE("DB_TYPE_INTERVAL_DS"), INTERVAL_YM: DB_TYPE("DB_TYPE_INTERVAL_YM"),
  VARCHAR: DB_TYPE("DB_TYPE_VARCHAR"), RAW: DB_TYPE("DB_TYPE_RAW"), OTHER: DB_TYPE("DB_TYPE_OTHER")
};

/** node-oracledb's DB type for a column type name. */
function dbType(name) {
  const t = String(name || "").toUpperCase();
  if (t === "NUMBER" || t === "FLOAT" || t === "INTEGER") return types.NUMBER;
  if (t === "DATE") return types.DATE;
  if (t.startsWith("TIMESTAMP") && t.includes("LOCAL")) return types.TIMESTAMP_LTZ;
  if (t.startsWith("TIMESTAMP") && t.includes("TIME ZONE")) return types.TIMESTAMP_TZ;
  if (t.startsWith("TIMESTAMP")) return types.TIMESTAMP;
  if (t === "CLOB") return types.CLOB;
  if (t === "NCLOB") return types.NCLOB;
  if (t === "BLOB") return types.BLOB;
  if (t.startsWith("INTERVAL DAY")) return types.INTERVAL_DS;
  if (t.startsWith("INTERVAL YEAR")) return types.INTERVAL_YM;
  if (t === "RAW" || t === "LONG RAW") return types.RAW;
  if (t.includes("CHAR")) return types.VARCHAR;
  return types.OTHER;
}

/** Wall-clock text (YYYY-MM-DD HH:MM:SS.fff) as the Date Thin mode builds from it. */
function localDate(text) {
  const m = /^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,3}))?/.exec(text);
  if (!m) return new Date(NaN);
  return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]), Number(m[4]), Number(m[5]), Number(m[6]), Number((m[7] ?? "0").padEnd(3, "0")));
}

/** Oracle's interval text ("+01 02:03:04.500000", "+02-03") as node-oracledb's interval objects. */
function interval(text, ds) {
  const sign = text.trim().startsWith("-") ? -1 : 1;
  if (ds) {
    const m = /([+-]?)(\d+) (\d+):(\d+):(\d+)(?:\.(\d+))?/.exec(text) ?? [];
    return { days: sign * Number(m[2] ?? 0), hours: sign * Number(m[3] ?? 0), minutes: sign * Number(m[4] ?? 0), seconds: sign * Number(m[5] ?? 0), fseconds: sign * Number(((m[6] ?? "") + "000000000").slice(0, 9)) };
  }
  const m = /([+-]?)(\d+)-(\d+)/.exec(text) ?? [];
  return { years: sign * Number(m[2] ?? 0), months: sign * Number(m[3] ?? 0) };
}

function raw(value, type) {
  switch (tagOf(value)) {
    case "$num": return value.$num;
    case "$big": return value.$big;
    case "$bytes": return bytes(value);
    case "$ts": return localDate(value.$ts);
    case "$date": return new Date(Number(value.$date));
    case "$interval": return interval(value.$interval, type === types.INTERVAL_DS);
    case "$json": try { return JSON.parse(value.$json); } catch { return value.$json; }
    default: return value;
  }
}

/** The value node-oracledb would return without a handler (numbers as numbers). */
function plain(value, type) {
  const v = raw(value, type);
  if (type === types.NUMBER && typeof v === "string") return Number(v);
  return v;
}

const OUT_FORMAT_ARRAY = 4001;
const OUT_FORMAT_OBJECT = 4002;
const BIND_IN = 3001;
const BIND_OUT = 3003;
const STRING = 2001;
const NUMBER_TYPE = 2010;
const BUFFER = 2006;

class Connection {
  constructor(info) {
    this.id = info.id;
    this.callTimeout = 0;
    const version = String(info.version ?? "");
    this.oracleServerVersionString = /Version (\S+)/.exec(version)?.[1] ?? /Release (\S+)/.exec(version)?.[1] ?? version;
    this.autoCommitState = true;
  }

  async execute(sql, binds = [], options = {}) {
    // Named OUT binds (DBMS_OUTPUT.GET_LINE): a call with OUT parameters.
    if (binds && !Array.isArray(binds) && typeof binds === "object") {
      const wire = {};
      for (const [name, bind] of Object.entries(binds)) {
        wire[name] = bind && typeof bind === "object" && bind.dir === BIND_OUT
          ? { out: true, type: bind.type === NUMBER_TYPE ? "number" : "string" }
          : { value: param(bind && typeof bind === "object" && "val" in bind ? bind.val : bind) };
      }
      const answer = await rpc("db.call", { id: this.id, sql: String(sql), binds: wire });
      const outBinds = {};
      for (const [name, value] of Object.entries(answer.outBinds ?? {})) outBinds[name] = binds[name]?.type === NUMBER_TYPE && value !== null ? Number(value) : value;
      return { outBinds };
    }
    const autoCommit = options.autoCommit !== undefined ? Boolean(options.autoCommit) : false;
    const answer = await rpc("db.query", {
      id: this.id,
      sql: String(sql),
      params: (binds ?? []).map(param),
      timeoutMs: this.callTimeout || 0,
      maxRows: options.maxRows || 0,
      autoCommit
    });
    const set = answer.results.find(r => r.columns);
    if (!set) {
      const update = answer.results.find(r => !r.columns);
      return { rowsAffected: update ? update.updateCount ?? 0 : undefined };
    }
    const metaData = set.columns.map(c => ({ name: c.name, dbType: dbType(c.type), dbTypeName: c.type, fetchType: dbType(c.type) }));
    const handlers = metaData.map(meta => (typeof options.fetchTypeHandler === "function" ? options.fetchTypeHandler(meta) : undefined));
    const objects = options.outFormat === OUT_FORMAT_OBJECT;
    const rows = set.rows.map(values => {
      const converted = values.map((value, i) => {
        if (value === null) return null;
        const handler = handlers[i];
        if (!handler) return plain(value, metaData[i].dbType);
        const fetched = handler.type === STRING && tagOf(value) === "$num" ? value.$num : handler.type === STRING && typeof value !== "object" ? String(value) : raw(value, metaData[i].dbType);
        return handler.converter ? handler.converter(fetched) : fetched;
      });
      if (!objects) return converted;
      const row = {};
      metaData.forEach((meta, i) => { row[meta.name] = converted[i]; });
      return row;
    });
    return { rows, metaData };
  }

  async commit() {
    await rpc("db.query", { id: this.id, sql: "COMMIT", params: [] });
  }

  async rollback() {
    await rpc("db.query", { id: this.id, sql: "ROLLBACK", params: [] });
  }

  async break() {
    await rpc("db.cancel", { id: this.id });
  }

  async close() {
    await rpc("db.close", { id: this.id });
  }
}

class Pool {
  constructor(attrs) {
    this.attrs = attrs;
  }

  async getConnection() {
    const info = await rpc("db.connect", {
      kind: "oracle",
      connectString: this.attrs.connectString || this.attrs.connectionString || "",
      user: this.attrs.user || "",
      password: this.attrs.password || "",
      connectTimeoutMs: (this.attrs.connectTimeout || 10) * 1000
    });
    const connection = new Connection(info);
    if (typeof this.attrs.sessionCallback === "function") {
      await new Promise((resolve, reject) => this.attrs.sessionCallback(connection, "", error => (error ? reject(error) : resolve())));
    }
    return connection;
  }

  async close() {}
}

/* The adapter loads the driver with require("oracledb"): its functions and constants are the module's own exports. */
export async function createPool(attrs) { return new Pool(attrs); }
export { OUT_FORMAT_ARRAY, OUT_FORMAT_OBJECT, BIND_IN, BIND_OUT, STRING, NUMBER_TYPE as NUMBER, BUFFER };
export const DB_TYPE_NUMBER = types.NUMBER;
export const DB_TYPE_DATE = types.DATE;
export const DB_TYPE_TIMESTAMP = types.TIMESTAMP;
export const DB_TYPE_TIMESTAMP_TZ = types.TIMESTAMP_TZ;
export const DB_TYPE_TIMESTAMP_LTZ = types.TIMESTAMP_LTZ;
export const DB_TYPE_CLOB = types.CLOB;
export const DB_TYPE_NCLOB = types.NCLOB;
export const DB_TYPE_BLOB = types.BLOB;
export const DB_TYPE_INTERVAL_DS = types.INTERVAL_DS;
export const DB_TYPE_INTERVAL_YM = types.INTERVAL_YM;
export const DB_TYPE_VARCHAR = types.VARCHAR;
export const DB_TYPE_RAW = types.RAW;
