/*
 * DevSnip Pro's extension host inside the JetBrains IDE: a hidden page of the
 * IDE's embedded browser (one per project) running the VS Code extension's
 * own code on the VS Code API in shims/vscode-host.js. This is the
 * extension's activate() (src/extention.ts) for everything the IDE does not
 * run natively: the Tools sidebar, points and milestones, themes and their
 * locks, onboarding, snippets, workspace hygiene, debug logs,
 * dependencies, security tools, the database client, the CSV & Excel viewer,
 * the tool hub and tool search.
 *
 * The toolkit tools, the REST API Client and the OSV / SCA scanners run in
 * their own pages (the IDE opens them); here they are registered as commands
 * that ask the IDE to open them, so they are counted for points, quests and
 * "Recently used" exactly like every other command.
 */
import "../shims/host-channel.js";
import "../shims/host-fetch.js";
import { readFileSync } from "fs";
import * as path from "path";
import * as vscode from "vscode";
import { extensionContext, registeredCommands, setEditorState } from "vscode";
import { host, onHostEvent, onStream, rpc, toHost } from "../shims/host-channel.js";
import { setSnippetBackupFolder, syncSnippetBackups } from "@devsnip/utils/snippet-utils";
import { registerHubCommands } from "@devsnip/commands/hubCommands";
import { registerDatabaseClientCommand } from "@devsnip/database/command";
import { registerSpreadsheetViewer } from "@devsnip/spreadsheet/command";
import { initThemes, registerThemeCommand, setThemeAccess, onDidChangeTheme, onDidChangeLockedPreview, currentThemeId } from "@devsnip/theme/service";
import { registerMilestoneTrackerCommand, setTreeRefreshCallback, setMilestoneContext, autoRecordToolUsage, redeemPoints, refundPoints, getPointsBalance, recordDiscovery, getUserStats, themeLockFor, buyReward, grantKeptTheme, onDidChangePoints } from "@devsnip/commands/milestoneTracker";
import { registerLazyCommands, registerTrackedCommand, setCommandObserver, setUsageRecorder, COMMAND_PREFIX } from "@devsnip/utils/command-registry";
import { classifyInstall, initAnalytics, snapshotInstall, trackCommand } from "@devsnip/analytics";
import { initActivation, noteCommand } from "@devsnip/onboarding/activation";
import { registerOnboardingCommands } from "@devsnip/onboarding/commands";
import { executeQueuedCommand } from "@devsnip/utils/command-dispatch";
import { FeatureAccessService } from "@devsnip/premium/feature-access";
import { registerPremiumCommands } from "@devsnip/premium/premium-commands";
import { ToolsSidebarProvider, TOOLS_VIEW_ID } from "@devsnip/sidebar/tools-sidebar";
import { renderMarkdown } from "@devsnip/utils/markdown";
import { escapeHtml, getNonce, openToolPanel } from "@devsnip/utils/webview-ui";
import { setWebviewHtml } from "@devsnip/theme/service";
import manifest from "@devsnip-manifest";

const SNIPPET_COMMANDS = ["createCustomSnippet", "showSnippets", "frameworkSnippets"].map(id => `${COMMAND_PREFIX}${id}`);
const HYGIENE_COMMANDS = ["listAndRemoveConsoleLogs", "removeUnusedImports", "readmeManager"].map(id => `${COMMAND_PREFIX}${id}`);
const DEBUG_LOG_COMMANDS = ["insertDebugLog", "removeDebugLogsInFile"].map(id => `${COMMAND_PREFIX}${id}`);
const SECURITY_COMMANDS = ["securityHub", "endpointSecurityScan", "securityAudit", "cloudSecurityAudit", "dependencyAudit"].map(id => `${COMMAND_PREFIX}${id}`);
const WALKTHROUGH_COMMAND = `${COMMAND_PREFIX}__walkthrough`;

/** What the IDE's own pages run: the toolkit tools, the REST API Client and the scanners. */
function registerNativeCommands(context) {
  for (const id of host().nativeCommands || []) {
    context.subscriptions.push(registerTrackedCommand(id, (...args) => rpc("ide.native", { id, args: args.map(wireArg) })));
  }
}

function wireArg(arg) {
  if (arg instanceof vscode.Uri) return { $uri: arg.toString(), fsPath: arg.fsPath };
  if (arg === undefined) return null;
  try {
    return JSON.parse(JSON.stringify(arg));
  } catch {
    return null;
  }
}

function argFromWire(arg) {
  if (arg && typeof arg === "object" && typeof arg.$uri === "string") return arg.fsPath ? vscode.Uri.file(arg.fsPath) : vscode.Uri.parse(arg.$uri);
  return arg === null ? undefined : arg;
}

/** Puts back snippets an update removed (the extension's restoreSnippetsAfterUpdate). */
async function restoreSnippetsAfterUpdate(liveFolder, backup, extensionPath) {
  try {
    const { syncSnippetBackups: sync } = await import("@devsnip/utils/snippet-utils");
    const restored = await sync(liveFolder, backup, extensionPath);
    if (restored) void vscode.window.showInformationMessage(`DevSnip Pro restored ${restored} custom snippet${restored === 1 ? "" : "s"} after the update.`);
  } catch (error) {
    console.error("DevSnip Pro: could not restore snippets.", error);
  }
}

// ----------------------------------------------------------- tool search

async function searchableTools() {
  const { NAV } = await import("@devsnip/toolkits/layout");
  const { findTool } = await import("@devsnip/toolkits/registry");
  const tools = NAV.flatMap(section => section.entries.map(entry => {
    const tool = entry.tool ? findTool(entry.tool) : undefined;
    const keywords = (tool?.keywords ?? entry.keywords ?? []).slice(0, 5);
    return { label: tool?.title ?? entry.label, description: [section.title, entry.category, ...keywords].filter(Boolean).join(" / "), command: COMMAND_PREFIX + entry.command };
  }));
  return [
    ...tools,
    { label: "Browse All Tools", description: "All sections, with search and pinning", command: `${COMMAND_PREFIX}advancedToolsHub` },
    { label: "Security Hub", description: "Security & Auth / every security scan in one panel", command: `${COMMAND_PREFIX}securityHub` },
    { label: "Milestone & Points Tracker", description: "Progress", command: `${COMMAND_PREFIX}milestoneTracker` }
  ];
}

/** "Search All Tools (Regex)", as the extension registers it in extention.ts. */
function registerUniversalToolSearch(context) {
  context.subscriptions.push(registerTrackedCommand(`${COMMAND_PREFIX}searchTools`, async () => {
    const pattern = await vscode.window.showInputBox({
      title: "Search DevSnip Pro Tools",
      prompt: "Enter a regular expression to match tool names, categories, or commands",
      placeHolder: "e.g. json|schema|rag|calculator",
      value: ""
    });
    if (pattern === undefined) return;
    let matcher;
    try {
      matcher = new RegExp(pattern || ".*", "i");
    } catch (error) {
      vscode.window.showErrorMessage(`Invalid regular expression: ${error instanceof Error ? error.message : String(error)}`);
      return;
    }
    const tools = await searchableTools();
    const matches = tools.filter(tool => matcher.test(`${tool.label} ${tool.description} ${tool.command}`));
    if (!matches.length) {
      vscode.window.showInformationMessage("No DevSnip Pro tools matched that regular expression.");
      return;
    }
    const selected = await vscode.window.showQuickPick(
      matches.map(tool => ({ label: tool.label, description: tool.description, detail: tool.command, command: tool.command })),
      { title: `${matches.length} matching DevSnip Pro tool${matches.length === 1 ? "" : "s"}`, matchOnDescription: true, matchOnDetail: true }
    );
    if (selected) await executeQueuedCommand(selected.command);
  }));
}

// ------------------------------------------- walkthrough and markdown pages

const PAGE_CSS = `
body{margin:0;padding:24px 28px 40px;font-family:var(--vscode-font-family);font-size:13px;line-height:1.6;color:var(--vscode-foreground);background:var(--vscode-editor-background)}
main{max-width:860px;margin:0 auto}
h1{font-size:22px;margin:0 0 4px}h2{font-size:17px;margin:24px 0 8px}h3{font-size:14px;margin:18px 0 6px}
.sub{color:var(--vscode-descriptionForeground);margin:0 0 20px}
a{color:var(--vscode-textLink-foreground)}
code{font-family:var(--vscode-editor-font-family);background:var(--vscode-textCodeBlock-background);padding:1px 4px;border-radius:3px}
pre{background:var(--vscode-textCodeBlock-background);padding:10px 12px;border-radius:6px;overflow:auto}
pre code{padding:0;background:none}
.steps{display:grid;grid-template-columns:260px 1fr;gap:16px}
.list{display:flex;flex-direction:column;gap:6px}
.step{all:unset;cursor:pointer;display:flex;gap:10px;align-items:flex-start;padding:10px 12px;border-radius:6px;border:1px solid var(--vscode-widget-border,transparent)}
.step:hover{background:var(--vscode-list-hoverBackground)}
.step[aria-selected=true]{background:var(--vscode-list-activeSelectionBackground);color:var(--vscode-list-activeSelectionForeground)}
.step .dot{flex:none;width:16px;height:16px;border-radius:50%;border:1px solid var(--vscode-descriptionForeground);margin-top:2px;display:inline-flex;align-items:center;justify-content:center;font-size:11px}
.step.done .dot{background:var(--vscode-testing-iconPassed,#3fb950);border-color:transparent;color:#fff}
.detail{border:1px solid var(--vscode-widget-border,rgba(128,128,128,.25));border-radius:8px;padding:16px 20px}
.detail p.lead{margin-top:0}
.btn{display:inline-block;margin:4px 0 12px;padding:5px 12px;border:0;border-radius:4px;background:var(--vscode-button-background);color:var(--vscode-button-foreground);cursor:pointer;font:inherit}
.btn:hover{background:var(--vscode-button-hoverBackground)}
@media (max-width:640px){.steps{grid-template-columns:1fr}}
`;

const WALKTHROUGH_DONE_KEY = "devsnip.walkthrough.done";

function walkthroughSteps(context) {
  const walkthrough = manifest.contributes?.walkthroughs?.[0];
  const done = new Set(context.globalState.get(WALKTHROUGH_DONE_KEY, []));
  return (walkthrough?.steps ?? []).map(step => {
    const link = /\[([^\]]+)\]\(command:([^)\s]+)\)/.exec(step.description || "");
    const lead = (step.description || "").replace(/\n?\[[^\]]+\]\(command:[^)]+\)/g, "").trim();
    let media = "";
    try {
      const file = path.join(context.extensionPath, step.media?.markdown ?? "");
      media = step.media?.markdown ? renderMarkdown(String(readFileSync(file, "utf8"))) : "";
    } catch {
      media = "";
    }
    // VS Code's own view command opens the Tools sidebar; here that is the DevSnip Pro tool window.
    const command = link ? (link[2] === "workbench.view.extension.myActivityBar" ? "workbench.view.extension.myActivityBar" : link[2]) : undefined;
    const events = (step.completionEvents ?? []).map(e => e.replace(/^onCommand:/, "").replace(/^onView:myView$/, "workbench.view.extension.myActivityBar"));
    return { id: step.id, title: step.title, lead, button: link?.[1], command, media, done: done.has(step.id), events };
  });
}

async function markWalkthrough(context, command) {
  const steps = walkthroughSteps(context);
  const done = new Set(context.globalState.get(WALKTHROUGH_DONE_KEY, []));
  let changed = false;
  for (const step of steps) if (!done.has(step.id) && step.events.includes(command)) { done.add(step.id); changed = true; }
  if (changed) await context.globalState.update(WALKTHROUGH_DONE_KEY, [...done]);
  return changed;
}

/** VS Code's walkthrough for "Get Started" (contributes.walkthroughs), as a page of its own. */
function openWalkthrough(context) {
  const walkthrough = manifest.contributes?.walkthroughs?.[0];
  const { panel, created } = openToolPanel("devsnip.walkthrough", "Get Started with DevSnip Pro", { enableScripts: true });
  const render = () => {
    const steps = walkthroughSteps(context);
    const nonce = getNonce();
    const data = JSON.stringify(steps).replace(/</g, "\\u003c");
    setWebviewHtml(panel.webview, `<!DOCTYPE html><html><head><meta charset="UTF-8"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-${nonce}';"><style>${PAGE_CSS}</style></head><body><main>
<h1>${escapeHtml(walkthrough?.title ?? "Get started with DevSnip Pro")}</h1><p class="sub">${escapeHtml(walkthrough?.description ?? "")}</p>
<div class="steps"><div class="list" role="tablist" id="list"></div><div class="detail" id="detail"></div></div></main>
<script nonce="${nonce}">
const vscode = acquireVsCodeApi();
const steps = ${data};
let current = Math.max(0, steps.findIndex(s => !s.done));
const esc = s => String(s).replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;","\\"":"&quot;","'":"&#39;"}[c]));
function draw() {
  document.getElementById("list").innerHTML = steps.map((s, i) => '<button class="step' + (s.done ? ' done' : '') + '" role="tab" aria-selected="' + (i === current) + '" data-i="' + i + '"><span class="dot">' + (s.done ? '✓' : '') + '</span><span>' + esc(s.title) + '</span></button>').join("");
  const s = steps[current];
  document.getElementById("detail").innerHTML = '<h2>' + esc(s.title) + '</h2><p class="lead">' + esc(s.lead) + '</p>' + (s.command ? '<button class="btn" id="go">' + esc(s.button || "Open") + '</button>' : '') + s.media;
  const go = document.getElementById("go");
  if (go) go.addEventListener("click", () => vscode.postMessage({ type: "run", command: s.command }));
}
document.getElementById("list").addEventListener("click", e => { const b = e.target.closest(".step"); if (!b) return; current = Number(b.dataset.i); draw(); });
window.addEventListener("message", e => { if (e.data && e.data.type === "steps") { e.data.steps.forEach((s, i) => { if (steps[i]) steps[i].done = s.done; }); draw(); } });
draw();
</script></body></html>`);
  };
  if (created) {
    render();
    panel.webview.onDidReceiveMessage(async message => {
      if (message?.type !== "run" || typeof message.command !== "string") return;
      if (message.command === "workbench.view.extension.myActivityBar") {
        await vscode.commands.executeCommand(message.command);
        await markWalkthrough(context, message.command);
        void panel.webview.postMessage({ type: "steps", steps: walkthroughSteps(context) });
        return;
      }
      await executeQueuedCommand(message.command);
    });
    const subscription = onWalkthroughProgress(() => void panel.webview.postMessage({ type: "steps", steps: walkthroughSteps(context) }));
    panel.onDidDispose(() => subscription.dispose());
  }
}

const walkthroughProgress = new vscode.EventEmitter();
const onWalkthroughProgress = walkthroughProgress.event;

/** markdown.showPreview (What's New opens the changelog): the file rendered by the extension's own Markdown renderer. */
async function showMarkdownPreview(context, target) {
  const uri = target instanceof vscode.Uri ? target : vscode.Uri.file(String(target?.fsPath ?? target));
  const text = new TextDecoder().decode(await vscode.workspace.fs.readFile(uri));
  const name = path.basename(uri.fsPath);
  const { panel } = openToolPanel(`devsnip.markdown:${uri.toString()}`, `Preview ${name}`, { enableScripts: false });
  setWebviewHtml(panel.webview, `<!DOCTYPE html><html><head><meta charset="UTF-8"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src https: data:;"><style>${PAGE_CSS}</style></head><body><main>${renderMarkdown(text)}</main></body></html>`);
}

// -------------------------------------------------------------- activate

let activated = false;

function activate() {
  if (activated) return;
  activated = true;
  const context = extensionContext();
  const installSnapshot = snapshotInstall(context);
  const snippetsFolderPath = path.join(context.extensionPath, "custom");

  setMilestoneContext(context);
  const snippetBackup = context.globalStorageUri ? path.join(context.globalStorageUri.fsPath, "snippets") : undefined;
  setSnippetBackupFolder(snippetBackup);
  if (snippetBackup) void restoreSnippetsAfterUpdate(snippetsFolderPath, snippetBackup, context.extensionPath);
  setThemeAccess({
    lock: themeId => themeLockFor(getUserStats(context), themeId),
    unlock: async themeId => {
      const lock = themeLockFor(getUserStats(context), themeId);
      return !lock || (await buyReward(context, lock.rewardId)) === "ok";
    },
    grant: themeId => grantKeptTheme(context, themeId)
  });
  initThemes(context);
  const version = String(context.extension?.packageJSON?.version ?? "0.0.0");
  const install = classifyInstall(installSnapshot, version);
  void initActivation(context, version, {
    isNewInstall: install.firstRun,
    previousVersion: install.previousVersion,
    recordDiscovery: id => recordDiscovery(context, id)
  }).catch(error => console.error("DevSnip Pro: onboarding state could not be loaded.", error));

  let toolsSidebar;
  setUsageRecorder(command => {
    void autoRecordToolUsage(command);
    toolsSidebar?.recordUsage(command);
    noteCommand(command);
    void markWalkthrough(context, command).then(changed => { if (changed) walkthroughProgress.fire(undefined); });
    // The IDE keeps its own "Recently used" for the pages it renders itself.
    toHost({ type: "__usage", command });
  });
  setCommandObserver(trackCommand);

  const access = new FeatureAccessService(context, {
    balance: () => getPointsBalance(context),
    spend: (amount, reason) => redeemPoints(context, amount, reason),
    refund: (amount, reason) => refundPoints(context, amount, reason)
  });
  pointsAccess = { context, access };

  toolsSidebar = new ToolsSidebarProvider(context);
  setTreeRefreshCallback(() => toolsSidebar?.refresh());
  context.subscriptions.push(vscode.window.registerWebviewViewProvider(TOOLS_VIEW_ID, toolsSidebar));

  const registrations = [
    ["snippets", () => registerLazyCommands(context.subscriptions, SNIPPET_COMMANDS, async () => {
      (await import("@devsnip/commands/createSnippetCommand")).registerCreateSnippetCommand(context);
      (await import("@devsnip/commands/showSnippetsCommand")).registerShowSnippetsCommand(context, snippetsFolderPath);
      (await import("@devsnip/commands/frameworkSnippets")).registerFrameworkSnippetsCommand(context);
    })],
    ["workspace hygiene", () => registerLazyCommands(context.subscriptions, HYGIENE_COMMANDS, async () => {
      (await import("@devsnip/commands/listAndRemoveConsoleLogsCommand")).registerListAndRemoveConsoleLogsCommand(context);
      (await import("@devsnip/commands/removeUnusedImportsCommand")).registerRemoveUnusedImportsCommand(context);
      (await import("@devsnip/commands/readmeManager")).registerReadmeManagerCommand(context);
    })],
    ["debug logs", () => registerLazyCommands(context.subscriptions, DEBUG_LOG_COMMANDS, async () => {
      (await import("@devsnip/debug/commands")).registerDebugLogCommands(context);
    })],
    ["IDE pages", () => registerNativeCommands(context)],
    ["premium commands", () => registerPremiumCommands(context, access)],
    ["dependencies & installation", () => registerLazyCommands(context.subscriptions, [`${COMMAND_PREFIX}dependencyManager`], async () => {
      (await import("@devsnip/commands/dependencyManager")).registerDependencyManagerCommand(context);
    })],
    ["tool hubs", () => registerHubCommands(context)],
    ["security tools", () => registerLazyCommands(context.subscriptions, SECURITY_COMMANDS, async () => {
      (await import("@devsnip/commands/securityTools")).registerSecurityToolsCommands(context);
    })],
    ["database client", () => registerDatabaseClientCommand(context)],
    ["CSV & Excel viewer", () => registerSpreadsheetViewer(context)],
    ["appearance themes", () => registerThemeCommand(context)],
    ["onboarding", () => registerOnboardingCommands(context)],
    ["milestone tracker", () => registerMilestoneTrackerCommand(context)],
    ["tool search", () => registerUniversalToolSearch(context)],
    ["walkthrough", () => {
      context.subscriptions.push(vscode.commands.registerCommand(WALKTHROUGH_COMMAND, () => openWalkthrough(context)));
      context.subscriptions.push(vscode.commands.registerCommand("markdown.showPreview", target => showMarkdownPreview(context, target)));
    }]
  ];
  const failed = [];
  for (const [name, register] of registrations) {
    try {
      register();
    } catch (error) {
      failed.push(name);
      console.error(`DevSnip Pro: failed to register ${name}.`, error);
      vscode.window.showErrorMessage(`DevSnip Pro could not load its ${name} commands: ${error instanceof Error ? error.message : String(error)}`);
    }
  }

  // Native pages (tools, REST client, scanners) follow the theme in use, including a locked-theme preview.
  const themeChanged = () => toHost({ type: "__theme", id: currentThemeId() });
  onDidChangeTheme(themeChanged);
  onDidChangeLockedPreview(themeChanged);
  onDidChangePoints(balance => toHost({ type: "__points", balance }));

  initAnalytics(context, installSnapshot);
  toHost({ type: "__ready", commands: registeredCommands(), failed, theme: currentThemeId(), points: getPointsBalance(context) });
}

/** The points ledger, for the REST API Client page (its premium features cost points, as in VS Code). */
let pointsAccess;

onHostEvent("__ledger", async message => {
  const reply = (result, error) => toHost({ type: "__ledgerResult", id: message.id, result: result ?? null, error: error ?? null });
  if (!pointsAccess) return reply(null, "DevSnip Pro is still starting.");
  const { context } = pointsAccess;
  try {
    if (message.op === "balance") return reply(getPointsBalance(context));
    if (message.op === "spend") return reply(await redeemPoints(context, Number(message.amount) || 0, String(message.reason || "REST API Client")));
    if (message.op === "refund") { await refundPoints(context, Number(message.amount) || 0, String(message.reason || "REST API Client")); return reply(true); }
    if (message.op === "discovery") { await recordDiscovery(context, String(message.discovery)); return reply(true); }
    reply(null, `Unknown ledger operation ${message.op}`);
  } catch (error) {
    reply(null, error instanceof Error ? error.message : String(error));
  }
});

/** The IDE runs a command here: from Find Action, a menu, the keyboard, or the IDE's own pages. */
onHostEvent("__exec", async message => {
  if (message.editors) setEditorState(message.editors);
  const reply = (error, result) => toHost({ type: "__execResult", id: message.id, error: error ?? null, result: result ?? null });
  try {
    const args = (message.args || []).map(argFromWire);
    if (message.queued) await executeQueuedCommand(message.command);
    else await vscode.commands.executeCommand(message.command, ...args);
    reply(null);
  } catch (error) {
    console.error(`DevSnip Pro: ${message.command} failed.`, error);
    reply(error instanceof Error ? error.message : String(error));
  }
});

globalThis.DevSnipHost = { activate, onStream };

// sqlite.ts asks where sql.js's files are; the asm.js build needs none.
globalThis.__devsnipRequireResolve = name => `/devsnip-pro/node_modules/${name}`;

// The REST services (axios) and the endpoint scan's TLS probe ask the IDE through this, as on the REST client page.
globalThis.__devsnipApiHost = {
  rpc: (method, params) => rpc(method, params),
  onStream,
  deliver: () => undefined,
  storage: { get: () => undefined, set: async () => undefined, keys: () => [] }
};

try {
  activate();
} catch (error) {
  console.error("DevSnip Pro: the extension host could not start.", error);
  toHost({ type: "__ready", commands: registeredCommands(), failed: ["activate"], error: error instanceof Error ? error.message : String(error) });
}
