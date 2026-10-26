/*
 * Shared by the Database Client's driver stand-ins (db-*.js): values from the
 * IDE's drivers (DbBridge.kt) arrive tagged, and each stand-in turns them into
 * what its Node driver returns.
 */
import { Buffer } from "buffer";
import { rpc } from "./host-channel.js";

export { rpc };

export class EventEmitter {
  constructor() { this.handlers = {}; }
  on(event, handler) { (this.handlers[event] ??= []).push(handler); return this; }
  once(event, handler) {
    const wrapped = (...args) => { this.off(event, wrapped); handler(...args); };
    return this.on(event, wrapped);
  }
  off(event, handler) { this.handlers[event] = (this.handlers[event] ?? []).filter(h => h !== handler); return this; }
  removeListener(event, handler) { return this.off(event, handler); }
  removeAllListeners(event) { if (event) delete this.handlers[event]; else this.handlers = {}; return this; }
  emit(event, ...args) {
    const handlers = [...(this.handlers[event] ?? [])];
    for (const handler of handlers) handler(...args);
    return handlers.length > 0;
  }
}

/** The tag of a wire value ("$big", "$num", ...), or null for a plain value. */
export function tagOf(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const keys = Object.keys(value);
  return keys.length === 1 && keys[0].startsWith("$") ? keys[0] : null;
}

/** Bytes as the IDE sends them. */
export function bytes(value) {
  return Buffer.from(value.$bytes, "base64");
}

/** A parameter for the IDE: Buffers and Uint8Arrays as base64, Dates as ISO text, bigint as text. */
export function param(value) {
  if (value === undefined) return null;
  if (value instanceof Uint8Array) return { $bytes: Buffer.from(value).toString("base64") };
  if (value instanceof Date) return value.toISOString();
  if (typeof value === "bigint") return value.toString();
  return value;
}

/** Rows as objects keyed by column name (a later duplicate name wins, as in the Node drivers). */
export function rowObjects(set, convert) {
  const names = set.columns.map(c => c.name);
  return set.rows.map(values => {
    const row = {};
    names.forEach((name, i) => { row[name] = convert(values[i], set.columns[i]); });
    return row;
  });
}

/** The verb of each statement in a script, for result labels (INSERT, UPDATE, ...). */
export function verbs(text) {
  const out = [];
  let current = "";
  let quote = null;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quote) {
      current += c;
      if (c === quote) quote = null;
    } else if (c === "'" || c === '"') {
      quote = c;
      current += c;
    } else if (c === "-" && text[i + 1] === "-") {
      while (i < text.length && text[i] !== "\n") i++;
    } else if (c === ";") {
      if (current.trim()) out.push(current);
      current = "";
    } else {
      current += c;
    }
  }
  if (current.trim()) out.push(current);
  return out.map(s => (/^\s*\(?\s*([A-Za-z]+)/.exec(s)?.[1] ?? "").toUpperCase());
}
