/*
 * The VS Code API for DevSnip Pro's extension host in the JetBrains IDE
 * (webview/src/host-runtime.js): the extension's own command code runs on
 * this API in a hidden page of the IDE's embedded browser, and EngineHost.kt
 * answers what it asks VS Code for. Webview panels open as IDE editor tabs,
 * the Tools sidebar is the DevSnip Pro tool window, custom editors are IDE
 * file editors, and text editors and documents are the IDE's.
 *
 * Only the part of the API the extension uses is here, with VS Code's
 * semantics: messages return the item chosen, panels deliver messages in
 * order, edits are undoable, configuration falls back to package.json defaults.
 */
import posix from "path-browserify";
import { canonicalPath, fromBase64, host, onHostEvent, rpc, syncCall, toBase64, toHost } from "./host-channel.js";
import manifest from "@devsnip-manifest";

const noop = () => undefined;

export class Disposable {
  constructor(fn) { this.fn = fn; }
  static from(...items) { return new Disposable(() => items.forEach(i => i?.dispose?.())); }
  dispose() { const fn = this.fn; this.fn = undefined; fn?.(); }
}

export class EventEmitter {
  constructor() {
    this.listeners = new Set();
    this.event = (listener, thisArg, disposables) => {
      const bound = thisArg ? listener.bind(thisArg) : listener;
      this.listeners.add(bound);
      const disposable = new Disposable(() => this.listeners.delete(bound));
      if (Array.isArray(disposables)) disposables.push(disposable);
      return disposable;
    };
  }
  fire(value) {
    for (const listener of [...this.listeners]) {
      try {
        listener(value);
      } catch (error) {
        console.error("DevSnip Pro: an event listener failed.", error);
      }
    }
  }
  dispose() { this.listeners.clear(); }
}

/** An event that never fires (VS Code events the IDE has no counterpart for). */
const never = () => new Disposable(noop);

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

const NONE_TOKEN = { isCancellationRequested: false, onCancellationRequested: never };

// ---------------------------------------------------------------- Uri

/** file:// paths as VS Code keeps them: POSIX, with Windows drives as /C:/…. */
function toUriPath(p) {
  let out = String(p).replace(/\\/g, "/");
  if (/^[A-Za-z]:/.test(out)) out = "/" + out;
  return out;
}

export class Uri {
  constructor(scheme, authority, path, query, fragment) {
    this.scheme = scheme;
    this.authority = authority || "";
    this.path = path || "";
    this.query = query || "";
    this.fragment = fragment || "";
  }
  get fsPath() {
    const p = /^\/[A-Za-z]:/.test(this.path) ? this.path.slice(1) : this.path;
    return host().platform === "win32" ? p.replace(/\//g, "\\") : p;
  }
  with(change) {
    return new Uri(change.scheme ?? this.scheme, change.authority ?? this.authority, change.path ?? this.path, change.query ?? this.query, change.fragment ?? this.fragment);
  }
  toString() {
    if (this.scheme === "file") return "file://" + encodeURI(this.path).replace(/[?#]/g, encodeURIComponent);
    const authority = this.authority ? `//${this.authority}` : "";
    return `${this.scheme}:${authority}${this.path}${this.query ? `?${this.query}` : ""}${this.fragment ? `#${this.fragment}` : ""}`;
  }
  toJSON() { return { scheme: this.scheme, authority: this.authority, path: this.path, query: this.query, fragment: this.fragment, fsPath: this.fsPath, external: this.toString() }; }
  static file(p) { return new Uri("file", "", toUriPath(p)); }
  static joinPath(base, ...segments) { return base.with({ path: posix.join(base.path || "/", ...segments) }); }
  static from(parts) { return new Uri(parts.scheme, parts.authority, parts.path, parts.query, parts.fragment); }
  static isUri(value) { return value instanceof Uri; }
  static parse(value) {
    const text = String(value);
    const match = /^([a-zA-Z][\w+.-]*):(\/\/([^/?#]*))?([^?#]*)(\?([^#]*))?(#(.*))?$/.exec(text);
    if (!match) return new Uri("file", "", toUriPath(text));
    const scheme = match[1].toLowerCase();
    let path = match[4] || "";
    if (scheme === "file") {
      try { path = decodeURIComponent(path); } catch { /* keep as is */ }
    }
    return new Uri(scheme, match[3] || "", path, match[6] || "", match[8] || "");
  }
}

export class RelativePattern {
  constructor(base, pattern) {
    this.baseUri = typeof base === "string" ? Uri.file(base) : base.uri ?? base;
    this.base = this.baseUri.fsPath;
    this.pattern = pattern;
  }
}

// ----------------------------------------------------------- positions

export class Position {
  constructor(line, character) { this.line = line; this.character = character; }
  isBefore(other) { return this.line < other.line || (this.line === other.line && this.character < other.character); }
  isBeforeOrEqual(other) { return !other.isBefore(this); }
  isAfter(other) { return other.isBefore(this); }
  isAfterOrEqual(other) { return !this.isBefore(other); }
  isEqual(other) { return this.line === other.line && this.character === other.character; }
  compareTo(other) { return this.isBefore(other) ? -1 : this.isEqual(other) ? 0 : 1; }
  translate(lineDelta = 0, characterDelta = 0) {
    if (typeof lineDelta === "object") return new Position(this.line + (lineDelta.lineDelta ?? 0), this.character + (lineDelta.characterDelta ?? 0));
    return new Position(this.line + lineDelta, this.character + characterDelta);
  }
  with(line, character) {
    if (typeof line === "object" && line !== null) return new Position(line.line ?? this.line, line.character ?? this.character);
    return new Position(line ?? this.line, character ?? this.character);
  }
}

export class Range {
  constructor(a, b, c, d) {
    if (typeof a === "number") { this.start = new Position(a, b); this.end = new Position(c, d); }
    else { this.start = a; this.end = b; }
    if (this.end.isBefore(this.start)) [this.start, this.end] = [this.end, this.start];
  }
  get isEmpty() { return this.start.isEqual(this.end); }
  get isSingleLine() { return this.start.line === this.end.line; }
  contains(value) {
    if (value instanceof Range) return this.contains(value.start) && this.contains(value.end);
    return value.isAfterOrEqual(this.start) && value.isBeforeOrEqual(this.end);
  }
  isEqual(other) { return this.start.isEqual(other.start) && this.end.isEqual(other.end); }
  with(start, end) {
    if (start && !(start instanceof Position)) return new Range(start.start ?? this.start, start.end ?? this.end);
    return new Range(start ?? this.start, end ?? this.end);
  }
  union(other) {
    return new Range(this.start.isBefore(other.start) ? this.start : other.start, this.end.isAfter(other.end) ? this.end : other.end);
  }
  intersection(other) {
    const start = this.start.isAfter(other.start) ? this.start : other.start;
    const end = this.end.isBefore(other.end) ? this.end : other.end;
    return start.isAfter(end) ? undefined : new Range(start, end);
  }
}

export class Selection extends Range {
  constructor(anchor, active, c, d) {
    if (typeof anchor === "number") { anchor = new Position(anchor, active); active = new Position(c, d); }
    super(anchor, active);
    this.anchor = anchor;
    this.active = active;
  }
  get isReversed() { return this.active.isBefore(this.anchor); }
}

export class SnippetString {
  constructor(value = "") { this.value = value; }
  appendText(text) { this.value += String(text).replace(/[$}\\]/g, "\\$&"); return this; }
  appendPlaceholder(text, number) { this.value += `\${${number ?? 1}:${text}}`; return this; }
  appendTabstop(number = 0) { this.value += `$${number}`; return this; }
}

export class ThemeColor { constructor(id) { this.id = id; } }
export class ThemeIcon { constructor(id, color) { this.id = id; this.color = color; } }
export class MarkdownString {
  constructor(value = "") { this.value = value; }
  appendMarkdown(text) { this.value += text; return this; }
  appendText(text) { this.value += text; return this; }
}

export class TabInputText { constructor(uri) { this.uri = uri; } }
export class TabInputWebview { constructor(viewType) { this.viewType = viewType; } }
export class TabInputCustom { constructor(uri, viewType) { this.uri = uri; this.viewType = viewType; } }
export class TabInputTextDiff { constructor(original, modified) { this.original = original; this.modified = modified; } }

export const ViewColumn = { Active: -1, Beside: -2, One: 1, Two: 2, Three: 3 };
export const ProgressLocation = { SourceControl: 1, Window: 10, Notification: 15 };
export const FileType = { Unknown: 0, File: 1, Directory: 2, SymbolicLink: 64 };
export const StatusBarAlignment = { Left: 1, Right: 2 };
export const ExtensionMode = { Production: 1, Development: 2, Test: 3 };
export const EndOfLine = { LF: 1, CRLF: 2 };
export const ConfigurationTarget = { Global: 1, Workspace: 2, WorkspaceFolder: 3 };
export const TextEditorRevealType = { Default: 0, InCenter: 1, InCenterIfOutsideViewport: 2, AtTop: 3 };
export const QuickPickItemKind = { Separator: -1, Default: 0 };
export const UIKind = { Desktop: 1, Web: 2 };
export const OverviewRulerLane = { Left: 1, Center: 2, Right: 4, Full: 7 };
export const DiagnosticSeverity = { Error: 0, Warning: 1, Information: 2, Hint: 3 };

export class FileSystemError extends Error {
  constructor(message, code) { super(message); this.code = code || "Unknown"; this.name = `${this.code} (FileSystemError)`; }
  static FileNotFound(target) { return new FileSystemError(`${target?.fsPath ?? target ?? ""} not found`, "FileNotFound"); }
  static FileExists(target) { return new FileSystemError(`${target?.fsPath ?? target ?? ""} exists`, "FileExists"); }
  static NoPermissions(target) { return new FileSystemError(String(target?.fsPath ?? target ?? "No permissions"), "NoPermissions"); }
  static FileIsADirectory(target) { return new FileSystemError(String(target?.fsPath ?? target ?? ""), "FileIsADirectory"); }
  static FileNotADirectory(target) { return new FileSystemError(String(target?.fsPath ?? target ?? ""), "FileNotADirectory"); }
  static Unavailable(target) { return new FileSystemError(String(target?.fsPath ?? target ?? ""), "Unavailable"); }
}

function fsError(error, uri) {
  const message = error instanceof Error ? error.message : String(error);
  if (/ENOENT|not found|does not exist/i.test(message) || error?.code === "ENOENT") return FileSystemError.FileNotFound(uri);
  if (/EACCES|not allowed|only reads|only writes/i.test(message)) return new FileSystemError(message, "NoPermissions");
  return new FileSystemError(message, "Unknown");
}

// ---------------------------------------------------------- documents

const EOL_CRLF = "\r\n";

/**
 * A text document as the IDE reported it. Its text is kept up to date for
 * documents this host edits, and for documents whose changes it listens to.
 */
class HostTextDocument {
  constructor(info) {
    this.uri = info.path ? Uri.file(info.path) : Uri.parse(info.uri || `untitled:${info.name || "Untitled"}`);
    this.fileName = info.path || info.name || this.uri.path;
    this.languageId = info.languageId || "plaintext";
    this.version = info.version ?? 1;
    this.isDirty = Boolean(info.isDirty);
    this.isUntitled = !info.path;
    this.isClosed = false;
    this.eol = info.eol === "crlf" ? EndOfLine.CRLF : EndOfLine.LF;
    this.encoding = "utf8";
    this.id = info.id;
    this.setText(typeof info.text === "string" ? info.text : undefined, info.lineCount);
  }

  setText(text, lineCount) {
    this.text = text;
    this.starts = [0];
    if (typeof text === "string") {
      for (let i = 0; i < text.length; i++) if (text.charCodeAt(i) === 10) this.starts.push(i + 1);
      this.lineCountValue = this.starts.length;
    } else {
      this.lineCountValue = lineCount ?? 1;
    }
  }

  get lineCount() { return this.lineCountValue; }

  requireText() {
    if (typeof this.text !== "string") throw new Error(`${posix.basename(this.uri.path)} is too large to read here.`);
    return this.text;
  }

  getText(range) {
    const text = this.requireText();
    if (!range) return text;
    return text.slice(this.offsetAt(range.start), this.offsetAt(range.end));
  }

  lineAt(lineOrPosition) {
    const line = typeof lineOrPosition === "number" ? lineOrPosition : lineOrPosition.line;
    const text = this.requireText();
    if (line < 0 || line >= this.starts.length) throw new Error("Illegal value for `line`");
    const start = this.starts[line];
    const next = line + 1 < this.starts.length ? this.starts[line + 1] : text.length + (text.endsWith("\n") ? 0 : 0);
    let end = line + 1 < this.starts.length ? next - 1 : text.length;
    if (end > start && text[end - 1] === "\r") end--;
    const content = text.slice(start, end);
    const firstNonWhitespaceCharacterIndex = content.search(/\S|$/);
    return {
      lineNumber: line,
      text: content,
      range: new Range(line, 0, line, content.length),
      rangeIncludingLineBreak: new Range(line, 0, line + 1 < this.starts.length ? line + 1 : line, line + 1 < this.starts.length ? 0 : content.length),
      firstNonWhitespaceCharacterIndex,
      isEmptyOrWhitespace: firstNonWhitespaceCharacterIndex === content.length
    };
  }

  positionAt(offset) {
    const text = this.requireText();
    const at = Math.max(0, Math.min(Math.floor(offset), text.length));
    let low = 0;
    let high = this.starts.length - 1;
    while (low < high) {
      const mid = (low + high + 1) >> 1;
      if (this.starts[mid] <= at) low = mid; else high = mid - 1;
    }
    return new Position(low, at - this.starts[low]);
  }

  offsetAt(position) {
    const text = this.requireText();
    if (position.line >= this.starts.length) return text.length;
    if (position.line < 0) return 0;
    const start = this.starts[position.line];
    const lineEnd = position.line + 1 < this.starts.length ? this.starts[position.line + 1] - 1 : text.length;
    return Math.min(start + Math.max(0, position.character), lineEnd);
  }

  validateRange(range) { return range; }
  validatePosition(position) { return position; }

  getWordRangeAtPosition(position, regex) {
    const line = this.lineAt(position.line).text;
    const pattern = regex ? new RegExp(regex.source, regex.flags.includes("g") ? regex.flags : regex.flags + "g") : /[A-Za-z_$][\w$]*/g;
    let match;
    while ((match = pattern.exec(line))) {
      const start = match.index;
      const end = start + match[0].length;
      if (start <= position.character && position.character <= end) return new Range(position.line, start, position.line, end);
      if (match[0].length === 0) pattern.lastIndex++;
    }
    return undefined;
  }

  /** Applies edits (sorted, non-overlapping) to the local text. */
  applyLocal(edits) {
    if (typeof this.text !== "string") return;
    const sorted = edits.map(e => ({ from: this.offsetAt(e.range.start), to: this.offsetAt(e.range.end), text: e.text })).sort((a, b) => b.from - a.from);
    let text = this.text;
    for (const e of sorted) text = text.slice(0, e.from) + e.text + text.slice(e.to);
    this.setText(text);
    this.version += 1;
    this.isDirty = true;
  }

  async save() {
    if (this.isUntitled) return false;
    await rpc("doc.save", { path: this.uri.fsPath });
    this.isDirty = false;
    return true;
  }
}

/** Documents known to this host, by canonical path (or untitled id). */
const documents = new Map();

function documentKey(info) {
  return info.path ? canonicalPath(info.path) : `untitled:${info.id ?? info.name}`;
}

/** The document for an IDE snapshot, updating the one already known. */
function documentFrom(info) {
  const key = documentKey(info);
  const known = documents.get(key);
  if (known && !known.isClosed) {
    if (typeof info.text === "string") known.setText(info.text);
    else if (info.lineCount !== undefined && typeof known.text !== "string") known.lineCountValue = info.lineCount;
    if (info.version !== undefined) known.version = info.version;
    if (info.isDirty !== undefined) known.isDirty = Boolean(info.isDirty);
    if (info.languageId) known.languageId = info.languageId;
    return known;
  }
  const document = new HostTextDocument(info);
  documents.set(key, document);
  return document;
}

function toWireRange(range) {
  return { start: { line: range.start.line, character: range.start.character }, end: { line: range.end.line, character: range.end.character } };
}

function fromWireSelection(s) {
  return new Selection(new Position(s.anchor.line, s.anchor.character), new Position(s.active.line, s.active.character));
}

// ------------------------------------------------------------- editors

class HostTextEditor {
  constructor(info) {
    this.id = info.id;
    this.document = documentFrom(info);
    this.selections = (info.selections && info.selections.length ? info.selections : [{ anchor: { line: 0, character: 0 }, active: { line: 0, character: 0 } }]).map(fromWireSelection);
    this.visibleRanges = [new Range(0, 0, Math.max(0, this.document.lineCount - 1), 0)];
    this.options = { tabSize: info.tabSize ?? 4, insertSpaces: info.insertSpaces !== false };
    this.viewColumn = ViewColumn.One;
  }

  get selection() { return this.selections[0]; }
  set selection(value) { this.selections = [value]; void this.pushSelections(); }

  pushSelections() {
    return rpc("editor.select", { path: this.document.isUntitled ? null : this.document.uri.fsPath, id: this.id, selections: this.selections.map(s => ({ anchor: { line: s.anchor.line, character: s.anchor.character }, active: { line: s.active.line, character: s.active.character } })) }).catch(noop);
  }

  async edit(callback, options = {}) {
    const edits = [];
    let eol;
    const builder = {
      replace: (location, value) => {
        const range = location instanceof Position ? new Range(location, location) : location;
        edits.push({ range, text: String(value) });
      },
      insert: (position, value) => edits.push({ range: new Range(position, position), text: String(value) }),
      delete: range => edits.push({ range, text: "" }),
      setEndOfLine: value => { eol = value; }
    };
    callback(builder);
    if (!edits.length && eol === undefined) return true;
    const result = await rpc("editor.edit", {
      path: this.document.isUntitled ? null : this.document.uri.fsPath,
      id: this.id,
      version: this.document.version,
      edits: edits.map(e => ({ range: toWireRange(e.range), text: e.text })),
      eol: eol === EndOfLine.CRLF ? "crlf" : eol === EndOfLine.LF ? "lf" : null,
      undoStopBefore: options.undoStopBefore !== false
    });
    if (!result || result.ok !== true) return false;
    if (typeof result.text === "string") {
      this.document.setText(result.text);
      this.document.version = result.version ?? this.document.version + 1;
      this.document.isDirty = true;
    } else {
      this.document.applyLocal(edits);
    }
    return true;
  }

  async insertSnippet(snippet, location) {
    const value = snippet instanceof SnippetString ? snippet.value : String(snippet?.value ?? snippet);
    const ranges = location === undefined ? this.selections : Array.isArray(location) ? location : [location instanceof Position ? new Range(location, location) : location];
    const result = await rpc("editor.insertSnippet", {
      path: this.document.isUntitled ? null : this.document.uri.fsPath,
      id: this.id,
      snippet: value,
      ranges: ranges.map(toWireRange)
    });
    if (result && typeof result.text === "string") {
      this.document.setText(result.text);
      this.document.version += 1;
    }
    return Boolean(result && result.ok);
  }

  revealRange(range) {
    void rpc("editor.reveal", { path: this.document.isUntitled ? null : this.document.uri.fsPath, id: this.id, range: toWireRange(range) }).catch(noop);
  }

  setDecorations() {}
  show() { void rpc("doc.show", { path: this.document.uri.fsPath }).catch(noop); }
  hide() {}
}

let activeEditorInfo = null;
let visibleEditorInfos = [];
let activeEditor;
const activeEditorChanged = new EventEmitter();

function editorFor(info) {
  if (!info) return undefined;
  if (activeEditor && activeEditor.id === info.id && activeEditor.document.version === info.version && typeof activeEditor.document.text === typeof info.text) return activeEditor;
  return new HostTextEditor(info);
}

/** The IDE sends the editor state with every command it runs, and when the selected editor changes. */
export function setEditorState(state) {
  if (!state) return;
  if (Array.isArray(state.documents)) for (const info of state.documents) documentFrom(info);
  const previous = activeEditor;
  activeEditorInfo = state.active ?? null;
  visibleEditorInfos = Array.isArray(state.visible) ? state.visible : activeEditorInfo ? [activeEditorInfo] : [];
  activeEditor = editorFor(activeEditorInfo);
  if ((previous?.id ?? null) !== (activeEditor?.id ?? null)) activeEditorChanged.fire(activeEditor);
}

onHostEvent("__editors", message => setEditorState(message.state));

onHostEvent("__docClosed", message => {
  const document = documents.get(canonicalPath(message.path || ""));
  if (document) {
    document.isClosed = true;
    documents.delete(canonicalPath(message.path));
    closedDocument.fire(document);
  }
});

const changedDocument = new EventEmitter();
const savedDocument = new EventEmitter();
const closedDocument = new EventEmitter();
let documentEventsWanted = false;

function wantDocumentEvents() {
  if (documentEventsWanted) return;
  documentEventsWanted = true;
  void rpc("events.documents", { enabled: true }).catch(noop);
}

onHostEvent("__docChanged", message => {
  const document = documentFrom({ path: message.path, text: message.text, version: message.version, isDirty: message.isDirty, lineCount: message.lineCount, languageId: message.languageId });
  changedDocument.fire({ document, contentChanges: [], reason: undefined });
});

onHostEvent("__docSaved", message => {
  const document = documentFrom({ path: message.path, version: message.version, isDirty: false, languageId: message.languageId });
  document.isDirty = false;
  savedDocument.fire(document);
});

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

/** package.json configuration defaults ("devsnip.apiTimeout": 30000, ...). */
const DEFAULTS = (() => {
  const out = {};
  const groups = manifest?.contributes?.configuration;
  for (const group of Array.isArray(groups) ? groups : groups ? [groups] : []) {
    for (const [key, schema] of Object.entries(group.properties || {})) if ("default" in schema) out[key] = schema.default;
  }
  return out;
})();

const configurationChanged = new EventEmitter();

onHostEvent("__settings", message => {
  const info = host();
  const before = { ...(info.settings || {}) };
  info.settings = message.settings || {};
  const keys = new Set([...Object.keys(before), ...Object.keys(info.settings)].filter(k => JSON.stringify(before[k]) !== JSON.stringify(info.settings[k])));
  if (!keys.size) return;
  configurationChanged.fire({ affectsConfiguration: section => [...keys].some(k => k === section || k.startsWith(section + ".")) });
});

function settingValue(key) {
  const values = host().settings || {};
  if (Object.prototype.hasOwnProperty.call(values, key)) return { found: true, value: values[key] };
  if (Object.prototype.hasOwnProperty.call(DEFAULTS, key)) return { found: true, value: DEFAULTS[key] };
  return { found: false };
}

function configuration(section) {
  const full = key => (section ? `${section}.${key}` : key);
  return {
    get(key, fallback) {
      const { found, value } = settingValue(full(key));
      return found && value !== undefined && value !== null ? value : fallback;
    },
    has(key) { return settingValue(full(key)).found; },
    inspect(key) {
      const values = host().settings || {};
      return { key: full(key), defaultValue: DEFAULTS[full(key)], globalValue: values[full(key)] };
    },
    async update(key, value) {
      const info = host();
      info.settings = { ...(info.settings || {}) };
      if (value === undefined) delete info.settings[full(key)];
      else info.settings[full(key)] = value;
      await rpc("config.update", { key: full(key), value: value === undefined ? null : value });
    }
  };
}

const providers = new Map();
const watchers = new Map();
let nextWatcher = 1;

onHostEvent("__fsEvent", message => {
  const watcher = watchers.get(message.id);
  if (!watcher) return;
  const uri = Uri.file(message.path);
  if (message.kind === "create" && !watcher.ignoreCreate) watcher.created.fire(uri);
  else if (message.kind === "change" && !watcher.ignoreChange) watcher.changed.fire(uri);
  else if (message.kind === "delete" && !watcher.ignoreDelete) watcher.deleted.fire(uri);
});

function uriFromWire(value) {
  if (value instanceof Uri) return value;
  if (value && typeof value === "object" && value.scheme) return Uri.from(value);
  if (typeof value === "string" && /^[a-z][\w+.-]*:\/\//i.test(value)) return Uri.parse(value);
  return Uri.file(String(value));
}

const untitledDocs = new Map();
let nextUntitled = 1;

export const workspace = {
  get workspaceFolders() { return folders(); },
  get name() { return host().workspace?.name; },
  get workspaceFile() { return undefined; },
  get rootPath() { return host().workspace?.path; },
  get isTrusted() { return host().trusted === true; },
  get textDocuments() { return [...documents.values()].filter(d => !d.isClosed); },
  fs: {
    async stat(uri) {
      try {
        const s = await rpc("fs.stat", { path: uri.fsPath });
        if (!s) throw new Error("ENOENT");
        return { type: s.type, size: s.size, mtime: s.mtime ?? 0, ctime: s.ctime ?? s.mtime ?? 0, permissions: s.readonly ? 1 : undefined };
      } catch (error) {
        throw fsError(error, uri);
      }
    },
    async readFile(uri) {
      let base64;
      try {
        base64 = await rpc("fs.readBytes", { path: uri.fsPath });
      } catch (error) {
        throw fsError(error, uri);
      }
      if (base64 === null || base64 === undefined) throw FileSystemError.FileNotFound(uri);
      return Buffer.from(fromBase64(base64));
    },
    async writeFile(uri, content) {
      try {
        await rpc("fs.writeBytes", { path: uri.fsPath, base64: toBase64(content instanceof Uint8Array ? content : new TextEncoder().encode(String(content))) });
      } catch (error) {
        throw fsError(error, uri);
      }
    },
    async createDirectory(uri) {
      try { await rpc("fs.mkdir", { path: uri.fsPath }); } catch (error) { throw fsError(error, uri); }
    },
    async delete(uri, options = {}) {
      try { await rpc("fs.delete", { path: uri.fsPath, recursive: Boolean(options.recursive), useTrash: Boolean(options.useTrash) }); } catch (error) { throw fsError(error, uri); }
    },
    async readDirectory(uri) {
      try {
        const entries = await rpc("fs.readdir", { path: uri.fsPath });
        return (entries || []).map(e => [e.name, e.type]);
      } catch (error) {
        throw fsError(error, uri);
      }
    },
    async rename(source, target, options = {}) {
      try { await rpc("fs.rename", { from: source.fsPath, to: target.fsPath, overwrite: Boolean(options.overwrite) }); } catch (error) { throw fsError(error, source); }
    },
    async copy(source, target, options = {}) {
      try { await rpc("fs.copy", { from: source.fsPath, to: target.fsPath, overwrite: Boolean(options.overwrite) }); } catch (error) { throw fsError(error, source); }
    },
    isWritableFileSystem(scheme) { return scheme === "file" ? true : undefined; }
  },
  async findFiles(include, exclude, maxResults, token) {
    if (token?.isCancellationRequested) return [];
    const relative = include instanceof RelativePattern;
    const excludeText = exclude === null ? null : exclude === undefined ? undefined : String(exclude.pattern ?? exclude);
    const paths = await rpc("ws.findFiles", {
      base: relative ? include.base : null,
      include: relative ? include.pattern : String(include),
      exclude: excludeText === undefined ? "**/{node_modules,.git}/**" : excludeText,
      useDefaultExcludes: exclude === undefined,
      max: maxResults ?? 100000
    });
    if (token?.isCancellationRequested) return [];
    return (paths ?? []).map(p => Uri.file(p));
  },
  getWorkspaceFolder(uri) { return folderOf(uri); },
  asRelativePath(pathOrUri, includeWorkspaceFolder) {
    const uri = typeof pathOrUri === "string" ? Uri.file(pathOrUri) : pathOrUri;
    const folder = folderOf(uri);
    if (!folder) return typeof pathOrUri === "string" ? pathOrUri : uri.fsPath;
    const rel = posix.relative(folder.uri.path, uri.path);
    const withFolder = includeWorkspaceFolder ?? false;
    return withFolder ? (rel ? `${folder.name}/${rel}` : folder.name) : rel || uri.fsPath;
  },
  getConfiguration(section) { return configuration(section); },
  async openTextDocument(target) {
    if (target && typeof target === "object" && !(target instanceof Uri) && !target.scheme) {
      // An untitled document with content: shown as a scratch tab by showTextDocument.
      const id = `u${nextUntitled++}`;
      const info = { id, name: `Untitled-${id.slice(1)}`, text: String(target.content ?? ""), languageId: target.language || "plaintext", version: 1 };
      untitledDocs.set(id, info);
      return documentFrom(info);
    }
    const uri = target instanceof Uri ? target : uriFromWire(target);
    const info = await rpc("doc.open", { path: uri.fsPath });
    if (!info) throw new Error(`${uri.fsPath} could not be opened.`);
    return documentFrom({ ...info, path: info.path || uri.fsPath });
  },
  async applyEdit(edit) {
    const byPath = new Map();
    for (const e of edit.entries ?? edit.edits ?? []) {
      const key = e.uri.fsPath;
      if (!byPath.has(key)) byPath.set(key, []);
      byPath.get(key).push(e);
    }
    const files = (edit.fileOps ?? []).map(op => ({ kind: op.kind, path: op.uri.fsPath, to: op.to?.fsPath, contents: op.contents === undefined ? null : toBase64(op.contents), overwrite: Boolean(op.options?.overwrite), ignoreIfExists: Boolean(op.options?.ignoreIfExists), recursive: Boolean(op.options?.recursive) }));
    const result = await rpc("workspace.applyEdit", {
      files,
      edits: [...byPath.entries()].map(([path, list]) => ({ path, edits: list.map(e => ({ range: toWireRange(e.range), text: e.newText })) }))
    });
    if (!result || result.ok !== true) return false;
    for (const [path, list] of byPath) {
      const document = documents.get(canonicalPath(path));
      if (document && !document.isClosed) document.applyLocal(list.map(e => ({ range: e.range, text: e.newText })));
    }
    return true;
  },
  async saveAll() { await rpc("doc.saveAll", {}); return true; },
  registerTextDocumentContentProvider(scheme, provider) {
    providers.set(scheme, provider);
    return new Disposable(() => providers.delete(scheme));
  },
  createFileSystemWatcher(pattern, ignoreCreate = false, ignoreChange = false, ignoreDelete = false) {
    const id = nextWatcher++;
    const watcher = {
      ignoreCreate, ignoreChange, ignoreDelete,
      created: new EventEmitter(), changed: new EventEmitter(), deleted: new EventEmitter()
    };
    watchers.set(id, watcher);
    const relative = pattern instanceof RelativePattern;
    void rpc("fs.watch", { id, base: relative ? pattern.base : null, pattern: relative ? pattern.pattern : String(pattern) }).catch(noop);
    return {
      ignoreCreateEvents: ignoreCreate, ignoreChangeEvents: ignoreChange, ignoreDeleteEvents: ignoreDelete,
      onDidCreate: watcher.created.event,
      onDidChange: watcher.changed.event,
      onDidDelete: watcher.deleted.event,
      dispose() {
        watchers.delete(id);
        void rpc("fs.unwatch", { id }).catch(noop);
      }
    };
  },
  onDidChangeConfiguration: configurationChanged.event,
  onDidChangeTextDocument(listener, thisArg, disposables) { wantDocumentEvents(); return changedDocument.event(listener, thisArg, disposables); },
  onDidSaveTextDocument(listener, thisArg, disposables) { wantDocumentEvents(); return savedDocument.event(listener, thisArg, disposables); },
  onDidCloseTextDocument: closedDocument.event,
  onDidOpenTextDocument: never,
  onDidChangeWorkspaceFolders: never,
  onDidGrantWorkspaceTrust: never,
  onDidCreateFiles: never,
  onDidDeleteFiles: never,
  onDidRenameFiles: never
};

export class WorkspaceEdit {
  constructor() { this.entries = []; this.fileOps = []; }
  replace(uri, range, newText) { this.entries.push({ uri, range, newText: String(newText) }); }
  insert(uri, position, newText) { this.entries.push({ uri, range: new Range(position, position), newText: String(newText) }); }
  delete(uri, range) { this.entries.push({ uri, range, newText: "" }); }
  createFile(uri, options = {}) { this.fileOps.push({ kind: "create", uri, options, contents: options.contents }); }
  deleteFile(uri, options = {}) { this.fileOps.push({ kind: "delete", uri, options }); }
  renameFile(from, to, options = {}) { this.fileOps.push({ kind: "rename", uri: from, to, options }); }
  has(uri) { return this.entries.some(e => e.uri.toString() === uri.toString()); }
  get size() { return new Set(this.entries.map(e => e.uri.toString())).size; }
  set(uri, edits) { for (const e of edits) this.entries.push({ uri, range: e.range, newText: e.newText }); }
  get(uri) { return this.entries.filter(e => e.uri.toString() === uri.toString()).map(e => ({ range: e.range, newText: e.newText })); }
}

export class TextEdit {
  constructor(range, newText) { this.range = range; this.newText = newText; }
  static replace(range, newText) { return new TextEdit(range, newText); }
  static insert(position, newText) { return new TextEdit(new Range(position, position), newText); }
  static delete(range) { return new TextEdit(range, ""); }
}

// ------------------------------------------------------- webviews

const panels = new Map();
let nextPanel = 1;

onHostEvent("__panelMessage", message => panels.get(message.panel)?.received.fire(message.message));
onHostEvent("__panelState", message => {
  const panel = panels.get(message.panel);
  if (!panel) return;
  const changed = panel.visible !== Boolean(message.visible) || panel.active !== Boolean(message.active);
  panel.visible = Boolean(message.visible);
  panel.active = Boolean(message.active);
  if (changed) panel.stateChanged();
});
onHostEvent("__panelClosed", message => panels.get(message.panel)?.closed());

/**
 * One webview: an editor tab (a panel or a custom editor) or the Tools
 * sidebar. What the extension posts goes to the IDE, which delivers it to the
 * tab's page; the page's messages come back as "__panelMessage".
 */
class HostWebview {
  constructor(id, kind) {
    this.id = id;
    this.kind = kind;
    this.htmlValue = "";
    this.optionsValue = {};
    this.received = new EventEmitter();
    this.disposedFlag = false;
  }
  get cspSource() { return "file:"; }
  get html() { return this.htmlValue; }
  set html(value) {
    if (this.disposedFlag) throw new Error("Webview is disposed");
    this.htmlValue = String(value);
    toHost({ type: "__panelHtml", panel: this.id, html: this.htmlValue });
  }
  get options() {
    if (this.disposedFlag) throw new Error("Webview is disposed");
    return this.optionsValue;
  }
  set options(value) { this.optionsValue = value || {}; }
  asWebviewUri(uri) { return uri; }
  onDidReceiveMessage(listener, thisArg, disposables) { return this.received.event(listener, thisArg, disposables); }
  postMessage(message) {
    if (this.disposedFlag) return Promise.resolve(false);
    toHost({ type: "__panelPost", panel: this.id, message: message === undefined ? null : JSON.parse(JSON.stringify(message)) });
    return Promise.resolve(true);
  }
}

class HostPanel {
  constructor(id, viewType, title, kind, options) {
    this.id = id;
    this.viewType = viewType;
    this.titleValue = title;
    this.kind = kind;
    this.options = options || {};
    this.webviewObject = new HostWebview(id, kind);
    this.received = this.webviewObject.received;
    this.visible = true;
    this.active = true;
    this.viewColumn = ViewColumn.One;
    this.disposeEmitter = new EventEmitter();
    this.viewStateEmitter = new EventEmitter();
    this.visibilityEmitter = new EventEmitter();
    this.isDisposed = false;
    panels.set(id, this);
  }
  get webview() {
    if (this.isDisposed && this.kind !== "view") throw new Error("Webview is disposed");
    return this.webviewObject;
  }
  get title() { return this.titleValue; }
  set title(value) {
    this.titleValue = String(value);
    if (!this.isDisposed) toHost({ type: "__panelTitle", panel: this.id, title: this.titleValue });
  }
  get iconPath() { return this.icon; }
  set iconPath(value) { this.icon = value; }
  get onDidDispose() { return this.disposeEmitter.event; }
  get onDidChangeViewState() { return this.viewStateEmitter.event; }
  get onDidChangeVisibility() { return this.visibilityEmitter.event; }
  stateChanged() {
    this.viewStateEmitter.fire({ webviewPanel: this });
    this.visibilityEmitter.fire(undefined);
  }
  reveal(_column, preserveFocus) {
    if (!this.isDisposed) toHost({ type: "__panelReveal", panel: this.id, preserveFocus: Boolean(preserveFocus) });
  }
  show(preserveFocus) { this.reveal(undefined, preserveFocus); }
  /** The tab was closed in the IDE. */
  closed() {
    if (this.isDisposed) return;
    this.isDisposed = true;
    this.webviewObject.disposedFlag = true;
    panels.delete(this.id);
    this.disposeEmitter.fire(undefined);
    this.disposeEmitter.dispose();
  }
  dispose() {
    if (this.isDisposed) return;
    toHost({ type: "__panelDispose", panel: this.id });
    this.closed();
  }
}

function createPanel(viewType, title, showOptions, options, kind = "panel", extra = {}) {
  const id = `p${nextPanel++}`;
  const panel = new HostPanel(id, viewType, title, kind, options);
  const preserveFocus = typeof showOptions === "object" && showOptions !== null ? Boolean(showOptions.preserveFocus) : false;
  if (kind === "panel") toHost({ type: "__panelCreate", panel: id, viewType, title: String(title), preserveFocus, ...extra });
  return panel;
}

const viewProviders = new Map();
const customEditors = new Map();

/** The IDE shows a webview view (the Tools sidebar): the extension's provider fills it. */
onHostEvent("__resolveView", async message => {
  const entry = viewProviders.get(message.viewId);
  if (!entry) return;
  const view = createPanel(message.viewId, entry.title || "", undefined, entry.options?.webviewOptions, "view");
  view.id = message.panel;
  panels.delete(view.webviewObject.id);
  view.webviewObject.id = message.panel;
  panels.set(message.panel, view);
  view.description = undefined;
  view.badge = undefined;
  try {
    await entry.provider.resolveWebviewView(view, { state: undefined }, NONE_TOKEN);
  } catch (error) {
    console.error(`DevSnip Pro: the ${message.viewId} view could not be shown.`, error);
  }
});

/** Open custom documents, by canonical path and view type. */
const customDocuments = new Map();

/** The IDE opens a file in a DevSnip Pro custom editor (CSV & Excel viewer). */
onHostEvent("__openCustomEditor", async message => {
  const entry = customEditors.get(message.viewType);
  if (!entry) {
    toHost({ type: "__customEditorFailed", panel: message.panel, error: `No editor for ${message.viewType}.` });
    return;
  }
  const uri = Uri.file(message.path);
  const panel = new HostPanel(message.panel, message.viewType, posix.basename(uri.path), "custom", entry.options?.webviewOptions);
  try {
    const key = `${message.viewType}|${canonicalPath(message.path)}`;
    let record = customDocuments.get(key);
    if (!record) {
      const document = await entry.provider.openCustomDocument(uri, { backupId: message.backupId || undefined, untitledDocumentData: undefined }, NONE_TOKEN);
      record = { document, panels: new Set() };
      customDocuments.set(key, record);
    }
    record.panels.add(panel.id);
    panel.document = record.document;
    panel.disposeEmitter.event(() => {
      record.panels.delete(panel.id);
      if (!record.panels.size) {
        customDocuments.delete(key);
        try { record.document.dispose?.(); } catch (error) { console.error(error); }
      }
    });
    await entry.provider.resolveCustomEditor(record.document, panel, NONE_TOKEN);
  } catch (error) {
    toHost({ type: "__customEditorFailed", panel: message.panel, error: error instanceof Error ? error.message : String(error) });
  }
});

function customRecordFor(panelId) {
  const panel = panels.get(panelId);
  return panel?.document ? { panel, document: panel.document, provider: customEditors.get(panel.viewType)?.provider } : undefined;
}

/** Save, Save As, Revert from the IDE for a custom editor; the answer says whether it worked. */
onHostEvent("__customCommand", async message => {
  const record = customRecordFor(message.panel);
  const reply = (ok, error) => toHost({ type: "__customResult", id: message.id, ok, error });
  if (!record?.provider) return reply(false, "The editor is closed.");
  try {
    if (message.command === "save") await record.provider.saveCustomDocument(record.document, NONE_TOKEN);
    else if (message.command === "saveAs") await record.provider.saveCustomDocumentAs(record.document, Uri.file(message.path), NONE_TOKEN);
    else if (message.command === "revert") await record.provider.revertCustomDocument(record.document, NONE_TOKEN);
    else if (message.command === "backup") {
      const backup = await record.provider.backupCustomDocument(record.document, { destination: Uri.file(message.path) }, NONE_TOKEN);
      return toHost({ type: "__customResult", id: message.id, ok: true, backupId: backup?.id ?? null });
    }
    reply(true);
  } catch (error) {
    reply(false, error instanceof Error ? error.message : String(error));
  }
});

/** Undo and redo from the IDE's undo stack, for edits a custom editor reported. */
const customEdits = new Map();
let nextCustomEdit = 1;

onHostEvent("__customUndo", async message => {
  const edit = customEdits.get(message.edit);
  if (!edit) return;
  try {
    if (message.redo) await edit.redo();
    else await edit.undo();
  } catch (error) {
    console.error("DevSnip Pro: undo failed.", error);
  }
});

function panelsOfDocument(document) {
  return [...panels.values()].filter(p => p.document === document).map(p => p.id);
}

// ------------------------------------------------------------ messages

function splitMessageArgs(rest) {
  let options = {};
  let items = rest;
  if (rest.length && rest[0] && typeof rest[0] === "object" && !Array.isArray(rest[0]) && !("title" in rest[0])) {
    options = rest[0];
    items = rest.slice(1);
  }
  if (items.length === 1 && Array.isArray(items[0])) items = items[0];
  return { options, items: items.filter(item => item !== undefined && item !== null) };
}

function message(kind) {
  return async (text, ...rest) => {
    const { options, items } = splitMessageArgs(rest);
    const titles = items.map(item => (typeof item === "string" ? item : String(item.title)));
    const index = await rpc("ui.message", {
      kind,
      message: String(text),
      detail: options.detail ? String(options.detail) : null,
      modal: options.modal === true,
      items: titles
    });
    return typeof index === "number" && index >= 0 ? items[index] : undefined;
  };
}

function quickPickWire(items) {
  return items.map(item => (typeof item === "string"
    ? { label: item }
    : { label: String(item.label ?? ""), description: item.description ? String(item.description) : null, detail: item.detail ? String(item.detail) : null, picked: Boolean(item.picked), separator: item.kind === QuickPickItemKind.Separator }));
}

const quickPicks = new Map();
let nextQuickPick = 1;

onHostEvent("__quickPick", message => quickPicks.get(message.id)?.(message));

function createQuickPick() {
  const id = nextQuickPick++;
  const accept = new EventEmitter();
  const active = new EventEmitter();
  const selection = new EventEmitter();
  const hide = new EventEmitter();
  const value = new EventEmitter();
  let open = false;
  const pick = {
    items: [],
    activeItems: [],
    selectedItems: [],
    title: undefined,
    placeholder: undefined,
    value: "",
    busy: false,
    enabled: true,
    canSelectMany: false,
    matchOnDescription: false,
    matchOnDetail: false,
    ignoreFocusOut: false,
    buttons: [],
    onDidAccept: accept.event,
    onDidChangeActive: active.event,
    onDidChangeSelection: selection.event,
    onDidHide: hide.event,
    onDidChangeValue: value.event,
    onDidTriggerButton: never,
    onDidTriggerItemButton: never,
    show() {
      if (open) return;
      open = true;
      quickPicks.set(id, event => {
        if (event.event === "active") {
          pick.activeItems = (event.indices || []).map(i => pick.items[i]).filter(Boolean);
          active.fire(pick.activeItems);
        } else if (event.event === "accept") {
          pick.selectedItems = (event.indices || []).map(i => pick.items[i]).filter(Boolean);
          selection.fire(pick.selectedItems);
          accept.fire(undefined);
        } else if (event.event === "hide") {
          open = false;
          quickPicks.delete(id);
          hide.fire(undefined);
        }
      });
      const activeIndex = pick.activeItems.length ? pick.items.indexOf(pick.activeItems[0]) : -1;
      void rpc("ui.quickPick", {
        id,
        live: true,
        items: quickPickWire(pick.items),
        title: pick.title ?? null,
        placeHolder: pick.placeholder ?? null,
        canPickMany: pick.canSelectMany,
        activeIndex
      }).catch(error => console.error("DevSnip Pro: quick pick", error));
    },
    hide() {
      if (!open) return;
      void rpc("ui.quickPickHide", { id }).catch(noop);
    },
    dispose() {
      pick.hide();
      quickPicks.delete(id);
    }
  };
  return pick;
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
    logLevel: 2,
    onDidChangeLogLevel: never,
    info: write("info"),
    warn: write("warning"),
    error: value => write("error")(value instanceof Error ? value.stack || value.message : value),
    debug: write("debug"),
    trace: write("trace"),
    appendLine: write("info"),
    append: write("info"),
    replace: value => { pending = []; void rpc("log.clear", { channel: name }).catch(noop); write("info")(value); },
    show() {
      if (timer) clearTimeout(timer);
      void flush().then(() => rpc("log.show", { channel: name })).catch(noop);
    },
    clear() { pending = []; void rpc("log.clear", { channel: name }).catch(noop); },
    hide: noop,
    dispose: noop
  };
  outputChannels.set(name, channel);
  return channel;
}

const terminals = new Set();
let nextTerminal = 1;
const terminalClosed = new EventEmitter();
const terminalOpened = new EventEmitter();

onHostEvent("__terminalClosed", message => {
  for (const terminal of terminals) {
    if (terminal.id !== message.id) continue;
    terminals.delete(terminal);
    terminal.exitStatus = { code: message.code ?? undefined, reason: 0 };
    terminalClosed.fire(terminal);
  }
});

function createTerminal(nameOrOptions, shellPath, shellArgs) {
  const options = typeof nameOrOptions === "object" && nameOrOptions !== null ? nameOrOptions : { name: nameOrOptions, shellPath, shellArgs };
  const id = nextTerminal++;
  const cwd = options.cwd ? (typeof options.cwd === "string" ? options.cwd : options.cwd.fsPath) : null;
  let started = false;
  const start = () => {
    if (started) return;
    started = true;
    void rpc("terminal.create", {
      id,
      name: options.name ?? "DevSnip Pro",
      cwd,
      shellPath: options.shellPath ?? null,
      shellArgs: Array.isArray(options.shellArgs) ? options.shellArgs.map(String) : null,
      env: options.env ?? null
    }).catch(error => console.error("DevSnip Pro: terminal", error));
  };
  const terminal = {
    id,
    name: options.name ?? "DevSnip Pro",
    processId: Promise.resolve(id),
    creationOptions: options,
    exitStatus: undefined,
    shellIntegration: undefined,
    state: { isInteractedWith: false },
    show(preserveFocus) { start(); void rpc("terminal.show", { id, preserveFocus: Boolean(preserveFocus) }).catch(noop); },
    hide: noop,
    sendText(text, addNewLine = true) {
      start();
      void rpc("terminal.send", { id, text: String(text), newline: addNewLine !== false }).catch(error => console.error("DevSnip Pro: terminal", error));
    },
    dispose() {
      terminals.delete(terminal);
      if (started) void rpc("terminal.dispose", { id }).catch(noop);
    }
  };
  terminals.add(terminal);
  start();
  terminalOpened.fire(terminal);
  return terminal;
}

const progressCancels = new Map();
onHostEvent("__progressCancel", message => progressCancels.get(message.id)?.cancel());

async function withProgress(options, task) {
  const source = new CancellationTokenSource();
  let id = null;
  try {
    id = await rpc("progress.start", { title: options.title ?? "DevSnip Pro", cancellable: Boolean(options.cancellable), location: options.location ?? ProgressLocation.Notification });
    if (id !== null) progressCancels.set(id, source);
  } catch { /* progress is cosmetic */ }
  let last = 0;
  let done = 0;
  const progress = {
    report(value) {
      if (id === null || !value) return;
      if (typeof value.increment === "number") done = Math.min(100, done + value.increment);
      if (Date.now() - last < 120 && !value.increment) return;
      last = Date.now();
      void rpc("progress.report", { id, message: value.message ? String(value.message) : null, fraction: done ? done / 100 : null }).catch(noop);
    }
  };
  try {
    return await task(progress, source.token);
  } finally {
    if (id !== null) {
      progressCancels.delete(id);
      void rpc("progress.end", { id }).catch(noop);
    }
    source.dispose();
  }
}

function statusBarItem(alignmentOrId, priorityOrAlignment) {
  const id = `s${nextPanel++}`;
  const item = {
    id,
    alignment: typeof alignmentOrId === "number" ? alignmentOrId : priorityOrAlignment,
    text: "",
    tooltip: undefined,
    command: undefined,
    color: undefined,
    backgroundColor: undefined,
    name: undefined,
    show() { void rpc("statusBar.set", { id, text: String(item.text || ""), tooltip: item.tooltip ? String(item.tooltip) : null, command: typeof item.command === "string" ? item.command : item.command?.command ?? null }).catch(noop); },
    hide() { void rpc("statusBar.set", { id, text: null }).catch(noop); },
    dispose() { item.hide(); }
  };
  return item;
}

onHostEvent("__statusBarClick", message => {
  if (message.command) void commands.executeCommand(message.command).catch(error => console.error(error));
});

function textEditorOf(info, document) {
  const editor = new HostTextEditor({ ...info, text: info.text ?? document?.text, path: info.path ?? (document && !document.isUntitled ? document.uri.fsPath : undefined) });
  if (document) editor.document = document;
  activeEditor = editor;
  return editor;
}

export const window = {
  createWebviewPanel: (viewType, title, showOptions, options) => createPanel(viewType, title, showOptions, options),
  registerWebviewViewProvider(viewId, provider, options) {
    viewProviders.set(viewId, { provider, options });
    toHost({ type: "__viewProvider", viewId });
    return new Disposable(() => viewProviders.delete(viewId));
  },
  registerCustomEditorProvider(viewType, provider, options) {
    customEditors.set(viewType, { provider, options });
    // Edits reported by the provider go on the IDE's undo stack for that editor.
    const subscription = provider.onDidChangeCustomDocument?.(event => {
      const panelIds = panelsOfDocument(event.document);
      if (event.undo) {
        const editId = nextCustomEdit++;
        customEdits.set(editId, { undo: event.undo, redo: event.redo });
        if (customEdits.size > 500) customEdits.delete(customEdits.keys().next().value);
        toHost({ type: "__customEdit", panels: panelIds, edit: editId, label: event.label ?? "Edit" });
      } else {
        toHost({ type: "__customDirty", panels: panelIds });
      }
    });
    return new Disposable(() => {
      customEditors.delete(viewType);
      subscription?.dispose?.();
    });
  },
  registerUriHandler: () => new Disposable(noop),
  registerTreeDataProvider: () => new Disposable(noop),
  showInformationMessage: message("info"),
  showWarningMessage: message("warning"),
  showErrorMessage: message("error"),
  async showQuickPick(itemsOrPromise, options = {}, token) {
    const items = (await itemsOrPromise) || [];
    if (!items.length) return undefined;
    const answer = await rpc("ui.quickPick", {
      items: quickPickWire(items),
      title: options.title ?? null,
      placeHolder: options.placeHolder ?? null,
      canPickMany: options.canPickMany === true,
      matchOnDescription: options.matchOnDescription === true,
      matchOnDetail: options.matchOnDetail === true
    });
    if (token?.isCancellationRequested) return undefined;
    if (answer === null || answer === undefined) return undefined;
    if (options.canPickMany) return (answer || []).map(i => items[i]).filter(i => i !== undefined);
    return items[answer];
  },
  createQuickPick,
  async showInputBox(options = {}) {
    let value = options.value ?? "";
    let problem = null;
    for (;;) {
      const answer = await rpc("ui.inputBox", {
        title: options.title ?? null,
        prompt: options.prompt ?? null,
        placeHolder: options.placeHolder ?? null,
        password: options.password === true,
        value,
        problem
      });
      if (answer === null || answer === undefined) return undefined;
      const check = options.validateInput ? await options.validateInput(answer) : undefined;
      problem = check && typeof check === "object" ? check.message : check;
      if (!problem) return answer;
      value = answer;
    }
  },
  async showOpenDialog(options = {}) {
    const result = await rpc("ui.openDialog", {
      title: options.title ?? options.openLabel ?? null,
      canSelectFiles: options.canSelectFiles !== false,
      canSelectFolders: options.canSelectFolders === true,
      canSelectMany: options.canSelectMany === true,
      defaultPath: options.defaultUri ? options.defaultUri.fsPath : null,
      extensions: Object.values(options.filters ?? {}).flat().map(String)
    });
    return result?.length ? result.map(p => Uri.file(p)) : undefined;
  },
  async showSaveDialog(options = {}) {
    const chosen = await rpc("ui.saveDialog", {
      title: options.title ?? options.saveLabel ?? null,
      defaultPath: options.defaultUri ? options.defaultUri.fsPath : null,
      extensions: Object.values(options.filters ?? {}).flat().map(String)
    });
    return chosen ? Uri.file(chosen) : undefined;
  },
  async showTextDocument(target, columnOrOptions, preserveFocus) {
    const options = typeof columnOrOptions === "object" && columnOrOptions !== null ? columnOrOptions : { viewColumn: columnOrOptions, preserveFocus };
    let document = target instanceof HostTextDocument ? target : target?.document instanceof HostTextDocument ? target.document : undefined;
    const uri = document?.uri ?? (target instanceof Uri ? target : uriFromWire(target));
    const selection = options.selection ? toWireRange(options.selection) : null;
    let info;
    if (document?.isUntitled) {
      const untitled = untitledDocs.get(document.id) ?? {};
      info = await rpc("doc.showUntitled", { id: document.id, name: untitled.name ?? document.fileName, text: document.getText(), languageId: document.languageId, preview: options.preview !== false, beside: options.viewColumn === ViewColumn.Beside });
    } else {
      info = await rpc("doc.show", { path: uri.fsPath, selection, preview: options.preview !== false, preserveFocus: Boolean(options.preserveFocus), beside: options.viewColumn === ViewColumn.Beside });
    }
    if (!document && info) document = documentFrom({ ...info, path: info.path || uri.fsPath });
    return textEditorOf(info || {}, document);
  },
  get activeTextEditor() { return activeEditor; },
  get visibleTextEditors() {
    const out = visibleEditorInfos.map(info => (activeEditor && activeEditor.id === info.id ? activeEditor : new HostTextEditor(info)));
    return out;
  },
  get activeColorTheme() { return { kind: host().themeKind === "light" ? 1 : 2 }; },
  get terminals() { return [...terminals]; },
  get state() { return { focused: true, active: true }; },
  get tabGroups() {
    return { all: [], activeTabGroup: { activeTab: undefined, tabs: [], isActive: true }, onDidChangeTabs: never, onDidChangeTabGroups: never, close: async () => true };
  },
  onDidChangeActiveTextEditor: activeEditorChanged.event,
  onDidChangeVisibleTextEditors: never,
  onDidChangeTextEditorSelection: never,
  onDidChangeActiveColorTheme: never,
  onDidChangeWindowState: never,
  onDidCloseTerminal: terminalClosed.event,
  onDidOpenTerminal: terminalOpened.event,
  onDidChangeTerminalShellIntegration: never,
  onDidStartTerminalShellExecution: never,
  onDidEndTerminalShellExecution: never,
  createTerminal,
  withProgress,
  setStatusBarMessage(text, timeoutOrThenable) {
    void rpc("statusBar.set", { id: "message", text: String(text) }).catch(noop);
    const clear = () => void rpc("statusBar.set", { id: "message", text: null }).catch(noop);
    if (typeof timeoutOrThenable === "number") setTimeout(clear, timeoutOrThenable);
    else if (timeoutOrThenable?.then) timeoutOrThenable.then(clear, clear);
    return new Disposable(clear);
  },
  createOutputChannel: name => outputChannel(name),
  createStatusBarItem: statusBarItem,
  createTextEditorDecorationType: () => ({ key: "devsnip", dispose: noop })
};

// ------------------------------------------------------------- commands

const registered = new Map();

/** VS Code's own commands the extension runs, done the IDE way. */
const BUILT_IN = {
  setContext: () => undefined,
  // Nothing the extension reloads for needs it in the IDE (snippets are read as they change).
  "workbench.action.reloadWindow": () => undefined,
  "workbench.action.files.openFolder": () => rpc("ide.command", { id: "workbench.action.files.openFolder", args: [] }),
  "vscode.open": target => window.showTextDocument(uriFromWire(target)),
  "workbench.action.openSettings": query => rpc("ide.openSettings", { query: String(query ?? "") }),
  "workbench.action.openWalkthrough": () => commands.executeCommand("sayaib.hue-console.__walkthrough"),
  "vscode.diff": async (left, right, title) => {
    const text = async uri => {
      const provider = providers.get(uri.scheme);
      if (provider) return String((await provider.provideTextDocumentContent(uri, NONE_TOKEN)) ?? "");
      return null;
    };
    return rpc("ide.diff", {
      leftPath: left.scheme === "file" ? left.fsPath : null,
      leftText: left.scheme === "file" ? null : await text(left),
      rightPath: right.scheme === "file" ? right.fsPath : null,
      rightText: right.scheme === "file" ? null : await text(right),
      title: String(title ?? "")
    });
  },
  "revealInExplorer": target => rpc("ide.reveal", { path: uriFromWire(target).fsPath }),
  "revealFileInOS": target => rpc("ide.revealInOs", { path: uriFromWire(target).fsPath }),
  "workbench.view.extension.myActivityBar": () => rpc("ide.showToolWindow", {}),
  "workbench.view.explorer": () => rpc("ide.command", { id: "workbench.view.explorer", args: [] }),
  "editor.action.formatDocument": () => rpc("ide.command", { id: "editor.action.formatDocument", args: [] })
};

function commandArgsToWire(args) {
  return args.map(arg => (arg instanceof Uri ? { $uri: arg.toString(), fsPath: arg.fsPath } : arg === undefined ? null : arg));
}

export const commands = {
  registerCommand(id, handler, thisArg) {
    registered.set(id, thisArg ? handler.bind(thisArg) : handler);
    return new Disposable(() => {
      if (registered.get(id)) registered.delete(id);
    });
  },
  registerTextEditorCommand(id, handler, thisArg) {
    return commands.registerCommand(id, (...args) => {
      if (!activeEditor) return undefined;
      return (thisArg ? handler.bind(thisArg) : handler)(activeEditor, { replace: noop, insert: noop, delete: noop, setEndOfLine: noop }, ...args);
    });
  },
  async executeCommand(id, ...args) {
    const handler = registered.get(id);
    if (handler) return handler(...args);
    if (Object.prototype.hasOwnProperty.call(BUILT_IN, id)) return BUILT_IN[id](...args);
    // The commands the IDE runs itself (toolkit tools, scanners, the REST API Client, IDE actions).
    return rpc("ide.command", { id, args: commandArgsToWire(args) });
  },
  async getCommands() {
    return [...new Set([...registered.keys(), ...(host().nativeCommands || [])])];
  }
};

/** Commands registered in this host (the IDE asks which it can run here). */
export function registeredCommands() {
  return [...registered.keys()];
}

// ------------------------------------------------------------------ env

const telemetryChanged = new EventEmitter();
onHostEvent("__telemetry", message => {
  host().telemetry = Boolean(message.enabled);
  telemetryChanged.fire(Boolean(message.enabled));
});

export const env = {
  clipboard: {
    writeText: text => rpc("ide.copy", { text: String(text) }).then(noop),
    readText: () => rpc("ide.paste", {}).then(text => text ?? "")
  },
  openExternal: uri => rpc("ide.openExternal", { url: uri instanceof Uri ? uri.toString() : String(uri) }).then(() => true),
  asExternalUri: async uri => uri,
  get appName() { return host().ideName || "JetBrains IDE"; },
  get appRoot() { return host().extensionPath || "/devsnip-pro"; },
  get appHost() { return "desktop"; },
  get uriScheme() { return "jetbrains"; },
  get language() { return "en"; },
  get machineId() { return host().machineId || "someValue.machineId"; },
  get sessionId() { return host().sessionId || "session"; },
  get remoteName() { return undefined; },
  get shell() { return host().shell || ""; },
  get uiKind() { return UIKind.Desktop; },
  get isTelemetryEnabled() { return host().telemetry === true; },
  get isNewAppInstall() { return false; },
  onDidChangeTelemetryEnabled: telemetryChanged.event,
  onDidChangeShell: never,
  logLevel: 2,
  onDidChangeLogLevel: never
};

export const languages = {
  registerCompletionItemProvider: () => new Disposable(noop),
  registerCodeActionsProvider: () => new Disposable(noop),
  registerHoverProvider: () => new Disposable(noop),
  createDiagnosticCollection: () => ({ set: noop, delete: noop, clear: noop, dispose: noop }),
  getLanguages: async () => [],
  setTextDocumentLanguage: async document => document
};

const EXTENSION_ID = "sayaib.hue-console";

function extensionObject() {
  const info = host();
  return {
    id: EXTENSION_ID,
    extensionPath: info.extensionPath,
    extensionUri: Uri.file(info.extensionPath || "/devsnip-pro"),
    packageJSON: { ...manifest, version: info.version || manifest.version },
    isActive: true,
    exports: undefined,
    extensionKind: 1,
    activate: async () => undefined
  };
}

export const extensions = {
  getExtension: id => (id === EXTENSION_ID ? extensionObject() : undefined),
  get all() { return [extensionObject()]; },
  onDidChange: never
};

export const version = host().ideVersion || "JetBrains";

// -------------------------------------------------------------- context

const globalStateChanged = new EventEmitter();

/** globalState is shared by every project's host; the IDE pushes changes made by the others. */
onHostEvent("__globalState", message => {
  const state = host().globalState || (host().globalState = {});
  if (message.value === null || message.value === undefined) delete state[message.key];
  else state[message.key] = message.value;
  globalStateChanged.fire(message.key);
});

function memento(kind) {
  const read = () => {
    const info = host();
    const key = kind === "global" ? "globalState" : "workspaceState";
    return info[key] || (info[key] = {});
  };
  return {
    get(key, fallback) {
      const state = read();
      return Object.prototype.hasOwnProperty.call(state, key) ? structuredClone(state[key]) : fallback;
    },
    async update(key, value) {
      const state = read();
      if (value === undefined) delete state[key];
      else state[key] = structuredClone(value);
      await rpc(kind === "global" ? "globalState.update" : "workspaceState.update", { key, value: value === undefined ? null : value });
    },
    keys: () => Object.keys(read()),
    setKeysForSync: noop
  };
}

const secretsChanged = new EventEmitter();

let contextObject;

/** The ExtensionContext the extension's activate() gets. */
export function extensionContext() {
  if (contextObject) return contextObject;
  const info = host();
  const extensionUri = Uri.file(info.extensionPath || "/devsnip-pro");
  contextObject = {
    subscriptions: [],
    extensionPath: extensionUri.fsPath,
    extensionUri,
    extension: extensionObject(),
    extensionMode: ExtensionMode.Production,
    globalStorageUri: Uri.file(info.globalStorage || "/devsnip-pro-storage"),
    globalStoragePath: info.globalStorage,
    storageUri: info.workspaceStorage ? Uri.file(info.workspaceStorage) : undefined,
    storagePath: info.workspaceStorage,
    logUri: Uri.file(info.logPath || info.globalStorage || "/devsnip-pro-logs"),
    logPath: info.logPath || info.globalStorage,
    globalState: memento("global"),
    workspaceState: memento("workspace"),
    secrets: {
      get: key => rpc("secrets.get", { key }).then(value => value ?? undefined),
      store: (key, value) => rpc("secrets.store", { key, value: String(value) }).then(() => secretsChanged.fire({ key })),
      delete: key => rpc("secrets.store", { key, value: null }).then(() => secretsChanged.fire({ key })),
      onDidChange: secretsChanged.event
    },
    environmentVariableCollection: { replace: noop, append: noop, prepend: noop, get: () => undefined, forEach: noop, delete: noop, clear: noop, persistent: false },
    asAbsolutePath: relative => posix.join(extensionUri.path, relative)
  };
  return contextObject;
}

export const onDidChangeGlobalState = globalStateChanged.event;

export default {
  Disposable, EventEmitter, CancellationError, CancellationTokenSource, Uri, RelativePattern, Position, Range, Selection,
  SnippetString, ThemeColor, ThemeIcon, MarkdownString, WorkspaceEdit, TextEdit, FileSystemError,
  TabInputText, TabInputWebview, TabInputCustom, TabInputTextDiff,
  ViewColumn, ProgressLocation, FileType, StatusBarAlignment, ExtensionMode, EndOfLine, ConfigurationTarget, TextEditorRevealType,
  QuickPickItemKind, UIKind, OverviewRulerLane, DiagnosticSeverity,
  workspace, window, commands, env, languages, extensions, version
};
