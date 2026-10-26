/* Node's os for DevSnip Pro's extension host: the machine as the IDE reports it. */
const info = () => globalThis.__devsnipHost || {};

export const platform = () => info().platform || "linux";
export const homedir = () => info().home || "/";
export const tmpdir = () => info().tmpdir || "/tmp";
export const hostname = () => info().hostname || "localhost";
export const type = () => ({ win32: "Windows_NT", darwin: "Darwin" }[platform()] || "Linux");
export const release = () => info().osVersion || "";
export const arch = () => info().arch || "x64";
export const cpus = () => Array.from({ length: info().cpus || 1 }, () => ({ model: "", speed: 0, times: {} }));
export const totalmem = () => info().totalmem || 0;
export const freemem = () => 0;
export const networkInterfaces = () => info().networkInterfaces || {};
export const userInfo = () => ({ username: info().username || "", homedir: homedir(), shell: info().shell || null, uid: -1, gid: -1 });
export const EOL = platform() === "win32" ? "\r\n" : "\n";
export const constants = { signals: {} };

export default { platform, homedir, tmpdir, hostname, type, release, arch, cpus, totalmem, freemem, networkInterfaces, userInfo, EOL, constants };
