/*
 * Node's child_process for DevSnip Pro's extension host in the IDE: the IDE
 * starts the process (EngineHost.kt: only package-manager, build and
 * Node executables, in the project's folders, in a trusted project) and
 * streams its output and exit back as "__process" events.
 */
import { Buffer } from "buffer";
import { onHostEvent, rpc } from "./host-channel.js";
import { promisify } from "./host-util.js";

let nextId = 1;
const children = new Map();

class Emitter {
  constructor() { this.handlers = {}; }
  on(event, handler) { (this.handlers[event] ??= []).push(handler); return this; }
  addListener(event, handler) { return this.on(event, handler); }
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
  setEncoding(encoding) { this.encoding = encoding; return this; }
}

onHostEvent("__process", message => {
  const child = children.get(message.id);
  if (!child) return;
  if (message.event === "stdout" || message.event === "stderr") {
    const stream = child[message.event];
    const data = Buffer.from(String(message.data ?? ""), "utf8");
    stream.emit("data", stream.encoding ? data.toString(stream.encoding) : data);
  } else if (message.event === "error") {
    children.delete(message.id);
    const error = new Error(String(message.message ?? "The process could not be started."));
    error.code = message.code || "ENOENT";
    error.errno = -2;
    error.syscall = "spawn";
    error.path = child.spawnfile;
    child.emit("error", error);
    child.emit("close", null, null);
  } else if (message.event === "exit") {
    children.delete(message.id);
    const code = message.code === null || message.code === undefined ? null : Number(message.code);
    const signal = message.signal ?? (code === null ? "SIGTERM" : null);
    child.exitCode = code;
    child.signalCode = signal;
    child.stdout.emit("end");
    child.stderr.emit("end");
    child.emit("exit", code, signal);
    child.emit("close", code, signal);
  }
});

/** The pids the IDE knows by our ids; process.kill and taskkill reach them through here. */
export function killChild(pid) {
  const child = children.get(pid);
  if (!child) return false;
  child.killed = true;
  void rpc("process.kill", { id: pid }).catch(() => undefined);
  return true;
}

export function spawn(command, args, options) {
  const argv = Array.isArray(args) ? args.map(String) : [];
  const opts = (Array.isArray(args) ? options : args) || {};
  const program = String(command);
  if (program === "taskkill") {
    // The extension's Windows kill: taskkill /pid <pid> /T /F.
    const at = argv.indexOf("/pid");
    if (at >= 0) killChild(Number(argv[at + 1]));
    const done = new Emitter();
    done.stdout = new Emitter();
    done.stderr = new Emitter();
    done.kill = () => false;
    setTimeout(() => done.emit("close", 0, null), 0);
    return done;
  }
  const id = nextId++;
  const child = new Emitter();
  child.pid = id;
  child.spawnfile = program;
  child.spawnargs = [program, ...argv];
  child.exitCode = null;
  child.signalCode = null;
  child.killed = false;
  child.stdout = new Emitter();
  child.stderr = new Emitter();
  child.stdin = { write: text => { void rpc("process.input", { id, text: String(text) }).catch(() => undefined); return true; }, end: () => { void rpc("process.input", { id, end: true }).catch(() => undefined); } };
  child.kill = () => killChild(id);
  child.unref = () => child;
  child.ref = () => child;
  children.set(id, child);
  const env = opts.env && typeof opts.env === "object" ? Object.fromEntries(Object.entries(opts.env).filter(([, v]) => v !== undefined && v !== null).map(([k, v]) => [k, String(v)])) : null;
  rpc("process.spawn", {
    id,
    command: program,
    args: argv,
    cwd: opts.cwd ? String(opts.cwd) : "",
    env,
    shell: Boolean(opts.shell),
    windowsVerbatimArguments: Boolean(opts.windowsVerbatimArguments)
  }).catch(error => {
    if (!children.has(id)) return;
    children.delete(id);
    const failure = error instanceof Error ? error : new Error(String(error));
    failure.code ??= "ENOENT";
    child.emit("error", failure);
    child.emit("close", null, null);
  });
  return child;
}

/** execFile with Node's callback and promisified forms ({ stdout, stderr }, rejecting with code). */
export function execFile(file, args, options, callback) {
  if (typeof args === "function") { callback = args; args = []; options = {}; }
  else if (typeof options === "function") { callback = options; options = Array.isArray(args) ? {} : args; if (!Array.isArray(args)) args = []; }
  if (!Array.isArray(args)) { options = args || {}; args = []; }
  options = options || {};
  const child = spawn(file, args, options);
  let stdout = "";
  let stderr = "";
  const max = options.maxBuffer ?? 1024 * 1024;
  let timer;
  let settled = false;
  child.stdout.on("data", chunk => { stdout += chunk.toString(); if (stdout.length > max) stdout = stdout.slice(-max); });
  child.stderr.on("data", chunk => { stderr += chunk.toString(); if (stderr.length > max) stderr = stderr.slice(-max); });
  const finish = (error) => {
    if (settled) return;
    settled = true;
    if (timer) clearTimeout(timer);
    callback?.(error, stdout, stderr);
  };
  if (options.timeout) timer = setTimeout(() => { child.kill(); const e = new Error(`Command timed out: ${file}`); e.killed = true; e.signal = "SIGTERM"; e.code = null; finish(e); }, options.timeout);
  child.on("error", error => finish(error));
  child.on("close", (code, signal) => {
    if (code === 0) return finish(null);
    const error = new Error(`Command failed: ${[file, ...args].join(" ")}\n${stderr}`);
    error.code = code;
    error.signal = signal;
    error.killed = Boolean(child.killed);
    finish(error);
  });
  return child;
}

execFile[promisify.custom] = (file, args, options) => new Promise((resolve, reject) => {
  execFile(file, args, options, (error, stdout, stderr) => {
    if (error) {
      error.stdout = stdout;
      error.stderr = stderr;
      reject(error);
    } else {
      resolve({ stdout, stderr });
    }
  });
});

export default { spawn, execFile };
