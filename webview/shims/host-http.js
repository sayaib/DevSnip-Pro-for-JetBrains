/*
 * Node's http and https request() for DevSnip Pro's extension host (the
 * Database Client's cloud connectors: DynamoDB, Cosmos DB, Spanner,
 * Firestore, BigQuery and their token endpoints): sent by the IDE's HTTP
 * client, so its proxy settings apply. Only what cloud-auth/http.ts uses:
 * request(url, options, onResponse), write / end / destroy, the timeout and
 * error events, an AbortSignal, rejectUnauthorized for local emulators, and
 * a response that emits data and end.
 */
import { Buffer } from "buffer";
import { rpc } from "./host-channel.js";

let nextId = 1;
const MAX_BODY = 64 * 1024 * 1024;

class Emitter {
  constructor() { this.listeners = new Map(); }
  on(event, listener) {
    if (!this.listeners.has(event)) this.listeners.set(event, []);
    this.listeners.get(event).push(listener);
    return this;
  }
  once(event, listener) {
    const wrapped = (...args) => { this.off(event, wrapped); listener(...args); };
    return this.on(event, wrapped);
  }
  off(event, listener) {
    const list = this.listeners.get(event);
    if (list) this.listeners.set(event, list.filter(l => l !== listener));
    return this;
  }
  emit(event, ...args) {
    const list = this.listeners.get(event);
    if (!list?.length) return false;
    for (const listener of [...list]) listener(...args);
    return true;
  }
}

class IncomingMessage extends Emitter {
  constructor(head) {
    super();
    this.statusCode = head.status;
    this.statusMessage = head.statusText || "";
    this.headers = Object.fromEntries(Object.entries(head.headers || {}).map(([k, v]) => [k.toLowerCase(), v]));
  }
}

function abortError() {
  const error = new Error("The operation was aborted.");
  error.name = "AbortError";
  error.code = "ABORT_ERR";
  return error;
}

class ClientRequest extends Emitter {
  constructor(url, options, onResponse) {
    super();
    this.id = `hr${nextId++}`;
    this.url = url;
    this.options = options;
    this.chunks = [];
    this.done = false;
    this.destroyed = false;
    if (onResponse) this.once("response", onResponse);
    const signal = options.signal;
    if (signal) {
      if (signal.aborted) queueMicrotask(() => this.destroy(abortError()));
      else signal.addEventListener("abort", () => this.destroy(abortError()), { once: true });
    }
  }

  setTimeout(ms, listener) {
    this.options.timeout = ms;
    if (listener) this.once("timeout", listener);
    return this;
  }

  write(chunk) {
    this.chunks.push(typeof chunk === "string" ? chunk : Buffer.from(chunk).toString("utf8"));
    return true;
  }

  end(chunk) {
    if (chunk !== undefined) this.write(chunk);
    if (this.done || this.destroyed) return this;
    this.done = true;
    const { options } = this;
    const headers = {};
    for (const [k, v] of Object.entries(options.headers || {})) {
      // The IDE's HTTP client sets these itself.
      if (v !== undefined && !/^(content-length|host|connection)$/i.test(k)) headers[k] = Array.isArray(v) ? v.join(", ") : String(v);
    }
    if (options.timeout) this.timer = setTimeout(() => { if (!this.destroyed) this.emit("timeout"); }, options.timeout);
    rpc("http.raw", {
      id: this.id,
      method: String(options.method || "GET").toUpperCase(),
      url: String(this.url),
      headers,
      body: this.chunks.length ? this.chunks.join("") : null,
      // The IDE stops too when the caller's timeout passes, and refuses bodies over 64 MB as cloud-auth/http.ts does.
      timeoutMs: options.timeout || 0,
      maxRedirects: 0,
      rejectUnauthorized: options.rejectUnauthorized !== false,
      maxBytes: MAX_BODY,
      stream: false
    }).then(head => {
      clearTimeout(this.timer);
      if (this.destroyed) return;
      const response = new IncomingMessage(head);
      this.emit("response", response);
      const body = head.body ?? "";
      if (body) response.emit("data", Buffer.from(body, "utf8"));
      response.emit("end");
    }, error => {
      clearTimeout(this.timer);
      if (this.destroyed) return;
      // The IDE's error keeps its code (ECONNREFUSED, ...), which friendlyError and the lost-connection check read.
      const failure = error instanceof Error ? error : new Error(String(error));
      this.destroyed = true;
      this.emit("error", failure);
    });
    return this;
  }

  destroy(error) {
    if (this.destroyed) return this;
    this.destroyed = true;
    clearTimeout(this.timer);
    if (this.done) void rpc("http.cancel", { id: this.id }).catch(() => undefined);
    if (error) queueMicrotask(() => this.emit("error", error));
    return this;
  }

  abort() { this.destroy(abortError()); }
}

export function request(url, options, onResponse) {
  if (typeof options === "function") { onResponse = options; options = {}; }
  return new ClientRequest(url, options || {}, onResponse);
}

export function get(url, options, onResponse) {
  const req = request(url, { ...(typeof options === "object" ? options : {}), method: "GET" }, typeof options === "function" ? options : onResponse);
  req.end();
  return req;
}

/** Only the options the axios shim reads (rejectUnauthorized). */
export class Agent {
  constructor(options) {
    this.options = options || {};
  }
}

export default { request, get, Agent };
