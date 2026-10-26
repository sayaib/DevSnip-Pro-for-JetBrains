/*
 * The extension host's line to the IDE (webview/src/host-runtime.js runs in
 * a hidden page of the IDE's embedded browser, one per project):
 *
 *  - rpc(method, params): an asynchronous request, answered by EngineHost.kt;
 *  - syncCall(method, params): a synchronous request, for the few Node calls
 *    the extension makes synchronously (fs.readFileSync, fs.statSync, ...);
 *  - toHost(message): a one-way message (panels, command results);
 *  - onHostEvent(type, listener): messages the IDE pushes ("__exec",
 *    "__panelMessage", "__process", ...).
 *
 * In the IDE, window.__devsnipQuery and window.__devsnipSyncQuery are set by
 * the page; tests set globalThis.__devsnipToHost and __devsnipSync instead.
 */
const listeners = new Map();
const pending = new Map();
let nextRpc = 1;

export function host() {
  return globalThis.__devsnipHost || {};
}

export function toHost(message) {
  try {
    if (typeof globalThis.__devsnipToHost === "function") globalThis.__devsnipToHost(message);
    else globalThis.__devsnipQuery(JSON.stringify(message));
  } catch (error) {
    console.error("DevSnip Pro: could not reach the IDE.", error);
  }
}

export function rpc(method, params) {
  const id = nextRpc++;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    toHost({ type: "__rpc", id, method, params: params || {} });
  });
}

/** A synchronous request: the IDE answers before this returns. Throws the IDE's error. */
export function syncCall(method, params) {
  let answer;
  if (typeof globalThis.__devsnipSync === "function") {
    answer = globalThis.__devsnipSync(method, params || {});
  } else if (host().syncUrl) {
    // A synchronous request to the IDE's local server (EngineSyncServer.kt); the page waits for the answer.
    const request = new XMLHttpRequest();
    request.open("POST", host().syncUrl, false);
    request.setRequestHeader("Content-Type", "text/plain;charset=UTF-8");
    try {
      request.send(JSON.stringify({ method, params: params || {} }));
      answer = request.status === 200 ? JSON.parse(request.responseText) : { error: `The IDE refused the request (${request.status}).` };
    } catch (error) {
      answer = { error: `The IDE did not answer (${error instanceof Error ? error.message : String(error)}).` };
    }
  } else {
    // The IDE answers prompt() itself (see DevSnipEngine.kt); the page waits for it.
    const raw = globalThis.prompt("__devsnip_sync__", JSON.stringify({ method, params: params || {} }));
    answer = raw === null || raw === undefined ? { error: "The IDE did not answer." } : JSON.parse(raw);
  }
  if (answer && answer.error) {
    const error = new Error(answer.error);
    if (answer.code) error.code = answer.code;
    throw error;
  }
  return answer ? answer.result : undefined;
}

/** Subscribes to one kind of IDE message. */
export function onHostEvent(type, listener) {
  if (!listeners.has(type)) listeners.set(type, new Set());
  listeners.get(type).add(listener);
  return { dispose: () => listeners.get(type)?.delete(listener) };
}

const streams = new Map();

/** The REST services' streamed HTTP responses (shims/axios.js) arrive as "__stream" events. */
export function onStream(id, listener) {
  if (listener) streams.set(id, listener);
  else streams.delete(id);
}

/** Every message from the IDE comes through here. */
export function receive(message) {
  if (!message || typeof message !== "object") return;
  if (message.type === "__rpcResult") {
    const call = pending.get(message.id);
    pending.delete(message.id);
    if (!call) return;
    if (message.error !== undefined && message.error !== null) call.reject(Object.assign(new Error(message.error), message.errorData || {}));
    else call.resolve(message.result);
    return;
  }
  if (message.type === "__stream") {
    const listener = streams.get(message.id);
    if (listener) listener(message.event, message.value);
    if (message.event === "end" || message.event === "error") streams.delete(message.id);
    return;
  }
  for (const listener of [...(listeners.get(message.type) ?? [])]) {
    try {
      listener(message);
    } catch (error) {
      console.error(`DevSnip Pro: handling ${message.type} failed.`, error);
    }
  }
}

globalThis.__devsnipReceive = receive;

const isWindows = () => host().platform === "win32";

/** One spelling of a file system path for comparisons: forward slashes, no slash before a drive letter. */
export function canonicalPath(p) {
  let out = String(p).replace(/\\/g, "/").replace(/^\/(?=[A-Za-z]:)/, "");
  if (out.length > 1) out = out.replace(/\/+$/, "");
  return isWindows() ? out.toLowerCase() : out;
}

/** Bytes to base64 and back, for files crossing to the IDE. */
export function toBase64(bytes) {
  let binary = "";
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
  return btoa(binary);
}

export function fromBase64(text) {
  const binary = atob(text || "");
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
  return out;
}
