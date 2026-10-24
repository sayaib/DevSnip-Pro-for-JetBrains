/* Node's os module for the embedded browser: the host fills these in. */
const info = () => globalThis.__devsnipHost || {};
export function networkInterfaces() {
  return info().networkInterfaces || {};
}
export function platform() {
  return info().platform || "linux";
}
export function homedir() {
  return info().home || "/";
}
export function tmpdir() {
  return info().tmpdir || "/tmp";
}
export const EOL = "\n";
export default { networkInterfaces, platform, homedir, tmpdir, EOL };
