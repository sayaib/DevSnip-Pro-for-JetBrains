/* Node globals for DevSnip Pro's extension host: Buffer, and a process with the host's platform, environment and kill. */
import { Buffer } from "buffer";
import { killChild } from "./host-child_process.js";

const info = () => globalThis.__devsnipHost || {};
const listeners = {};

export { Buffer };
export const process = {
  get platform() { return info().platform || "linux"; },
  get env() { return info().env || (info().env = {}); },
  get arch() { return info().arch || "x64"; },
  pid: 1,
  versions: { node: "20.0.0" },
  version: "v20.0.0",
  argv: [],
  execPath: "",
  cwd: () => info().workspace?.path || "/",
  nextTick: (fn, ...args) => Promise.resolve().then(() => fn(...args)),
  hrtime: Object.assign(previous => {
    const now = performance.now();
    const seconds = Math.floor(now / 1000);
    const nanos = Math.floor((now % 1000) * 1e6);
    if (!previous) return [seconds, nanos];
    let ds = seconds - previous[0];
    let dn = nanos - previous[1];
    if (dn < 0) { ds -= 1; dn += 1e9; }
    return [ds, dn];
  }, { bigint: () => BigInt(Math.floor(performance.now() * 1e6)) }),
  memoryUsage: () => ({ rss: 0, heapTotal: 0, heapUsed: 0, external: 0 }),
  uptime: () => performance.now() / 1000,
  emitWarning: () => undefined,
  on(event, listener) { (listeners[event] ??= []).push(listener); return process; },
  once(event, listener) { return process.on(event, listener); },
  off() { return process; },
  removeListener() { return process; },
  /** The extension stops what it started with process.kill(-pid): the IDE ends the process tree. */
  kill(pid) {
    if (!killChild(Math.abs(Number(pid)))) {
      const error = new Error("kill ESRCH");
      error.code = "ESRCH";
      throw error;
    }
    return true;
  }
};
