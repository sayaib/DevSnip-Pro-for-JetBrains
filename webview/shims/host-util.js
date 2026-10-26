/* Node's util for DevSnip Pro's extension host: promisify (with .custom), format and inspect, as far as the extension uses them. */
const custom = Symbol.for("nodejs.util.promisify.custom");

export function promisify(fn) {
  if (fn && fn[custom]) return fn[custom];
  return (...args) => new Promise((resolve, reject) => {
    fn(...args, (error, value) => (error ? reject(error) : resolve(value)));
  });
}
promisify.custom = custom;

export function inspect(value) {
  if (typeof value === "string") return `'${value}'`;
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

export function format(text, ...args) {
  let i = 0;
  const out = String(text).replace(/%[sdifjoO%]/g, token => {
    if (token === "%%") return "%";
    if (i >= args.length) return token;
    const value = args[i++];
    if (token === "%d" || token === "%i") return String(Number.parseInt(value, 10));
    if (token === "%f") return String(Number(value));
    if (token === "%j" || token === "%o" || token === "%O") return inspect(value);
    return String(value);
  });
  return [out, ...args.slice(i).map(a => (typeof a === "string" ? a : inspect(a)))].join(" ");
}

export const types = { isDate: value => value instanceof Date, isRegExp: value => value instanceof RegExp };
export const TextEncoder = globalThis.TextEncoder;
export const TextDecoder = globalThis.TextDecoder;

export default { promisify, inspect, format, types, TextEncoder, TextDecoder };
