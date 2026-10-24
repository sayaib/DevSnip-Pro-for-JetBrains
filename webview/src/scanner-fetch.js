/*
 * fetch() for the scanners, sent through the IDE: its HTTP client uses the
 * IDE's proxy settings, and the page itself is not allowed to connect
 * anywhere (connect-src 'none'). Only what the extension's code needs:
 * method, headers, text body, AbortSignal; status, headers and text back.
 */
import { rpc } from "../shims/host-rpc.js";

let nextRequest = 1;

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

async function ideFetch(input, init = {}) {
  const url = typeof input === "string" ? input : String(input?.url ?? input);
  const signal = init.signal;
  if (signal?.aborted) throw abortError();
  const id = nextRequest++;
  const call = rpc("http.fetch", {
    id,
    url,
    method: String(init.method || "GET").toUpperCase(),
    headers: headerObject(init.headers),
    body: init.body === undefined || init.body === null ? null : String(init.body)
  });
  const response = await new Promise((resolve, reject) => {
    const onAbort = () => {
      void rpc("http.abort", { id }).catch(() => undefined);
      reject(abortError());
    };
    signal?.addEventListener("abort", onAbort, { once: true });
    call.then(resolve, error => reject(new TypeError(error instanceof Error ? error.message : String(error)))).finally(() => signal?.removeEventListener("abort", onAbort));
  });
  const headers = new Map(Object.entries(response.headers || {}).map(([k, v]) => [k.toLowerCase(), String(v)]));
  const text = String(response.text ?? "");
  return {
    ok: response.status >= 200 && response.status < 300,
    status: response.status,
    statusText: "",
    url,
    headers: { get: name => headers.get(String(name).toLowerCase()) ?? null, has: name => headers.has(String(name).toLowerCase()) },
    text: async () => text,
    json: async () => JSON.parse(text)
  };
}

globalThis.fetch = ideFetch;
