/*
 * Node's fs for the scanner runtime. Asynchronous calls go to the IDE, which
 * only allows the scanner's own folders (its storage, its temporary folders,
 * the local Maven repository). The two synchronous calls the extension makes
 * are answered from facts the IDE sent (see host-rpc.js).
 */
import { addFacts, factExists, factText, rpc } from "./host-rpc.js";

function enoent(p) {
  const error = new Error(`ENOENT: no such file or directory, open '${p}'`);
  error.code = "ENOENT";
  return error;
}

const toText = data => (typeof data === "string" ? data : new TextDecoder().decode(data));

export const promises = {
  async readFile(p, options) {
    const text = await rpc("fs.read", { path: String(p) });
    if (text === null || text === undefined) throw enoent(p);
    const encoding = typeof options === "string" ? options : options?.encoding;
    return encoding ? text : Buffer.from(text, "utf8");
  },
  async writeFile(p, data) {
    await rpc("fs.write", { path: String(p), text: toText(data) });
  },
  async mkdir(p) {
    await rpc("fs.mkdir", { path: String(p) });
  },
  async rm(p, options) {
    await rpc("fs.rm", { path: String(p), recursive: Boolean(options?.recursive) });
  },
  async mkdtemp(prefix) {
    return rpc("fs.mkdtemp", { prefix: String(prefix) });
  }
};

export function existsSync(p) {
  return factExists(p);
}

export function readFileSync(p, options) {
  const text = factText(p);
  if (text === undefined) throw enoent(p);
  const encoding = typeof options === "string" ? options : options?.encoding;
  return encoding ? text : Buffer.from(text, "utf8");
}

export { addFacts };
export default { promises, existsSync, readFileSync };
