/*
 * Node's fs for DevSnip Pro's extension host in the IDE. Every call goes to
 * the IDE (EngineHost.kt), which allows the project's files, the extension's
 * own folder and storage, files the user picked, and the few files outside
 * the project the extension reads (Maven settings, build tool caches).
 * The synchronous calls the extension makes are answered synchronously.
 */
import { Buffer } from "buffer";
import { fromBase64, rpc, syncCall, toBase64 } from "./host-channel.js";

function errnoError(error, path, syscall) {
  const message = error instanceof Error ? error.message : String(error);
  const code = error?.code || (/ENOENT|not found|does not exist|No such file/i.test(message) ? "ENOENT" : /EACCES|not allowed|permission/i.test(message) ? "EACCES" : /EEXIST/.test(message) ? "EEXIST" : /ENOTDIR/.test(message) ? "ENOTDIR" : /EISDIR/.test(message) ? "EISDIR" : "EIO");
  const out = new Error(`${code}: ${message.replace(/^E[A-Z]+:\s*/, "")}, ${syscall} '${path}'`);
  out.code = code;
  out.errno = -1;
  out.syscall = syscall;
  out.path = String(path);
  return out;
}

const encodingOf = options => (typeof options === "string" ? options : options?.encoding ?? null);

function decode(base64, options) {
  const bytes = Buffer.from(fromBase64(base64));
  const encoding = encodingOf(options);
  return encoding ? bytes.toString(encoding === "utf-8" ? "utf8" : encoding) : bytes;
}

function encode(data, options) {
  if (typeof data === "string") {
    const encoding = encodingOf(options) || "utf8";
    return toBase64(Buffer.from(data, encoding));
  }
  return toBase64(data instanceof Uint8Array ? data : new Uint8Array(data));
}

class Stats {
  constructor(raw) {
    this.size = raw.size ?? 0;
    this.mtimeMs = raw.mtime ?? 0;
    this.ctimeMs = raw.ctime ?? raw.mtime ?? 0;
    this.birthtimeMs = this.ctimeMs;
    this.atimeMs = this.mtimeMs;
    this.mtime = new Date(this.mtimeMs);
    this.ctime = new Date(this.ctimeMs);
    this.birthtime = new Date(this.birthtimeMs);
    this.atime = new Date(this.atimeMs);
    this.mode = raw.type === 2 ? 0o40755 : raw.executable ? 0o100755 : 0o100644;
    this.type = raw.type;
    this.executable = Boolean(raw.executable);
  }
  isFile() { return this.type === 1; }
  isDirectory() { return this.type === 2; }
  isSymbolicLink() { return false; }
}

class Dirent {
  constructor(name, type, parentPath) { this.name = name; this.type = type; this.parentPath = parentPath; this.path = parentPath; }
  isFile() { return this.type === 1; }
  isDirectory() { return this.type === 2; }
  isSymbolicLink() { return false; }
}

export const constants = { F_OK: 0, R_OK: 4, W_OK: 2, X_OK: 1, O_RDONLY: 0 };

function sync(method, params, path, syscall) {
  try {
    return syncCall(method, params);
  } catch (error) {
    throw errnoError(error, path, syscall);
  }
}

export function readFileSync(path, options) {
  const base64 = sync("fs.readBytes", { path: String(path) }, path, "open");
  if (base64 === null || base64 === undefined) throw errnoError(new Error("ENOENT"), path, "open");
  return decode(base64, options);
}

export function writeFileSync(path, data, options) {
  sync("fs.writeBytes", { path: String(path), base64: encode(data, options), append: Boolean(options?.flag === "a") }, path, "open");
}

export function appendFileSync(path, data, options) {
  sync("fs.writeBytes", { path: String(path), base64: encode(data, options), append: true }, path, "open");
}

export function existsSync(path) {
  try {
    return Boolean(syncCall("fs.stat", { path: String(path) }));
  } catch {
    return false;
  }
}

export function statSync(path, options) {
  const raw = sync("fs.stat", { path: String(path) }, path, "stat");
  if (!raw) {
    if (options?.throwIfNoEntry === false) return undefined;
    throw errnoError(new Error("ENOENT"), path, "stat");
  }
  return new Stats(raw);
}

export const lstatSync = statSync;

export function accessSync(path, mode = constants.F_OK) {
  const raw = sync("fs.stat", { path: String(path) }, path, "access");
  if (!raw) throw errnoError(new Error("ENOENT"), path, "access");
  if ((mode & constants.X_OK) && raw.type === 1 && !raw.executable) throw errnoError(new Error("EACCES"), path, "access");
}

export function readdirSync(path, options) {
  const entries = sync("fs.readdir", { path: String(path) }, path, "scandir") || [];
  return options?.withFileTypes ? entries.map(e => new Dirent(e.name, e.type, String(path))) : entries.map(e => e.name);
}

export function mkdirSync(path) {
  sync("fs.mkdir", { path: String(path) }, path, "mkdir");
}

export function renameSync(from, to) {
  sync("fs.rename", { from: String(from), to: String(to), overwrite: true }, from, "rename");
}

export function rmSync(path, options = {}) {
  try {
    syncCall("fs.delete", { path: String(path), recursive: Boolean(options.recursive) });
  } catch (error) {
    if (!options.force) throw errnoError(error, path, "rm");
  }
}

export const unlinkSync = path => rmSync(path);

/** The one low-level read the extension makes: a file's first bytes (the SQLite header). */
const descriptors = new Map();
let nextFd = 100;

export function openSync(path) {
  const fd = nextFd++;
  descriptors.set(fd, String(path));
  return fd;
}

export function readSync(fd, buffer, offset, length, position) {
  const path = descriptors.get(fd);
  if (path === undefined) throw errnoError(new Error("EBADF"), fd, "read");
  const base64 = sync("fs.readBytes", { path, offset: position ?? 0, length }, path, "read");
  const bytes = fromBase64(base64 || "");
  const count = Math.min(length, bytes.length);
  for (let i = 0; i < count; i++) buffer[offset + i] = bytes[i];
  return count;
}

export function closeSync(fd) {
  descriptors.delete(fd);
}

async function call(method, params, path, syscall) {
  try {
    return await rpc(method, params);
  } catch (error) {
    throw errnoError(error, path, syscall);
  }
}

export const promises = {
  async readFile(path, options) {
    const base64 = await call("fs.readBytes", { path: String(path) }, path, "open");
    if (base64 === null || base64 === undefined) throw errnoError(new Error("ENOENT"), path, "open");
    return decode(base64, options);
  },
  async writeFile(path, data, options) {
    await call("fs.writeBytes", { path: String(path), base64: encode(data, options) }, path, "open");
  },
  async appendFile(path, data, options) {
    await call("fs.writeBytes", { path: String(path), base64: encode(data, options), append: true }, path, "open");
  },
  async readdir(path, options) {
    const entries = (await call("fs.readdir", { path: String(path) }, path, "scandir")) || [];
    return options?.withFileTypes ? entries.map(e => new Dirent(e.name, e.type, String(path))) : entries.map(e => e.name);
  },
  async mkdir(path) {
    await call("fs.mkdir", { path: String(path) }, path, "mkdir");
  },
  async stat(path) {
    const raw = await call("fs.stat", { path: String(path) }, path, "stat");
    if (!raw) throw errnoError(new Error("ENOENT"), path, "stat");
    return new Stats(raw);
  },
  async access(path, mode = constants.F_OK) {
    const raw = await call("fs.stat", { path: String(path) }, path, "access");
    if (!raw) throw errnoError(new Error("ENOENT"), path, "access");
    if ((mode & constants.X_OK) && raw.type === 1 && !raw.executable) throw errnoError(new Error("EACCES"), path, "access");
  },
  async rm(path, options = {}) {
    try {
      await rpc("fs.delete", { path: String(path), recursive: Boolean(options.recursive) });
    } catch (error) {
      if (!options.force) throw errnoError(error, path, "rm");
    }
  },
  async unlink(path) {
    await call("fs.delete", { path: String(path), recursive: false }, path, "unlink");
  },
  async rename(from, to) {
    await call("fs.rename", { from: String(from), to: String(to), overwrite: true }, from, "rename");
  },
  async copyFile(from, to) {
    await call("fs.copy", { from: String(from), to: String(to), overwrite: true }, from, "copyfile");
  },
  async mkdtemp(prefix) {
    return call("fs.mkdtemp", { prefix: String(prefix) }, prefix, "mkdtemp");
  }
};

promises.lstat = promises.stat;

function callbackStyle(fn) {
  return (...args) => {
    const callback = typeof args[args.length - 1] === "function" ? args.pop() : undefined;
    fn(...args).then(value => callback?.(null, value), error => callback?.(error));
  };
}

export const readFile = callbackStyle(promises.readFile);
export const writeFile = callbackStyle(promises.writeFile);
export const readdir = callbackStyle(promises.readdir);
export const stat = callbackStyle(promises.stat);
export const mkdir = callbackStyle(promises.mkdir);

export { Stats, Dirent };

export default {
  constants, promises, Stats, Dirent,
  readFileSync, writeFileSync, appendFileSync, existsSync, statSync, lstatSync, accessSync, readdirSync, mkdirSync, renameSync,
  rmSync, unlinkSync, openSync, readSync, closeSync, readFile, writeFile, readdir, stat, mkdir
};
