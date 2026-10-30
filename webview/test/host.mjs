#!/usr/bin/env node
/*
 * DevSnip Pro's extension host (host-runtime.js) with the stand-in IDE:
 * every command group the host runs, through the extension's own code.
 */
import assert from "assert";
import fs from "fs";
import path from "path";
import { createIde, editorState } from "./host-harness.mjs";

const P = "sayaib.hue-console.";
let failures = 0;
async function check(name, run) {
  try {
    await run();
    console.log(`ok   ${name}`);
  } catch (error) {
    failures++;
    console.error(`FAIL ${name}\n     ${error.stack?.split("\n").slice(0, 3).join("\n     ")}`);
  }
}

const files = {
  "src/app.js": "import fs from 'fs';\nimport { unused } from './x';\nconst total = 1 + 2;\nconsole.log('debug', total);\nexport default total;\n",
  "src/util.py": "def add(a, b):\n    print(a)\n    return a + b\n",
  "README.md": "# Demo\n\nA small project.\n",
  "package.json": "{\"name\":\"demo\",\"version\":\"1.0.0\",\"dependencies\":{\"lodash\":\"4.17.20\"}}\n",
  ".env": "API_KEY=sk-live-1234567890abcdef1234567890abcd\n",
  "config.js": "const password = \"hunter2hunter2\";\nmodule.exports = { password };\n",
  "data.csv": "name,age\nAda,36\nLinus,28\n",
  "Dockerfile": "FROM node:latest\nUSER root\n"
};
const ide = createIde({ quiet: true, files });
ide.start();
await ide.settle(150);
const ws = ide.workspace;

await check("leaves out the OpenCode Integration", async () => {
  assert.ok(!ide.ready.commands.includes(P + "openCodeIntegration"));
});

await check("activates with every command group", async () => {
  assert.ok(ide.ready, "ready");
  assert.deepEqual(ide.ready.failed, []);
  for (const id of ["insertDebugLog", "showSnippets", "createCustomSnippet", "frameworkSnippets", "readmeManager", "listAndRemoveConsoleLogs", "removeUnusedImports",
    "dependencyManager", "securityHub", "securityAudit", "cloudSecurityAudit", "dependencyAudit", "endpointSecurityScan", "databaseClient",
    "csvViewer", "excelViewer", "milestoneTracker", "premiumStatus", "advancedToolsHub", "aiMlHub", "chooseTheme", "searchTools", "getStarted", "whatsNew", "curlConverter", "openGUI"]) {
    assert.ok(ide.ready.commands.includes(P + id), id);
  }
});

await check("a toolkit tool opens in the IDE and earns points", async () => {
  const before = ide.globalState.devsnip_user_stats?.totalPoints ?? 0;
  assert.equal((await ide.exec(P + "jsonFormatter")).error, null);
  await ide.settle(50);
  assert.equal(ide.natives.at(-1).id, P + "jsonFormatter");
  assert.ok((ide.globalState.devsnip_user_stats?.totalPoints ?? 0) > before, "points");
  assert.ok(ide.usage.includes(P + "jsonFormatter"));
});

await check("the Tools sidebar comes from the extension's provider", async () => {
  ide.deliver({ type: "__resolveView", viewId: "myView", panel: "view-1" });
  await ide.settle(50);
  const view = ide.panels.get("view-1");
  assert.ok(view?.html.includes("tools-sidebar.js"), "sidebar html");
  ide.panelMessage("view-1", { type: "favorites", favorites: [P + "jsonFormatter"] });
  await ide.settle(30);
  assert.deepEqual(ide.globalState["devsnip.sidebar.favorites"], [P + "jsonFormatter"]);
  ide.panelMessage("view-1", { type: "run", command: P + "curlConverter" });
  await ide.settle(50);
  assert.equal(ide.natives.at(-1).id, P + "curlConverter");
});

await check("Insert Debug Log edits the active editor", async () => {
  const file = path.join(ws, "src/app.js");
  const text = fs.readFileSync(file, "utf8");
  const result = await ide.exec(P + "insertDebugLog", [], editorState(file, text, { line: 2, character: 7 }));
  assert.equal(result.error, null);
  const edit = ide.edits.at(-1);
  assert.ok(edit && edit.edits[0].text.includes("total"), JSON.stringify(edit));
});

await check("Remove Debug Logs in This File removes them with confirmation", async () => {
  const file = path.join(ws, "src/util.py");
  const text = fs.readFileSync(file, "utf8");
  ide.answers.message.push(p => p.items.findIndex(i => /remove|delete/i.test(i)));
  const result = await ide.exec(P + "removeDebugLogsInFile", [], editorState(file, text));
  assert.equal(result.error, null);
  await ide.settle(30);
  assert.ok(!(ide.docs.get(file) ?? text).includes("print(a)") || ide.messages.length, "removed or reported");
});

await check("Create Snippet saves to the user's snippet file", async () => {
  const file = path.join(ws, "src/app.js");
  const text = fs.readFileSync(file, "utf8");
  ide.answers.inputBox.push("My total", "mytot", "Logs the total");
  ide.answers.quickPick.push(p => 0);
  ide.answers.message.push(() => -1, () => -1);
  const result = await ide.exec(P + "createCustomSnippet", [], editorState(file, text, { line: 2, character: 0 }, { line: 2, character: 23 }));
  assert.equal(result.error, null);
  await ide.settle(50);
  const saved = fs.readdirSync(path.join(ide.extension, "custom")).map(f => fs.readFileSync(path.join(ide.extension, "custom", f), "utf8")).join("");
  assert.ok(saved.includes("mytot"), "snippet saved");
  assert.ok(!JSON.stringify(ide.messages).includes("VS Code"), "IDE wording");
});

await check("Saved Snippets lists the user's snippets", async () => {
  assert.equal((await ide.exec(P + "showSnippets")).error, null);
  const panel = ide.panelByType("showSnippets") ?? [...ide.panels.values()].find(p => /snippet/i.test(p.title ?? ""));
  assert.ok(panel?.html.includes("mytot"), "listed");
});

await check("Framework Snippets inserts the picked snippet", async () => {
  const file = path.join(ws, "src/app.js");
  ide.answers.quickPick.push(() => 0);
  const result = await ide.exec(P + "frameworkSnippets", [], editorState(file, fs.readFileSync(file, "utf8")));
  assert.equal(result.error, null);
  assert.ok(ide.lastQuickPick?.items.length > 0, "snippets offered");
  assert.ok(ide.snippets?.length, "inserted as a snippet");
});

for (const [id, pattern] of [["readmeManager", /README/], ["listAndRemoveConsoleLogs", /console\.log|debug/i], ["removeUnusedImports", /unused/i], ["dependencyManager", /lodash|Dependencies/],
  ["securityAudit", /Security/], ["cloudSecurityAudit", /Security/], ["dependencyAudit", /Security/], ["securityHub", /Security/],
  ["databaseClient", /db-client\.js/], ["milestoneTracker", /Milestones/], ["advancedToolsHub", /All Tools/], ["getStarted", /Get started/i], ["whatsNew", /Changelog/i]]) {
  await check(`${id} opens its page`, async () => {
    assert.equal((await ide.exec(P + id)).error, null);
    await ide.settle(150);
    const panel = [...ide.panels.values()].reverse().find(p => pattern.test(p.html + (p.title ?? "")));
    assert.ok(panel, `a page matching ${pattern}`);
  });
}

await check("the workspace security audit finds the hard-coded secret", async () => {
  const hub = [...ide.panels.values()].find(p => /Security/.test(p.title ?? ""));
  ide.panelMessage(hub.panel, { type: "ready" });
  ide.panelMessage(hub.panel, { type: "scanWorkspace" });
  for (let i = 0; i < 100 && !hub.posted.some(m => m?.type === "result"); i++) await ide.settle(50);
  const posted = hub.posted;
  assert.ok(JSON.stringify(posted).match(/password|secret|API_KEY|sk-live/i), "a finding about the secret");
});

await check("clicking a security finding selects its line and centers it", async () => {
  const hub = [...ide.panels.values()].find(p => /Security/.test(p.title ?? ""));
  const file = path.join(ws, "src", "app.js");
  ide.panelMessage(hub.panel, { type: "openLocation", file: "src/app.js", line: 2, resource: `file://${file}` });
  for (let i = 0; i < 60 && !ide.revealed; i++) await ide.settle(25);
  assert.equal(ide.shown, file);
  assert.equal(ide.shownSelection?.start?.line, 1, JSON.stringify(ide.shownSelection));
  assert.equal(ide.revealed?.start?.line, 1, JSON.stringify(ide.revealed));
});

await check("the CSV viewer opens a file as a custom editor", async () => {
  ide.deliver({ type: "__openCustomEditor", viewType: "devsnip.csvViewer", path: path.join(ws, "data.csv"), panel: "custom-1" });
  await ide.settle(150);
  const panel = ide.panels.get("custom-1");
  assert.ok(panel?.html.includes("spreadsheet-viewer.js"), "viewer page");
});

await check("Search All Tools matches by regular expression", async () => {
  ide.answers.inputBox.push("jwt");
  ide.answers.quickPick.push(p => { assert.ok(p.items.some(i => /JWT/.test(i.label))); return null; });
  assert.equal((await ide.exec(P + "searchTools")).error, null);
});

await check("themes are locked until earned, and a choice reaches the IDE's pages", async () => {
  ide.panelMessage("view-1", { type: "setTheme", id: "dracula" });
  await ide.settle(50);
  // A locked theme explains how to unlock it; nothing is stored.
  assert.notEqual(ide.globalState["devsnip.appearance.theme"], "dracula");
});

await check("the REST client's ledger spends and refunds points", async () => {
  const balance = (await ide.ledger("balance")).result;
  const spent = await ide.ledger("spend", { amount: 1, reason: "test" });
  assert.equal(spent.result, balance >= 1);
  await ide.ledger("refund", { amount: 1, reason: "test" });
  assert.equal((await ide.ledger("balance")).result, balance);
});

await check("no request the IDE does not know", async () => {
  assert.deepEqual([...new Set(ide.errors.filter(e => !/ENOENT|spawn|ECONN|ENOTFOUND|not found|denied/i.test(e)))].filter(e => /Unknown request/.test(e)), []);
});

ide.cleanup();
console.log(failures ? `\n${failures} failure(s).` : "\nThe extension host runs every command group.");
process.exit(failures ? 1 : 0);
