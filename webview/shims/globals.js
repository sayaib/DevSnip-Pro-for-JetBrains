/* Node globals the toolkit uses, for the browser bundle. */
import { Buffer } from "buffer";
export { Buffer };
export const process = { env: {}, platform: "browser", versions: {}, cwd: () => "/", nextTick: (fn, ...args) => Promise.resolve().then(() => fn(...args)) };
