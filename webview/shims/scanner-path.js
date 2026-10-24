/*
 * Node's path for the scanner runtime: POSIX paths (Windows paths arrive as
 * C:/… from the IDE), with the host's PATH delimiter.
 */
import posix from "path-browserify";

export const { join, dirname, basename, extname, normalize, relative, resolve, isAbsolute, sep, format, parse } = posix;
export const delimiter = (globalThis.__devsnipHost || {}).platform === "win32" ? ";" : ":";
export const win32 = posix;
export { posix };
export default { ...posix, delimiter, posix, win32: posix };
