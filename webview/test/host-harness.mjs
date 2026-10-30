/*
 * A stand-in IDE for DevSnip Pro's extension host (host-runtime.js): loads
 * the bundle in a fresh browser-like context and answers its requests the way
 * EngineHost.kt does, on a temporary workspace. Used by test/host.mjs.
 */
import fs from "fs";
import os from "os";
import path from "path";
import vm from "vm";
import { fileURLToPath } from "url";

const here = path.dirname(fileURLToPath(import.meta.url));
export const bundlePath = path.resolve(here, "../../src/main/resources/webview/host-runtime.js");
export const extensionSource = path.resolve(here, "../../src/main/resources/webview/extension");

const native = JSON.parse(fs.readFileSync(path.resolve(here, "../../src/main/resources/webview/tool-commands.json"), "utf8")).map(c => c.command)
  .concat(["sayaib.hue-console.openGUI", "sayaib.hue-console.osvScanner", "sayaib.hue-console.osvScanWorkspace", "sayaib.hue-console.osvScanFile", "sayaib.hue-console.scaScanner", "sayaib.hue-console.scaScanWorkspace"]);

/** Copies the extension folder as the IDE unpacks it. */
function unpackExtension(target) {
  const files = JSON.parse(fs.readFileSync(path.join(extensionSource, "files.json"), "utf8"));
  for (const file of files) {
    const to = path.join(target, file);
    fs.mkdirSync(path.dirname(to), { recursive: true });
    fs.copyFileSync(path.join(extensionSource, file), to);
  }
}

function lineOffsets(text) {
  const starts = [0];
  for (let i = 0; i < text.length; i++) if (text[i] === "\n") starts.push(i + 1);
  return starts;
}

function offsetOf(text, pos) {
  const starts = lineOffsets(text);
  if (pos.line >= starts.length) return text.length;
  const end = pos.line + 1 < starts.length ? starts[pos.line + 1] - 1 : text.length;
  return Math.min(starts[pos.line] + pos.character, end);
}

function applyEdits(text, edits) {
  const sorted = edits.map(e => ({ from: offsetOf(text, e.range.start), to: offsetOf(text, e.range.end), text: e.text })).sort((a, b) => b.from - a.from);
  for (const e of sorted) text = text.slice(0, e.from) + e.text + text.slice(e.to);
  return text;
}

function globToRegex(glob) {
  let out = "";
  let braces = 0;
  for (let i = 0; i < glob.length; i++) {
    const c = glob[i];
    if (c === "*" && glob[i + 1] === "*") { if (glob[i + 2] === "/") { out += "(?:[^/]*/)*"; i += 2; } else { out += ".*"; i++; } }
    else if (c === "*") out += "[^/]*";
    else if (c === "?") out += "[^/]";
    else if (c === "{") { braces++; out += "(?:"; }
    else if (c === "}" && braces) { braces--; out += ")"; }
    else if (c === "," && braces) out += "|";
    else out += c.replace(/[.+^$()|[\]\\]/g, "\\$&");
  }
  return new RegExp(`^${out}$`);
}

/** One fake IDE with one project, ready to start the host. */
export function createIde(options = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "devsnip-host-"));
  const workspace = path.join(root, "project");
  const extension = path.join(root, "extension");
  const storage = path.join(root, "storage");
  fs.mkdirSync(workspace, { recursive: true });
  fs.mkdirSync(storage, { recursive: true });
  unpackExtension(extension);
  for (const [file, text] of Object.entries(options.files || {})) {
    const p = path.join(workspace, file);
    fs.mkdirSync(path.dirname(p), { recursive: true });
    fs.writeFileSync(p, text);
  }

  const ide = {
    root, workspace, extension, storage,
    globalState: options.globalState || {},
    workspaceState: {},
    settings: options.settings || {},
    messages: [],
    rpcs: [],
    panels: new Map(),
    answers: { message: [], quickPick: [], inputBox: [], openDialog: [], saveDialog: [] },
    natives: [],
    usage: [],
    logs: {},
    edits: [],
    docs: new Map(),
    ready: null,
    theme: null,
    points: [],
    terminals: [],
    spawned: [],
    errors: []
  };

  const readDoc = p => (ide.docs.has(p) ? ide.docs.get(p) : fs.existsSync(p) ? fs.readFileSync(p, "utf8") : null);

  function stat(p) {
    if (!fs.existsSync(p)) return null;
    const s = fs.statSync(p);
    let executable = false;
    try { fs.accessSync(p, fs.constants.X_OK); executable = s.isFile(); } catch { executable = false; }
    return { type: s.isDirectory() ? 2 : 1, size: s.size, mtime: Math.floor(s.mtimeMs), ctime: Math.floor(s.ctimeMs), executable };
  }

  /** The requests EngineHost.kt answers (sync ones too). */
  function answer(method, p) {
    // A test can serve some requests itself (db-servers.mjs: the IDE's real database bridge).
    if (options.rpc && options.rpc.handles(method)) return options.rpc.call(method, p);
    switch (method) {
      case "globalState.update": if (p.value === null) delete ide.globalState[p.key]; else ide.globalState[p.key] = p.value; return null;
      case "workspaceState.update": if (p.value === null) delete ide.workspaceState[p.key]; else ide.workspaceState[p.key] = p.value; return null;
      case "config.update": if (p.value === null) delete ide.settings[p.key]; else ide.settings[p.key] = p.value; return null;
      case "fs.stat": return stat(p.path);
      case "fs.readBytes": {
        if (!fs.existsSync(p.path)) return null;
        let bytes = fs.readFileSync(p.path);
        if (p.offset !== undefined && p.length !== undefined) bytes = bytes.subarray(p.offset, p.offset + p.length);
        return bytes.toString("base64");
      }
      case "fs.writeBytes": fs.mkdirSync(path.dirname(p.path), { recursive: true }); if (p.append) fs.appendFileSync(p.path, Buffer.from(p.base64, "base64")); else fs.writeFileSync(p.path, Buffer.from(p.base64, "base64")); return null;
      case "fs.mkdir": fs.mkdirSync(p.path, { recursive: true }); return null;
      case "fs.delete": fs.rmSync(p.path, { recursive: Boolean(p.recursive), force: false }); return null;
      case "fs.rename": fs.renameSync(p.from, p.to); return null;
      case "fs.copy": fs.copyFileSync(p.from, p.to); return null;
      case "fs.readdir": {
        if (!fs.existsSync(p.path)) { const e = new Error("ENOENT: no such directory"); e.code = "ENOENT"; throw e; }
        return fs.readdirSync(p.path, { withFileTypes: true }).map(e => ({ name: e.name, type: e.isDirectory() ? 2 : 1 }));
      }
      case "fs.watch": case "fs.unwatch": case "events.documents": return null;
      case "ws.findFiles": {
        const base = p.base || workspace;
        const include = globToRegex(p.include);
        const exclude = p.exclude ? globToRegex(p.exclude) : null;
        const out = [];
        const walk = dir => {
          for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
            const full = path.join(dir, entry.name);
            const rel = path.relative(base, full).split(path.sep).join("/");
            if (entry.isDirectory()) { if (!exclude || !exclude.test(rel + "/x")) walk(full); }
            else if (include.test(rel) && (!exclude || !exclude.test(rel)) && out.length < p.max) out.push(full);
          }
        };
        if (fs.existsSync(base)) walk(base);
        return out;
      }
      case "doc.open": {
        const text = readDoc(p.path);
        if (text === null) return null;
        return { path: p.path, text, languageId: languageOf(p.path), version: 1, isDirty: ide.docs.has(p.path) };
      }
      case "doc.show": { const text = readDoc(p.path); ide.shown = p.path; ide.shownSelection = p.selection; return text === null ? null : { id: `e:${p.path}`, path: p.path, text, languageId: languageOf(p.path), version: 1, selections: [] }; }
      case "doc.showUntitled": ide.untitled = p; return { id: `u:${p.id}`, name: p.name, text: p.text, languageId: p.languageId, version: 1, selections: [] };
      case "doc.save": if (ide.docs.has(p.path)) { fs.writeFileSync(p.path, ide.docs.get(p.path)); ide.docs.delete(p.path); } return null;
      case "doc.saveAll": for (const [file, text] of ide.docs) fs.writeFileSync(file, text); ide.docs.clear(); return null;
      case "editor.edit": {
        const text = readDoc(p.path) ?? "";
        const next = applyEdits(text, p.edits);
        ide.docs.set(p.path, next);
        ide.edits.push(p);
        return { ok: true, text: next, version: (p.version || 1) + 1 };
      }
      case "editor.insertSnippet": {
        const text = readDoc(p.path) ?? "";
        const plain = p.snippet.replace(/\$\{\d+:([^}]*)\}/g, "$1").replace(/\$\{\d+\|([^,|]*)[^}]*\}/g, "$1").replace(/\$\d+/g, "");
        const next = applyEdits(text, p.ranges.map(range => ({ range, text: plain })));
        ide.docs.set(p.path, next);
        ide.snippets = (ide.snippets || []).concat(p.snippet);
        return { ok: true, text: next };
      }
      case "editor.select": return null;
      case "editor.reveal": ide.revealed = p.range; return null;
      case "workspace.applyEdit": {
        for (const file of p.edits) {
          const text = readDoc(file.path) ?? "";
          ide.docs.set(file.path, applyEdits(text, file.edits));
        }
        for (const op of p.files || []) {
          if (op.kind === "create") { fs.mkdirSync(path.dirname(op.path), { recursive: true }); if (!fs.existsSync(op.path) || op.overwrite) fs.writeFileSync(op.path, op.contents ? Buffer.from(op.contents, "base64") : ""); }
          else if (op.kind === "delete") fs.rmSync(op.path, { recursive: op.recursive, force: true });
        }
        ide.applied = (ide.applied || []).concat(p);
        return { ok: true };
      }
      case "ui.message": {
        ide.messages.push(p);
        const next = ide.answers.message.shift();
        if (typeof next === "function") return next(p);
        if (typeof next === "string") return p.items.indexOf(next);
        return next ?? null;
      }
      case "ui.quickPick": {
        ide.lastQuickPick = p;
        const next = ide.answers.quickPick.shift();
        return typeof next === "function" ? next(p) : next ?? null;
      }
      case "ui.quickPickHide": return null;
      case "ui.inputBox": { ide.lastInput = p; const next = ide.answers.inputBox.shift(); return typeof next === "function" ? next(p) : next ?? null; }
      case "ui.openDialog": return ide.answers.openDialog.shift() ?? null;
      case "ui.saveDialog": return ide.answers.saveDialog.shift() ?? null;
      case "ide.native": ide.natives.push(p); return null;
      case "ide.command": ide.commands = (ide.commands || []).concat(p); return null;
      case "ide.copy": ide.clipboard = p.text; return null;
      case "ide.paste": return ide.clipboard ?? "";
      case "ide.openExternal": ide.opened = (ide.opened || []).concat(p.url); return null;
      case "ide.openSettings": case "ide.notify": case "ide.diff": case "ide.reveal": case "ide.showToolWindow": return null;
      case "log.append": (ide.logs[p.channel] ??= []).push(...p.lines.map(l => l.text)); return null;
      case "log.show": case "log.clear": return null;
      case "progress.start": return 1;
      case "progress.report": case "progress.end": return null;
      case "statusBar.set": ide.status = p; return null;
      case "secrets.get": return ide.secrets?.[p.key] ?? null;
      case "secrets.store": ide.secrets = { ...(ide.secrets || {}), [p.key]: p.value }; return null;
      case "terminal.create": case "terminal.show": case "terminal.dispose": return null;
      case "terminal.send": ide.terminals.push(p); return null;
      case "process.spawn": {
        ide.spawned.push(p);
        const script = options.processes?.[path.basename(p.command)];
        setTimeout(() => {
          if (!script) return deliver({ type: "__process", id: p.id, event: "error", message: `spawn ${p.command} ENOENT`, code: "ENOENT" });
          const result = typeof script === "function" ? script(p) : script;
          if (result.stdout) deliver({ type: "__process", id: p.id, event: "stdout", data: result.stdout });
          if (result.stderr) deliver({ type: "__process", id: p.id, event: "stderr", data: result.stderr });
          deliver({ type: "__process", id: p.id, event: "exit", code: result.code ?? 0 });
        }, 1);
        return null;
      }
      case "process.kill": case "process.input": return null;
      case "http.raw": {
        const handler = options.http;
        if (!handler) { const e = new Error("getaddrinfo ENOTFOUND"); e.code = "ENOTFOUND"; throw e; }
        return handler(p);
      }
      case "tls.probe": { const e = new Error("connect ECONNREFUSED"); e.code = "ECONNREFUSED"; throw e; }
      case "http.cancel": return null;
      default: {
        const e = new Error(`Unknown request ${method}`);
        ide.errors.push(method);
        throw e;
      }
    }
  }

  let context;
  function deliver(message) {
    vm.runInContext(`__devsnipReceive(${JSON.stringify(message)})`, context);
  }

  function onMessage(message) {
    switch (message.type) {
      case "__rpc":
        Promise.resolve().then(() => answer(message.method, message.params)).then(
          result => setTimeout(() => deliver({ type: "__rpcResult", id: message.id, result: result ?? null }), 0),
          error => setTimeout(() => deliver({ type: "__rpcResult", id: message.id, error: error.message, errorData: { code: error.code } }), 0)
        );
        ide.rpcs.push(message.method);
        break;
      case "__ready": ide.ready = message; break;
      case "__panelCreate": ide.panels.set(message.panel, { ...message, html: "", posted: [] }); break;
      case "__panelHtml": { const p = ide.panels.get(message.panel) ?? { panel: message.panel, posted: [] }; p.html = message.html; ide.panels.set(message.panel, p); break; }
      case "__panelPost": ide.panels.get(message.panel)?.posted.push(message.message); break;
      case "__panelTitle": if (ide.panels.get(message.panel)) ide.panels.get(message.panel).title = message.title; break;
      case "__panelDispose": ide.panels.delete(message.panel); break;
      case "__usage": ide.usage.push(message.command); break;
      case "__theme": ide.theme = message.id; break;
      case "__points": ide.points.push(message.balance); break;
      case "__execResult": ide.execResults.get(message.id)?.(message); break;
      case "__ledgerResult": ide.ledgerResults.get(message.id)?.(message); break;
      default: (ide.other ??= []).push(message);
    }
  }

  ide.execResults = new Map();
  ide.ledgerResults = new Map();
  let nextExec = 1;

  ide.start = () => {
    const sandbox = {
      console: options.quiet ? { ...console, error: (...a) => ide.errors.push(a.map(String).join(" ")), warn: () => undefined, log: () => undefined } : console,
      setTimeout, clearTimeout, setInterval, clearInterval, queueMicrotask,
      crypto: globalThis.crypto, TextEncoder, TextDecoder, atob, btoa, URL, URLSearchParams, Intl, structuredClone, performance,
      AbortController, AbortSignal, Event, EventTarget, DOMException, WebAssembly, ReadableStream, fetch: undefined,
      __devsnipHost: {
        page: "engine",
        platform: process.platform === "win32" ? "win32" : process.platform === "darwin" ? "darwin" : "linux",
        home: os.homedir(),
        tmpdir: os.tmpdir(),
        extensionPath: extension,
        globalStorage: storage,
        version: "11.81.1",
        ideName: "IntelliJ IDEA",
        trusted: options.trusted !== false,
        workspace: { name: "project", path: workspace },
        settings: ide.settings,
        globalState: ide.globalState,
        workspaceState: ide.workspaceState,
        nativeCommands: native,
        machineId: "test-machine",
        env: { PATH: process.env.PATH, HOME: os.homedir() },
        telemetry: false
      },
      __devsnipToHost: onMessage,
      __devsnipSync: (method, params) => {
        try {
          return { result: answer(method, params) ?? null };
        } catch (error) {
          return { error: error.message, code: error.code };
        }
      }
    };
    sandbox.globalThis = sandbox;
    sandbox.window = sandbox;
    context = vm.createContext(sandbox);
    vm.runInContext(fs.readFileSync(bundlePath, "utf8"), context, { filename: "host-runtime.js" });
    ide.context = context;
  };

  ide.deliver = deliver;

  /** Runs a command as the IDE does (with the editor state), resolving when the host answers. */
  ide.exec = (command, args = [], editors = null) => new Promise(resolve => {
    const id = nextExec++;
    ide.execResults.set(id, resolve);
    deliver({ type: "__exec", id, command, args, editors });
  });

  ide.ledger = (op, extra = {}) => new Promise(resolve => {
    const id = nextExec++;
    ide.ledgerResults.set(id, resolve);
    deliver({ type: "__ledger", id, op, ...extra });
  });

  ide.panelMessage = (panel, message) => deliver({ type: "__panelMessage", panel, message });
  ide.panelByType = viewType => [...ide.panels.values()].find(p => p.viewType === viewType);
  ide.settle = (ms = 30) => new Promise(r => setTimeout(r, ms));
  ide.cleanup = () => fs.rmSync(root, { recursive: true, force: true });
  return ide;
}

export function languageOf(file) {
  const ext = path.extname(file).toLowerCase();
  return { ".js": "javascript", ".jsx": "javascriptreact", ".ts": "typescript", ".tsx": "typescriptreact", ".py": "python", ".java": "java", ".kt": "kotlin", ".cs": "csharp", ".dart": "dart", ".swift": "swift", ".json": "json", ".md": "markdown", ".html": "html", ".vue": "vue", ".php": "php" }[ext] || "plaintext";
}

/** The editor state the IDE sends with a command: one text editor on `file`, cursor at `cursor`. */
export function editorState(file, text, cursor = { line: 0, character: 0 }, selectionEnd) {
  const selection = { anchor: cursor, active: selectionEnd ?? cursor };
  const info = { id: `e:${file}`, path: file, text, languageId: languageOf(file), version: 1, isDirty: false, selections: [selection] };
  return { active: info, visible: [info], documents: [] };
}
