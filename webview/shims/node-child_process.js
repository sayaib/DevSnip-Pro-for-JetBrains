/*
 * Node's child_process.spawn for the scanner runtime: the IDE starts the
 * process (only Maven's dependency:tree or Gradle with the scanner's init
 * script, and only from the project's own wrapper or the tool on PATH) and
 * sends its output and exit back as events.
 */
import { onHostEvent, rpc } from "./host-rpc.js";

let nextId = 1;
const children = new Map();

onHostEvent("__process", message => {
  const child = children.get(message.id);
  if (!child) return;
  if (message.event === "stdout" || message.event === "stderr") child[message.event].emit("data", Buffer.from(String(message.data ?? ""), "utf8"));
  else if (message.event === "error") {
    children.delete(message.id);
    child.emit("error", new Error(String(message.message ?? "The process could not be started.")));
  } else if (message.event === "close") {
    children.delete(message.id);
    child.emit("close", message.code === null || message.code === undefined ? null : Number(message.code));
  }
});

class Emitter {
  constructor() { this.handlers = {}; }
  on(event, handler) { (this.handlers[event] ??= []).push(handler); return this; }
  once(event, handler) {
    const wrapped = (...args) => { this.off(event, wrapped); handler(...args); };
    return this.on(event, wrapped);
  }
  off(event, handler) { this.handlers[event] = (this.handlers[event] ?? []).filter(h => h !== handler); return this; }
  emit(event, ...args) { for (const handler of [...(this.handlers[event] ?? [])]) handler(...args); }
}

/** Undoes the cmd.exe quoting the extension applies on Windows (quoteForCmd), so the IDE gets the program and its arguments. */
export function splitCmdLine(line) {
  const out = [];
  let current = "";
  let quoted = false;
  let started = false;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (quoted) {
      if (c === '"' && line[i + 1] === '"') { current += '"'; i++; }
      else if (c === '"') quoted = false;
      else current += c;
    } else if (c === '"') { quoted = true; started = true; }
    else if (c === " ") { if (started) { out.push(current); current = ""; started = false; } }
    else { current += c; started = true; }
  }
  if (started) out.push(current);
  return out;
}

/** The pids the IDE knows by our ids; process.kill(-pid) and taskkill reach them through here. */
export function killChild(pid) {
  if (!children.has(pid)) return false;
  void rpc("process.kill", { id: pid }).catch(() => undefined);
  return true;
}

export function spawn(command, args, options) {
  let argv = Array.isArray(args) ? args.map(String) : [];
  let opts = Array.isArray(args) ? options || {} : args || {};
  let program = String(command);
  if (program === "taskkill") {
    // The extension's Windows kill: taskkill /pid <pid> /T /F.
    const at = argv.indexOf("/pid");
    if (at >= 0) killChild(Number(argv[at + 1]));
    const done = new Emitter();
    done.stdout = new Emitter();
    done.stderr = new Emitter();
    return done;
  }
  if (opts.shell) {
    const parts = splitCmdLine(program);
    program = parts[0] ?? "";
    argv = parts.slice(1);
  }
  const id = nextId++;
  const child = new Emitter();
  child.pid = id;
  child.stdout = new Emitter();
  child.stderr = new Emitter();
  child.kill = () => killChild(id);
  children.set(id, child);
  rpc("process.spawn", { id, command: program, args: argv, cwd: opts.cwd ? String(opts.cwd) : "" }).catch(error => {
    if (!children.has(id)) return;
    children.delete(id);
    child.emit("error", error instanceof Error ? error : new Error(String(error)));
  });
  return child;
}

export default { spawn };
