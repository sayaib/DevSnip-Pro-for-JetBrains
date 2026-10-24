/*
 * axios for the REST API Client page in the embedded browser. The page cannot
 * make cross-origin requests (and must not: CORS and the CSP would change
 * what the user sees), so every request goes to the IDE, which sends it with
 * its HTTP client and returns the response. The subset implemented is what
 * the extension's services use: axios(config) / axios.get / post / ...,
 * validateStatus, timeout, maxRedirects, responseType "json" | "text" |
 * "stream", transformResponse, cancel tokens and an https.Agent with
 * rejectUnauthorized: false.
 */
import { Buffer } from "buffer";

function host() {
  const bridge = globalThis.__devsnipApiHost;
  if (!bridge) throw new Error("The IDE connection is not available.");
  return bridge;
}

let nextId = 1;

class Cancel {
  constructor(message) {
    this.message = message;
    this.__CANCEL__ = true;
  }
}

export function isCancel(value) {
  return Boolean(value && value.__CANCEL__);
}

export function CancelToken(executor) {
  let resolve;
  this.promise = new Promise(r => { resolve = r; });
  executor(message => {
    if (this.reason) return;
    this.reason = new Cancel(message || "canceled");
    resolve(this.reason);
  });
}
CancelToken.source = function () {
  let cancel;
  const token = new CancelToken(c => { cancel = c; });
  return { token, cancel };
};

class AxiosError extends Error {
  constructor(message, code, config, response) {
    super(message);
    this.name = "AxiosError";
    this.code = code;
    this.config = config;
    this.response = response;
    this.isAxiosError = true;
  }
}

export function isAxiosError(value) {
  return Boolean(value && value.isAxiosError);
}

function plainHeaders(headers) {
  const out = {};
  if (!headers || typeof headers !== "object") return out;
  for (const [name, value] of Object.entries(headers)) {
    if (value === undefined || value === null || typeof value === "object") continue;
    out[name] = String(value);
  }
  return out;
}

function hasHeader(headers, name) {
  return Object.keys(headers).some(key => key.toLowerCase() === name);
}

/** axios's default request transform: objects are sent as JSON, forms as urlencoded. */
function serialize(data, headers) {
  if (data === undefined || data === null) return undefined;
  if (typeof data === "string") return data;
  if (typeof URLSearchParams !== "undefined" && data instanceof URLSearchParams) {
    if (!hasHeader(headers, "content-type")) headers["Content-Type"] = "application/x-www-form-urlencoded;charset=utf-8";
    return data.toString();
  }
  if (data instanceof ArrayBuffer || ArrayBuffer.isView(data)) return Buffer.from(data.buffer ? data : new Uint8Array(data)).toString("utf8");
  if (!hasHeader(headers, "content-type")) headers["Content-Type"] = "application/json";
  return JSON.stringify(data);
}

function buildUrl(config) {
  let url = String(config.url || "");
  if (config.baseURL && !/^[a-z][a-z0-9+.-]*:/i.test(url)) url = config.baseURL.replace(/\/+$/, "") + "/" + url.replace(/^\/+/, "");
  if (config.params && typeof config.params === "object") {
    const parsed = new URL(url);
    for (const [key, value] of Object.entries(config.params)) {
      if (value !== undefined && value !== null) parsed.searchParams.set(key, String(value));
    }
    url = parsed.toString();
  }
  return url;
}

/** Node's stream for responseType "stream": data / end / error, fed by the IDE as the body arrives. */
class ResponseStream {
  constructor() {
    this.listeners = { data: [], end: [], error: [] };
    this.queue = [];
  }
  on(event, listener) {
    (this.listeners[event] = this.listeners[event] || []).push(listener);
    if (event === "data" || event === "end" || event === "error") Promise.resolve().then(() => this.flush());
    return this;
  }
  once(event, listener) {
    const wrapped = (...args) => { this.off(event, wrapped); listener(...args); };
    return this.on(event, wrapped);
  }
  off(event, listener) {
    this.listeners[event] = (this.listeners[event] || []).filter(l => l !== listener);
    return this;
  }
  push(event, value) {
    this.queue.push([event, value]);
    this.flush();
  }
  flush() {
    // Events wait until someone listens for data, like a paused Node stream.
    if (!this.listeners.data.length) return;
    while (this.queue.length) {
      const [event, value] = this.queue.shift();
      for (const listener of [...(this.listeners[event] || [])]) listener(value);
    }
  }
  destroy() {
    this.queue = [];
  }
}

function transform(config, text) {
  const transforms = config.transformResponse;
  if (Array.isArray(transforms) && transforms.length) return transforms.reduce((value, fn) => fn(value), text);
  if (typeof transforms === "function") return transforms(text);
  if (config.responseType === "text") return text;
  if (config.responseType === "arraybuffer") return Buffer.from(text, "utf8");
  // axios's silent JSON parsing: JSON when it parses, the text otherwise.
  if (typeof text === "string" && text.trim()) {
    try {
      return JSON.parse(text);
    } catch {
      return text;
    }
  }
  return text;
}

async function request(input, extra) {
  const config = typeof input === "string" ? { ...(extra || {}), url: input } : { ...(input || {}) };
  const headers = plainHeaders(config.headers);
  const method = String(config.method || "GET").toUpperCase();
  const body = method === "GET" || method === "HEAD" ? undefined : serialize(config.data, headers);
  const id = `ax${nextId++}`;
  const stream = config.responseType === "stream";
  const agent = config.httpsAgent;
  const params = {
    id,
    method,
    url: buildUrl(config),
    headers,
    body,
    timeoutMs: Number(config.timeout) > 0 ? Number(config.timeout) : 0,
    maxRedirects: config.maxRedirects === undefined ? 5 : Number(config.maxRedirects),
    rejectUnauthorized: !(agent && agent.options && agent.options.rejectUnauthorized === false),
    maxBytes: Number(config.maxContentLength) > 0 ? Number(config.maxContentLength) : 0,
    stream
  };

  const bridge = host();
  let cancelled = null;
  const cancel = reason => {
    if (cancelled) return;
    cancelled = reason instanceof Cancel ? reason : new Cancel(String(reason?.message || reason || "canceled"));
    bridge.rpc("http.cancel", { id }).catch(() => undefined);
  };
  if (config.cancelToken) {
    if (config.cancelToken.reason) throw config.cancelToken.reason;
    config.cancelToken.promise.then(cancel);
  }
  if (config.signal) {
    if (config.signal.aborted) throw new Cancel("canceled");
    config.signal.addEventListener("abort", () => cancel(new Cancel("canceled")));
  }

  const body$ = stream ? new ResponseStream() : null;
  if (body$) bridge.onStream(id, (event, value) => body$.push(event, event === "data" ? Buffer.from(value, "utf8") : value));

  let raw;
  try {
    raw = await bridge.rpc("http.raw", params);
  } catch (error) {
    if (body$) bridge.onStream(id, null);
    if (cancelled) throw cancelled;
    throw new AxiosError(error.message, error.code, config);
  }
  if (cancelled) throw cancelled;

  const response = {
    status: raw.status,
    statusText: raw.statusText || "",
    headers: raw.headers || {},
    config,
    request: {},
    data: body$ || transform(config, raw.body ?? "")
  };
  const validate = typeof config.validateStatus === "function" ? config.validateStatus : status => status >= 200 && status < 300;
  if (!validate(response.status)) {
    const code = response.status >= 500 ? "ERR_BAD_RESPONSE" : "ERR_BAD_REQUEST";
    throw new AxiosError(`Request failed with status code ${response.status}`, code, config, response);
  }
  return response;
}

function axios(config, extra) {
  return request(config, extra);
}
axios.request = config => request(config);
for (const method of ["get", "delete", "head", "options"]) {
  axios[method] = (url, config) => request({ ...(config || {}), method, url });
}
for (const method of ["post", "put", "patch"]) {
  axios[method] = (url, data, config) => request({ ...(config || {}), method, url, data });
}
axios.create = () => axios;
axios.isCancel = isCancel;
axios.isAxiosError = isAxiosError;
axios.CancelToken = CancelToken;
axios.Cancel = Cancel;
axios.AxiosError = AxiosError;
axios.defaults = { headers: { common: {} } };

export { AxiosError, Cancel };
export default axios;
