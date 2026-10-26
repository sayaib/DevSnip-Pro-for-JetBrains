/*
 * fetch() for DevSnip Pro's extension host (ClickHouse's HTTP interface in
 * the Database Client): sent by the IDE's HTTP client (its proxy settings),
 * with the body streamed back as it arrives, an AbortSignal that cancels it,
 * and the headers and text / json / body reader the adapter uses.
 */
import { onStream, rpc } from "./host-channel.js";

let nextId = 1;

function abortError() {
  try {
    return new DOMException("The operation was aborted.", "AbortError");
  } catch {
    const error = new Error("The operation was aborted.");
    error.name = "AbortError";
    return error;
  }
}

function headerObject(headers) {
  if (!headers) return {};
  if (typeof headers.forEach === "function" && !Array.isArray(headers)) {
    const out = {};
    headers.forEach((value, key) => { out[key] = String(value); });
    return out;
  }
  if (Array.isArray(headers)) return Object.fromEntries(headers.map(([k, v]) => [k, String(v)]));
  return Object.fromEntries(Object.entries(headers).map(([k, v]) => [k, String(v)]));
}

async function hostFetch(input, init = {}) {
  const url = typeof input === "string" ? input : String(input?.url ?? input);
  const signal = init.signal;
  if (signal?.aborted) throw abortError();
  const id = `fe${nextId++}`;
  const encoder = new TextEncoder();
  let controller;
  const body = new ReadableStream({
    start(c) { controller = c; },
    cancel() { void rpc("http.cancel", { id }).catch(() => undefined); }
  });
  onStream(id, (event, value) => {
    try {
      if (event === "data") controller.enqueue(encoder.encode(String(value ?? "")));
      else if (event === "end") controller.close();
      else if (event === "error") controller.error(new TypeError(String(value ?? "The response was interrupted.")));
    } catch {
      /* the reader was cancelled */
    }
  });
  const onAbort = () => {
    void rpc("http.cancel", { id }).catch(() => undefined);
    try { controller.error(abortError()); } catch { /* already closed */ }
  };
  signal?.addEventListener("abort", onAbort, { once: true });
  let head;
  try {
    head = await new Promise((resolve, reject) => {
      signal?.addEventListener("abort", () => reject(abortError()), { once: true });
      rpc("http.raw", {
        id,
        method: String(init.method || "GET").toUpperCase(),
        url,
        headers: headerObject(init.headers),
        body: init.body === undefined || init.body === null ? null : String(init.body),
        timeoutMs: 0,
        maxRedirects: 5,
        rejectUnauthorized: true,
        maxBytes: 0,
        stream: true
      }).then(resolve, error => reject(new TypeError(error instanceof Error ? error.message : String(error))));
    });
  } catch (error) {
    onStream(id, null);
    signal?.removeEventListener("abort", onAbort);
    throw error;
  }
  const headers = new Map(Object.entries(head.headers || {}).map(([k, v]) => [k.toLowerCase(), Array.isArray(v) ? v.join(", ") : String(v)]));
  const text = async () => {
    const reader = body.getReader();
    const decoder = new TextDecoder();
    let out = "";
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      out += decoder.decode(value, { stream: true });
    }
    return out + decoder.decode();
  };
  return {
    ok: head.status >= 200 && head.status < 300,
    status: head.status,
    statusText: head.statusText || "",
    url,
    headers: { get: name => headers.get(String(name).toLowerCase()) ?? null, has: name => headers.has(String(name).toLowerCase()), forEach: fn => headers.forEach((v, k) => fn(v, k)) },
    body,
    text,
    json: async () => JSON.parse(await text())
  };
}

globalThis.fetch = hostFetch;
