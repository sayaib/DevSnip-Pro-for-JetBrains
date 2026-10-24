/*
 * Build-time exporter (runs on Node): renders DevSnip Pro's own webview
 * shells and data from the VS Code extension's source, so the JetBrains
 * plugin shows exactly the same pages.
 */
import fs from "fs";
import path from "path";
import { renderToolsSidebar } from "@devsnip/sidebar/tools-sidebar";
import { renderToolPage } from "@devsnip/toolkits/page";
import { ALL_TOOLS, SECTIONS } from "@devsnip/toolkits/registry";
import { describe } from "@devsnip/toolkits/types";
import { themeChoices } from "@devsnip/theme/service";
import { injectTheme, themeMessage } from "@devsnip/theme/inject";
import { THEMES, SYSTEM_THEME_ID } from "@devsnip/theme/themes";
import { NAV, COMMAND_PREFIX } from "@devsnip/toolkits/layout";
import { renderOsvPage } from "@devsnip/osv/page";
import { renderScaPage } from "@devsnip/sca/page";
import { OSV_SCANNER_COMMAND } from "@devsnip/osv/command";
import { SCA_SCANNER_COMMAND } from "@devsnip/sca/command";
import { treeArguments } from "@devsnip/osv/maven/cli";
import { gradleArguments, INIT_SCRIPT } from "@devsnip/osv/gradle/cli";

const out = process.argv[2];
fs.mkdirSync(out, { recursive: true });
const write = (name, text) => fs.writeFileSync(path.join(out, name), text);

const DATA_MARKER = "__DEVSNIP_DATA__";

/** Replaces the page's embedded JSON (script#id) with a marker; returns [html, data]. */
function splitData(html, id) {
  const re = new RegExp(`(<script type="application/json" id="${id}">)([\\s\\S]*?)(</script>)`);
  const m = re.exec(html);
  if (!m) throw new Error(`No ${id} block`);
  return [html.replace(re, (_a, open, _d, close) => open + DATA_MARKER + close), JSON.parse(m[2])];
}

// ---- Sidebar ---------------------------------------------------------------
const sidebarHtml = renderToolsSidebar({
  cspSource: "devsnip:",
  scriptUri: "__DEVSNIP_SCRIPT__",
  codiconsUri: "__DEVSNIP_CODICONS__",
  status: { badge: "", level: "", points: 0, lifetimePoints: 0, nextLevel: null, toNext: 0, progress: 0 },
  expanded: [],
  favorites: [],
  usage: {},
  theme: { current: SYSTEM_THEME_ID, choices: themeChoices(), lockedPreview: null, previewSeconds: 30 },
  onboarding: { guide: null, whatsNew: null }
});
const [sidebarShell, sidebarData] = splitData(sidebarHtml, "sidebar-data");
write("sidebar.html", sidebarShell);
write("sidebar-data.json", JSON.stringify(sidebarData));

// ---- Tool pages ------------------------------------------------------------
let toolShell = null;
const tools = {};
for (const tool of ALL_TOOLS) {
  const section = SECTIONS.find(s => s.id === tool.section);
  const html = renderToolPage({
    cspSource: "devsnip:",
    scriptUri: "__DEVSNIP_SCRIPT__",
    tool: describe(tool),
    section: { id: section.id, title: section.title },
    examples: tool.examples ?? [],
    initial: {},
    platform: "linux"
  });
  const [shell, data] = splitData(html, "tool-data");
  // Every tool page shares one shell; the title is the only other difference.
  const generic = shell.replace(/<title>[\s\S]*?<\/title>/, "<title>__DEVSNIP_TITLE__</title>");
  if (toolShell && toolShell !== generic) throw new Error(`Tool page shells differ (${tool.id})`);
  toolShell = generic;
  tools[tool.id] = { command: tool.command, title: tool.title, section: tool.section, data };
}
write("tool.html", toolShell);
write("tools.json", JSON.stringify(tools));

// ---- OSV and SCA scanner pages ------------------------------------------------
// Each render has a fresh nonce; it is made a fixed value (the IDE replaces the CSP anyway).
const fixedNonce = html => {
  const nonce = /nonce-([^']+)'/.exec(html)[1];
  return html.split(nonce).join("devsnip");
};
const scannerUris = { cspSource: "devsnip:", scriptUri: "__DEVSNIP_SCRIPT__", codiconsUri: "__DEVSNIP_CODICONS__" };
write("osv.html", fixedNonce(renderOsvPage(scannerUris)));
write("sca.html", fixedNonce(renderScaPage({ ...scannerUris, modelUri: "__DEVSNIP_MODEL__" })));
write("scanners.json", JSON.stringify({
  osv: { command: OSV_SCANNER_COMMAND, title: "OSV Vulnerability Scanner", script: "osv-scanner.js" },
  sca: { command: SCA_SCANNER_COMMAND, title: "SCA Scanner", script: "sca-scanner.js", model: "sca-model.js" }
}));
// The only build tool runs the IDE allows: the extension's read-only Maven and Gradle invocations.
write("build-tools.json", JSON.stringify({
  maven: { args: treeArguments("__OUTPUT__") },
  gradle: { args: gradleArguments("__INIT__"), argsWithoutConfigurationCache: gradleArguments("__INIT__", false), initScript: INIT_SCRIPT }
}));

// ---- Navigation: which sidebar command opens which tool ----------------------
const nav = NAV.map(section => ({
  id: section.id,
  title: section.title,
  entries: section.entries.map(entry => ({ command: COMMAND_PREFIX + entry.command, label: entry.label, tool: entry.tool ?? null }))
}));
write("nav.json", JSON.stringify({ prefix: COMMAND_PREFIX, sections: nav }));

// ---- Themes ----------------------------------------------------------------
// injectTheme's style + runtime block for every theme and surface, with its nonce made a placeholder.
const themes = { system: SYSTEM_THEME_ID, choices: themeChoices(), blocks: {}, messages: {} };
for (const id of [SYSTEM_THEME_ID, ...THEMES.map(t => t.id)]) {
  themes.blocks[id] = {};
  themes.messages[id] = {};
  for (const surface of ["panel", "sidebar"]) {
    themes.messages[id][surface] = themeMessage(id, surface);
    const injected = injectTheme('<meta http-equiv="Content-Security-Policy" content="x"><head></head>', id, surface);
    const nonce = /nonce="([^"]+)"/.exec(injected)[1];
    const block = injected.slice(injected.indexOf("<style id=\"devsnip-theme\""), injected.indexOf("</head>"));
    themes.blocks[id][surface] = block.split(nonce).join("__DEVSNIP_NONCE__");
  }
}
write("themes.json", JSON.stringify(themes));

console.log(`Exported the sidebar, ${Object.keys(tools).length} tool pages and ${Object.keys(themes.blocks).length} themes.`);
