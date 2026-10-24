/*
 * Build-time stand-in for the `vscode` module. The exporter only renders HTML
 * shells and reads static data; modules that create emitters or read
 * settings on import get harmless no-ops.
 */
const noop = () => undefined;
function stub(name) {
  const fn = function () { return stub(name + "()"); };
  return new Proxy(fn, {
    get(_t, prop) {
      if (prop === Symbol.toPrimitive) return () => name;
      if (prop === "then") return undefined;
      return stub(`${name}.${String(prop)}`);
    },
    construct() { return stub(`new ${name}`); },
    apply() { return stub(`${name}()`); }
  });
}
class EventEmitter {
  constructor() { this.event = () => ({ dispose: noop }); }
  fire() {}
  dispose() {}
}
const Uri = { file: p => ({ fsPath: p, path: p, toString: () => p }), joinPath: (u, ...p) => ({ toString: () => [u, ...p].join("/") }), parse: s => ({ toString: () => s }) };
const vscode = new Proxy({ EventEmitter, Uri, Disposable: class { static from() { return { dispose: noop }; } dispose() {} } }, {
  get(target, prop) { return prop in target ? target[prop] : stub(`vscode.${String(prop)}`); }
});
module.exports = vscode;
