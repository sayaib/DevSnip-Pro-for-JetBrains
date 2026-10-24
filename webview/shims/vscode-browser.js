/*
 * The part of the VS Code API that DevSnip Pro's OSV and SCA scanners use,
 * for their own code running in the JetBrains IDE's embedded browser. Every
 * call that needs the IDE (files, dialogs, editors, terminal, clipboard,
 * secrets, progress, logs) goes to it as an RPC; ScannerHost.kt answers.
 *
 * The page is the scanner's one webview panel: what the extension posts to
 * the panel is delivered to the page, and what the page posts reaches the
 * extension's listeners.
 */
import posix from "path-browserify";
import { addFacts, canonicalPath, host, onHostEvent, rpc } from "./host-rpc.js";

const noop = () => undefined;

export class Disposable {
  constructor(fn) { this.fn = fn; }
  static from(...items) { return new Disposable(() => items.forEach(i => i?.dispose?.())); }
  dispose() { this.fn?.(); this.fn = undefined; }
}

export class EventEmitter {
  constructor() {
    this.listeners = new Set();
    this.event = listener => {
      this.listeners.add(listener);
      return new Disposable(() => this.listeners.delete(listener));
    };
  }
  fire(value) { for (const listener of [...this.listeners]) listener(value); }
  dispose() { this.listeners.clear(); }
}

export class CancellationError extends Error {
  constructor() { super("Canceled"); this.name = "Canceled"; }
}

export class CancellationTokenSource {
  constructor() {
    const emitter = new EventEmitter();
    this.emitter = emitter;
    this.token = { isCancellationRequested: false, onCancellationRequested: emitter.event };
  }
  cancel() {
    if (this.token.isCancellationRequested) return;
    this.token.isCancellationRequested = true;
    this.emitter.fire(undefined);
  }
  dispose() { this.emitter.dispose(); }
}

// ---------------------------------------------------------------- Uri

/** file:// paths as VS Code keeps them: POSIX, with Windows drives as /C:/…. */
function toUriPath(p) {
  let out = String(p).replace(/\\/g, "/");
  if (/^[A-Za-z]:/.test(out)) out = "/" + out;
  return out;
}

export class Uri {
  constructor(scheme, path, query = "", fragment = "") {
    this.scheme = scheme;
    this.path = path;
    this.query = query;
    this.fragment = fragment;
    this.authority = "";
  }
  get fsPath() {
    return /^\/[A-Za-z]:/.test(this.path) ? this.path.slice(1) : this.path;
  }
  with(change) {
    return new Uri(change.scheme ?? this.scheme, change.path ?? this.path, change.query ?? this.query, change.fragment ?? this.fragment);
  }
  toString() {
    if (this.scheme === "file") return "file://" + encodeURI(this.path);
    if (this.raw) return this.raw;
    return `${this.scheme}:${this.path}${this.query ? `?${this.query}` : ""}${this.fragment ? `#${this.fragment}` : ""}`;
  }
  toJSON() { return { scheme: this.scheme, path: this.path, query: this.query }; }
  static file(p) { return new Uri("file", toUriPath(p)); }
  static joinPath(base, ...segments) { return base.with({ path: posix.join(base.path, ...segments) }); }
  static from(parts) { return new Uri(parts.scheme, parts.path ?? "", parts.query ?? "", parts.fragment ?? ""); }
  static parse(value) {
    const text = String(value);
    if (/^file:\/\//i.test(text)) return new Uri("file", decodeURI(text.replace(/^file:\/\//i, "")));
    const match = /^([a-z][\w+.-]*):/i.exec(text);
    const uri = new Uri(match ? match[1].toLowerCase() : "file", match ? text.slice(match[0].length) : text);
    uri.raw = text;
    return uri;
  }
}

export class RelativePattern {
  constructor(base, pattern) {
    this.baseUri = typeof base === "string" ? Uri.file(base) : base.uri ?? base;
    this.base = this.baseUri.fsPath;
    this.pattern = pattern;
  }
}

export class Position {
  constructor(line, character) { this.line = line; this.character = character; }
}

export class Range {
  constructor(start, end) { this.start = start; this.end = end; }
}

export class WorkspaceEdit {
  constructor() { this.edits = []; }
  replace(uri, range, newText) { this.edits.push({ uri, range, newText }); }
}

export const ViewColumn = { Active: -1, Beside: -2, One: 1, Two: 2, Three: 3 };
export const ProgressLocation = { SourceControl: 1, Window: 10, Notification: 15 };
export const FileType = { Unknown: 0, File: 1, Directory: 2, SymbolicLink: 64 };

// ---------------------------------------------------------- workspace

function folders() {
  const ws = host().workspace;
  return ws ? [{ uri: Uri.file(ws.path), name: ws.name, index: 0 }] : undefined;
}

function folderOf(uri) {
  const target = canonicalPath(uri.fsPath ?? uri);
  return (folders() ?? []).find(f => {
    const root = canonicalPath(f.uri.fsPath);
    return target === root || target.startsWith(root + "/");
  });
}

function fsError(error, uri) {
  const message = error instanceof Error ? error.message : String(error);
  const out = new Error(message.includes("ENOENT") || message.includes("not found") ? `EntryNotFound (FileSystemError): ${uri.fsPath}` : message);
  out.code = message.includes("ENOENT") ? "FileNotFound" : "Unknown";
  return out;
}

/** Text of documents the extension opened, for edits that replace a range. */
const documents = new Map();

function textDocument(uri, text, lineCount) {
  const starts = [0];
  if (typeof text === "string") for (let i = 0; i < text.length; i++) if (text[i] === "\n") starts.push(i + 1);
  return {
    uri,
    fileName: uri.fsPath,
    lineCount: typeof text === "string" ? starts.length : lineCount,
    getText() {
      if (typeof text !== "string") throw new Error(`${posix.basename(uri.path)} is too large to read here.`);
      return text;
    },
    positionAt(offset) {
      const at = Math.max(0, Math.min(offset, text?.length ?? 0));
      let line = 0;
      while (line + 1 < starts.length && starts[line + 1] <= at) line++;
      return new Position(line, at - starts[line]);
    },
    offsetAt(position) { return Math.min((starts[position.line] ?? text?.length ?? 0) + position.character, text?.length ?? 0); },
    save: () => rpc("doc.save", { path: uri.fsPath }).then(() => true)
  };
}

const providers = new Map();

export const workspace = {
  get workspaceFolders() { return folders(); },
  get name() { return host().workspace?.name; },
  get isTrusted() { return host().trusted === true; },
  fs: {
    async stat(uri) {
      try {
        const s = await rpc("fs.stat", { path: uri.fsPath });
        if (!s) throw new Error("ENOENT");
        return { type: s.type, size: s.size, mtime: s.mtime ?? 0, ctime: s.mtime ?? 0 };
      } catch (error) {
        throw fsError(error, uri);
      }
    },
    async readFile(uri) {
      let text;
      try {
        text = await rpc("fs.read", { path: uri.fsPath });
      } catch (error) {
        throw fsError(error, uri);
      }
      if (text === null || text === undefined) throw fsError(new Error("ENOENT"), uri);
      return Buffer.from(text, "utf8");
    },
    async writeFile(uri, content) {
      await rpc("fs.write", { path: uri.fsPath, text: typeof content === "string" ? content : new TextDecoder().decode(content) });
    },
    async createDirectory(uri) {
      await rpc("fs.mkdir", { path: uri.fsPath });
    }
  },
  async findFiles(include, exclude, maxResults, token) {
    if (token?.isCancellationRequested) return [];
    const relative = include instanceof RelativePattern;
    const result = await rpc("ws.findFiles", {
      base: relative ? include.base : null,
      include: relative ? include.pattern : String(include),
      exclude: exclude ? String(exclude.pattern ?? exclude) : null,
      max: maxResults ?? 100000
    });
    addFacts(result?.facts);
    return (result?.paths ?? []).map(p => Uri.file(p));
  },
  getWorkspaceFolder(uri) { return folderOf(uri); },
  asRelativePath(pathOrUri, includeWorkspaceFolder) {
    const uri = typeof pathOrUri === "string" ? Uri.file(pathOrUri) : pathOrUri;
    const folder = folderOf(uri);
    if (!folder) return typeof pathOrUri === "string" ? pathOrUri : uri.fsPath;
    const rel = posix.relative(folder.uri.path, uri.path);
    const withFolder = includeWorkspaceFolder ?? (folders()?.length ?? 0) > 1;
    return withFolder ? (rel ? `${folder.name}/${rel}` : folder.name) : rel || uri.fsPath;
  },
  getConfiguration(section) {
    const values = host().settings?.[section] ?? {};
    return {
      get: (key, fallback) => (Object.prototype.hasOwnProperty.call(values, key) ? values[key] : fallback),
      has: key => Object.prototype.hasOwnProperty.call(values, key)
    };
  },
  async openTextDocument(uri) {
    const doc = await rpc("doc.open", { path: uri.fsPath });
    if (!doc) throw new Error(`${uri.fsPath} could not be opened.`);
    const document = textDocument(uri, doc.text ?? undefined, doc.lineCount ?? 1);
    if (typeof doc.text === "string") documents.set(canonicalPath(uri.fsPath), document);
    return document;
  },
  async applyEdit(edit) {
    for (const { uri, range, newText } of edit.edits) {
      const document = documents.get(canonicalPath(uri.fsPath));
      if (!document) return false;
      const text = document.getText();
      const next = text.slice(0, document.offsetAt(range.start)) + newText + text.slice(document.offsetAt(range.end));
      const ok = await rpc("doc.replace", { path: uri.fsPath, text: next });
      if (!ok) return false;
    }
    return true;
  },
  registerTextDocumentContentProvider(scheme, provider) {
    providers.set(scheme, provider);
    return new Disposable(() => providers.delete(scheme));
  },
  onDidChangeConfiguration: () => new Disposable(noop)
};

onHostEvent("__settings", message => {
  const info = host();
  info.settings = message.settings || info.settings;
});

// -------------------------------------------------------------- window

let visible = host().visible !== false;
const panelDisposed = new EventEmitter();
onHostEvent("__visibility", message => { visible = message.visible !== false; });
globalThis.addEventListener?.("pagehide", () => panelDisposed.fire(undefined));

const fromPage = new EventEmitter();
/** bridge.js hands every message the page posts to the extension's listeners here. */
globalThis.__devsnipToExtension = message => fromPage.fire(message);

/** Hooks the runtime adds to what the extension posts (to keep the last result when the tab closes). */
export const panelHooks = { onPost: noop };

function pagePanel(title) {
  return {
    title,
    viewType: "",
    iconPath: undefined,
    get visible() { return visible; },
    get active() { return visible; },
    webview: {
      cspSource: "devsnip:",
      options: {},
      html: "",
      asWebviewUri: uri => uri,
      onDidReceiveMessage: (listener, thisArg) => fromPage.event(thisArg ? listener.bind(thisArg) : listener),
      postMessage(message) {
        panelHooks.onPost(message);
        // VS Code delivers asynchronously; keep that order.
        Promise.resolve().then(() => globalThis.__devsnipDeliver?.(JSON.parse(JSON.stringify(message))));
        return Promise.resolve(true);
      }
    },
    onDidDispose: panelDisposed.event,
    onDidChangeViewState: () => new Disposable(noop),
    reveal() { void rpc("panel.reveal", {}).catch(noop); },
    dispose: noop
  };
}

function splitMessageArgs(rest) {
  let options = {};
  let items = rest;
  if (rest.length && rest[0] && typeof rest[0] === "object" && !Array.isArray(rest[0]) && !("title" in rest[0])) {
    options = rest[0];
    items = rest.slice(1);
  }
  return { options, items: items.map(item => (typeof item === "string" ? item : item?.title)).filter(Boolean) };
}

function message(kind) {
  return async (text, ...rest) => {
    const { options, items } = splitMessageArgs(rest);
    const choice = await rpc("ui.message", { kind, message: String(text), detail: options.detail ? String(options.detail) : null, modal: options.modal === true, items });
    return choice ?? undefined;
  };
}

const outputChannels = new Map();

function outputChannel(name) {
  if (outputChannels.has(name)) return outputChannels.get(name);
  let pending = [];
  let timer;
  const flush = () => {
    timer = undefined;
    if (!pending.length) return Promise.resolve();
    const lines = pending;
    pending = [];
    return rpc("log.append", { channel: name, lines }).catch(noop);
  };
  const write = level => value => {
    pending.push({ level, text: String(value) });
    timer ??= setTimeout(flush, 200);
  };
  const channel = {
    name,
    info: write("info"),
    warn: write("warning"),
    error: write("error"),
    debug: write("debug"),
    trace: write("trace"),
    appendLine: write("info"),
    append: write("info"),
    show() {
      if (timer) clearTimeout(timer);
      void flush().then(() => rpc("log.show", { channel: name })).catch(noop);
    },
    clear: noop,
    hide: noop,
    dispose: noop
  };
  outputChannels.set(name, channel);
  return channel;
}

export const window = {
  createWebviewPanel: (_viewType, title) => pagePanel(title),
  showInformationMessage: message("info"),
  showWarningMessage: message("warning"),
  showErrorMessage: message("error"),
  async showOpenDialog(options = {}) {
    const result = await rpc("ui.openDialog", {
      title: options.title ?? null,
      canSelectFiles: options.canSelectFiles !== false,
      canSelectFolders: options.canSelectFolders === true,
      canSelectMany: options.canSelectMany === true
    });
    addFacts(result?.facts);
    return result?.paths?.length ? result.paths.map(p => Uri.file(p)) : undefined;
  },
  async showSaveDialog(options = {}) {
    const chosen = await rpc("ui.saveDialog", {
      title: options.title ?? null,
      defaultPath: options.defaultUri ? options.defaultUri.fsPath : null,
      extensions: Object.values(options.filters ?? {}).flat()
    });
    return chosen ? Uri.file(chosen) : undefined;
  },
  async showInputBox(options = {}) {
    let value = options.value ?? "";
    let problem = null;
    for (;;) {
      const answer = await rpc("ui.inputBox", { title: options.title ?? null, prompt: options.prompt ?? null, password: options.password === true, value, problem });
      if (answer === null || answer === undefined) return undefined;
      problem = options.validateInput ? await options.validateInput(answer) : undefined;
      if (!problem) return answer;
      value = answer;
    }
  },
  async showTextDocument(document, options = {}) {
    const uri = document.uri ?? document;
    const line = options.selection ? options.selection.start.line : null;
    await rpc("doc.show", { path: uri.fsPath, line });
    return { document };
  },
  createTerminal(options = {}) {
    const cwd = options.cwd ? (typeof options.cwd === "string" ? options.cwd : options.cwd.fsPath) : null;
    return {
      name: options.name ?? "DevSnip Pro",
      show: noop,
      sendText: text => { void rpc("terminal.run", { name: options.name ?? "DevSnip Pro", cwd, command: String(text) }).catch(error => console.error("DevSnip Pro: terminal", error)); },
      dispose: noop
    };
  },
  async withProgress(options, task) {
    let id = null;
    try {
      id = await rpc("progress.start", { title: options.title ?? "DevSnip Pro" });
    } catch { /* progress is cosmetic */ }
    let last = 0;
    const progress = {
      report(value) {
        if (id === null || !value?.message || Date.now() - last < 150) return;
        last = Date.now();
        void rpc("progress.report", { id, message: String(value.message) }).catch(noop);
      }
    };
    try {
      return await task(progress, new CancellationTokenSource().token);
    } finally {
      if (id !== null) void rpc("progress.end", { id }).catch(noop);
    }
  },
  createOutputChannel: name => outputChannel(name),
  get activeTextEditor() { return undefined; }
};

export const commands = {
  async executeCommand(id, ...args) {
    switch (id) {
      case "workbench.action.openSettings":
        return rpc("ide.openSettings", { query: String(args[0] ?? "") });
      case "workbench.action.files.openFolder":
        return rpc("ide.openFolder", {});
      case "vscode.diff": {
        const [left, right, title] = args;
        const provider = providers.get(right.scheme);
        const text = provider ? await provider.provideTextDocumentContent(right, new CancellationTokenSource().token) : "";
        return rpc("ide.diff", { path: left.fsPath, text: String(text ?? ""), title: String(title ?? "") });
      }
      default:
        return undefined;
    }
  },
  registerCommand: () => new Disposable(noop)
};

export const env = {
  clipboard: { writeText: text => rpc("ide.copy", { text: String(text) }).then(noop) },
  openExternal: uri => rpc("ide.openExternal", { url: uri.scheme === "file" ? uri.toString() : uri.raw ?? uri.toString() }).then(() => true),
  get appName() { return host().ideName || "JetBrains IDE"; }
};

export const version = host().ideVersion || "JetBrains";

/** The extension context the scanners get: storage, secrets and per-project state, kept by the IDE. */
export function extensionContext() {
  const info = host();
  const state = { ...(info.workspaceState || {}) };
  return {
    subscriptions: [],
    extensionPath: info.extensionPath || "/devsnip-pro",
    globalStorageUri: info.globalStorage ? Uri.file(info.globalStorage) : undefined,
    extension: { packageJSON: { version: info.version || "dev" } },
    workspaceState: {
      get: (key, fallback) => (Object.prototype.hasOwnProperty.call(state, key) ? state[key] : fallback),
      async update(key, value) {
        state[key] = value;
        await rpc("state.update", { key, value: value === undefined ? null : value });
      },
      keys: () => Object.keys(state)
    },
    // The IDE keeps secrets in its password safe; the page only ever sees a marker, which the IDE swaps in when it sends the request.
    secrets: {
      get: key => rpc("secrets.get", { key }).then(value => value ?? undefined),
      store: (key, value) => rpc("secrets.store", { key, value: String(value) }).then(noop),
      delete: key => rpc("secrets.store", { key, value: null }).then(noop)
    }
  };
}

export default {
  Disposable, EventEmitter, CancellationError, CancellationTokenSource, Uri, RelativePattern, Position, Range, WorkspaceEdit,
  ViewColumn, ProgressLocation, FileType, workspace, window, commands, env, version
};
