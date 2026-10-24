/* Node globals for the scanner runtime (the toolkit's globals.js plus the host's platform, PATH and process.kill). */
import { Buffer } from "buffer";
import { killChild } from "./node-child_process.js";

const info = () => globalThis.__devsnipHost || {};
export { Buffer };
export const process = {
  get platform() { return info().platform || "linux"; },
  env: { get PATH() { return info().pathEnv || ""; } },
  versions: {},
  cwd: () => "/",
  nextTick: (fn, ...args) => Promise.resolve().then(() => fn(...args)),
  /** The extension stops Maven and Gradle with process.kill(-pid): the IDE ends the process tree. */
  kill(pid) {
    if (!killChild(Math.abs(Number(pid)))) {
      const error = new Error("kill ESRCH");
      error.code = "ESRCH";
      throw error;
    }
    return true;
  }
};
