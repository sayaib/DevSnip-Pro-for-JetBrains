/*
 * The parts of the `vscode` API the REST API Client's feature code uses, for
 * the page in the embedded browser: save / open dialogs, reading and writing
 * the file the user picked, notifications. Each is a request to the IDE,
 * which only reads or writes files the user chose in one of its dialogs.
 */
import { Buffer } from "buffer";

function rpc(method, params) {
  const bridge = globalThis.__devsnipApiHost;
  if (!bridge) return Promise.reject(new Error("The IDE connection is not available."));
  return bridge.rpc(method, params);
}

const baseName = path => String(path || "").split(/[\\/]/).pop();

export const Uri = {
  file: path => ({ scheme: "file", fsPath: path, path, toString: () => path }),
  parse: text => ({ scheme: "file", fsPath: text, path: text, toString: () => text }),
  joinPath: (base, ...parts) => Uri.file([base.fsPath, ...parts].join("/"))
};

export const window = {
  showSaveDialog: options =>
    rpc("dialog.save", { defaultName: baseName(options?.defaultUri?.fsPath), filters: options?.filters || {}, title: options?.title || "" })
      .then(path => (path ? Uri.file(path) : undefined)),
  showOpenDialog: options =>
    rpc("dialog.open", { filters: options?.filters || {}, title: options?.title || "" })
      .then(path => (path ? [Uri.file(path)] : undefined)),
  showInformationMessage: text => rpc("notify", { kind: "info", text: String(text) }).then(() => undefined),
  showWarningMessage: text => rpc("notify", { kind: "warning", text: String(text) }).then(() => undefined),
  showErrorMessage: text => rpc("notify", { kind: "error", text: String(text) }).then(() => undefined)
};

export const workspace = {
  fs: {
    writeFile: (uri, bytes) => rpc("fs.write", { path: uri.fsPath, base64: Buffer.from(bytes).toString("base64") }).then(() => undefined),
    readFile: uri => rpc("fs.read", { path: uri.fsPath }).then(base64 => new Uint8Array(Buffer.from(base64 || "", "base64")))
  },
  getConfiguration: () => ({ get: (_key, fallback) => fallback }),
  workspaceFolders: undefined
};

/* The points tracker (Milestones & rewards) is not part of the IDE plugin yet. */
export const commands = { executeCommand: () => Promise.resolve(undefined) };

export class EventEmitter {
  constructor() {
    this.event = () => ({ dispose() {} });
  }
  fire() {}
  dispose() {}
}

export default { Uri, window, workspace, commands, EventEmitter };
