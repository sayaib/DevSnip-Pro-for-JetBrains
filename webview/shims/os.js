/* Node's os module for the embedded browser: the host fills the network interfaces in. */
export function networkInterfaces() {
  return (globalThis.__devsnipHost && globalThis.__devsnipHost.networkInterfaces) || {};
}
export function platform() {
  return (globalThis.__devsnipHost && globalThis.__devsnipHost.platform) || "linux";
}
export const EOL = "\n";
export default { networkInterfaces, platform, EOL };
