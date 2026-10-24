/*
 * The scanner runtime's line to the IDE: request/response calls (RPC) and the
 * events the IDE pushes (process output, visibility, settings). bridge.js
 * provides window.__devsnipRpc and forwards "__*" messages to onHostEvent.
 */
const listeners = new Map();

export function host() {
  return globalThis.__devsnipHost || {};
}

export function rpc(method, params) {
  if (typeof globalThis.__devsnipRpc !== "function") return Promise.reject(new Error("The IDE is not reachable."));
  return globalThis.__devsnipRpc(method, params || {});
}

/** Subscribes to one kind of IDE event ("__process", "__visibility", "__settings"). */
export function onHostEvent(type, listener) {
  if (!listeners.has(type)) listeners.set(type, new Set());
  listeners.get(type).add(listener);
  return { dispose: () => listeners.get(type)?.delete(listener) };
}

globalThis.__devsnipInternal = message => {
  for (const listener of listeners.get(message?.type) ?? []) {
    try {
      listener(message);
    } catch (error) {
      console.error("DevSnip Pro: event handler failed.", error);
    }
  }
};

const isWindows = () => host().platform === "win32";

/** One spelling of a file system path for comparisons: forward slashes, no slash before a drive letter. */
export function canonicalPath(p) {
  let out = String(p).replace(/\\/g, "/").replace(/^\/(?=[A-Za-z]:)/, "");
  if (out.length > 1) out = out.replace(/\/+$/, "");
  return isWindows() ? out.toLowerCase() : out;
}

/*
 * Facts the extension's code reads synchronously (fs.existsSync for build tool
 * wrappers and executables on PATH, fs.readFileSync for ~/.m2/settings.xml).
 * The IDE sends them with the page and refreshes them with every file search.
 */
const facts = { exists: new Set(), texts: new Map() };

export function addFacts(update) {
  if (!update) return;
  for (const p of update.exists ?? []) facts.exists.add(canonicalPath(p));
  for (const [p, text] of Object.entries(update.texts ?? {})) facts.texts.set(canonicalPath(p), text);
}

export function factExists(p) {
  return facts.exists.has(canonicalPath(p));
}

export function factText(p) {
  return facts.texts.get(canonicalPath(p));
}

addFacts(host().facts);
