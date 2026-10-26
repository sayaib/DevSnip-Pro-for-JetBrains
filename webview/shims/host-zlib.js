/* Node's zlib for the CSV & Excel viewer (xlsx entries are raw deflate), on pako. */
import { Buffer } from "buffer";
import pako from "pako";

export function inflateRawSync(data, options = {}) {
  const out = pako.inflateRaw(data);
  if (options.maxOutputLength && out.length > options.maxOutputLength) {
    const error = new RangeError("Cannot create a Buffer larger than the maximum output length");
    error.code = "ERR_BUFFER_TOO_LARGE";
    throw error;
  }
  return Buffer.from(out);
}

export function deflateRawSync(data, options = {}) {
  return Buffer.from(pako.deflateRaw(data, { level: options.level ?? 6 }));
}

export function inflateSync(data) {
  return Buffer.from(pako.inflate(data));
}

export function gunzipSync(data) {
  return Buffer.from(pako.ungzip(data));
}

export default { inflateRawSync, deflateRawSync, inflateSync, gunzipSync };
