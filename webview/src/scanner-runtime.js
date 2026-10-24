/*
 * DevSnip Pro's OSV Vulnerability Scanner and SCA Scanner, running inside
 * the JetBrains IDE's embedded browser. Everything here is the VS Code
 * extension's own code (src/osv, src/sca: panels, engine, discovery, Maven
 * and Gradle resolution, providers), bundled with browser stand-ins for the
 * vscode, fs, child_process, os and fetch APIs it uses (webview/shims). The
 * IDE (ScannerHost.kt) does the I/O: files, HTTP, build tools, dialogs.
 */
import "./scanner-fetch.js";
import { extensionContext, panelHooks } from "vscode";
import { openOsvScanner, osvSessionForTests } from "@devsnip/osv/panel";
import { openScaScanner, scaSessionForTests } from "@devsnip/sca/panel";
import { rpc } from "../shims/host-rpc.js";

function keepLastResult(page) {
  // The IDE keeps the latest result for the project, so reopening the tab shows it (as the extension's session does).
  panelHooks.onPost = message => {
    if (!message || typeof message !== "object") return;
    if (message.type === "result" || (page === "sca" && message.type === "projects")) {
      const key = message.type === "result" ? "result" : "projects";
      void rpc("session.save", { key, value: message[key] ?? null }).catch(() => undefined);
    }
  };
}

globalThis.DevSnipScanners = {
  /** Opens the page's scanner, as the extension's command does, with the project's last result. */
  start(page) {
    const info = globalThis.__devsnipHost || {};
    const saved = info.session || {};
    const context = extensionContext();
    keepLastResult(page);
    if (page === "osv") {
      if (saved.result) osvSessionForTests().result = saved.result;
      return openOsvScanner(context, info.run ? { run: true } : {});
    }
    if (page === "sca") {
      const session = scaSessionForTests();
      if (saved.result) session.result = saved.result;
      if (saved.projects) session.projects = saved.projects;
      return openScaScanner(context, info.run ? { run: true } : {});
    }
    return Promise.reject(new Error(`Unknown scanner ${page}`));
  }
};

// The panel opens before the page's own script asks for "ready" (bridge.js and this file load in the head).
const page = (globalThis.__devsnipHost || {}).page;
if (page === "osv" || page === "sca") {
  globalThis.DevSnipScanners.start(page).catch(error => console.error("DevSnip Pro: the scanner could not start.", error));
}
