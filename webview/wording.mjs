/*
 * The extension's user-facing text that names VS Code's own UI, said the
 * JetBrains way, plus one check that assumes VS Code (last entry). Exact
 * phrases only, so nothing else in the code changes; build.mjs applies them to the bundles, the pages' scripts and the
 * extension files. A phrase that no longer exists in the extension is
 * reported by the build, so the list stays current.
 */
export const WORDING = [
  // Snippets: completion reads the user's snippet files as they change; nothing needs a reload.
  ["VS Code loads contributed snippets at startup, so reload the window to start using it.", "It is offered in code completion right away.", "createSnippetCommand.ts"],
  ["\"Reload Window\"", "\"OK\"", "createSnippetCommand.ts"],
  ["from the Command Palette. Saved snippets appear here, and in IntelliSense after a window reload.", "from Find Action. Saved snippets appear here, and in code completion right away.", "showSnippetsCommand.ts"],
  ["Snippets are stored with the extension and loaded by VS Code at startup.", "Snippets are stored with DevSnip Pro and offered in code completion.", "showSnippetsCommand.ts"],
  // Editors, files and settings.
  ["VS Code could not ", "The IDE could not "],
  ["then reopen VS Code so it picks up the new PATH.", "then restart the IDE so it picks up the new PATH."],
  ["then reopen VS Code.", "then restart the IDE."],
  [", select it in VS Code, and press Rescan.", ", select it as the project interpreter, and press Rescan."],
  ["Converting files needs VS Code's file dialogs", "Converting files needs the IDE's file dialogs"],
  // Secrets live in the IDE's password safe.
  ["held in VS Code secret storage", "held in the IDE password safe"],
  ["through VS Code SecretStorage", "through the IDE password safe"],
  ["It is kept in VS Code's secret storage", "It is kept in the IDE password safe"],
  // Where things run.
  ["Queries run on a worker thread, so a long one never freezes VS Code and can be cancelled. On VS Code builds without node:sqlite, sql.js opens files up to 512 MB and cannot cancel.", "Queries run with sql.js, which opens files up to 512 MB and cannot cancel a running query.", "types.ts"],
  ["from VS Code's environment, else", "from the environment the IDE was started from, else", "db-client.js"],
  ["never leave VS Code", "never leave the IDE"],
  ["so VS Code stays responsive", "so the IDE stays responsive"],
  ["locally in VS Code unless", "locally in the IDE unless"],
  ["of the VS Code process", "of the IDE process"],
  ["the environment VS Code was started from", "the environment the IDE was started from"],
  // Themes: System Default follows the IDE's theme.
  ["Follows your VS Code color theme", "Follows your IDE color theme"],
  ["\"VS Code theme\"", "\"IDE theme\"", "service.ts"],
  // Theme Studio: VS Code Adaptive is IDE Adaptive, and pairs DevSnip themes with the IDE's light and dark themes.
  ["VS Code Adaptive keeps VS Code's.", "IDE Adaptive keeps the IDE's.", "theme-studio.js"],
  ["VS Code Adaptive", "IDE Adaptive"],
  ["shortLabel: \"VS Code\"", "shortLabel: \"IDE\"", "service.ts"],
  ["to follow VS Code.", "to follow the IDE.", "service.ts"],
  ["pairing them with VS Code.", "pairing them with the IDE.", "service.ts"],
  ["{ label: \"VS Code\", value: \"\" }", "{ label: \"IDE\", value: \"\" }", "custom.ts"],
  ["{ label: \"VS Code editor\", value: \"\" }", "{ label: \"IDE editor\", value: \"\" }", "custom.ts"],
  [" to match VS Code\"", " to match the IDE\"", "theme-studio.js"],
  ["\"VS Code is \"", "\"The IDE is \"", "theme-studio.js"],
  ["pair them with VS Code", "pair them with the IDE", "theme-studio.js"],
  ["now follows VS Code light and dark", "now follows the IDE light and dark", "theme-studio.js"],
  ["Follow VS Code", "Follow the IDE"],
  ["Match VS Code light and dark", "Match the IDE light and dark", "studio-page.ts"],
  ["while VS Code is dark and another while it is light. High-contrast VS Code themes", "while the IDE is dark and another while it is light. High-contrast IDE themes", "studio-page.ts"],
  ["While VS Code is dark", "While the IDE is dark", "studio-page.ts"],
  ["While VS Code is light", "While the IDE is light", "studio-page.ts"],
  ["Leave a font on VS Code to keep your editor settings.", "Leave a font on IDE to keep your editor settings.", "studio-page.ts"],
  ["with VS Code's light/dark switch", "with the IDE's light/dark switch", "activation.ts"],
  ["and VS Code light/dark pairing", "and IDE light/dark pairing", "tools-sidebar.js"],
  ["follows your VS Code theme (light, dark or high contrast)", "follows your IDE theme (light, dark or high contrast)", "theme.md"],
  ["while VS Code is dark and another while it is light.", "while the IDE is dark and another while it is light.", "theme.md"],
  ["or keep VS Code's.", "or keep the IDE's.", "theme.md"],
  // Feedback names the IDE's version.
  ["DevSnip Pro version, VS Code version, and operating system.", "DevSnip Pro version, IDE version, and operating system.", "milestone-tracker.js"],
  // The scanners' User-Agent names the host.
  ["(VS Code ${", "(JetBrains IDE ${"],
  // SQLite: the IDE's extension host has no node:sqlite, so sql.js opens files of up to 512 MB.
  ["This SQLite file is too large to open without node:sqlite", "This SQLite file is too large for the IDE to open", "sqlite.ts"],
  ["Update VS Code: versions with Node 22.5 or later read SQLite files of any size in place.", "Open a smaller copy, or query the file with the sqlite3 command-line tool.", "sqlite.ts"],
  // Places in the window.
  ["file in the Explorer and choose ", "file in the Project view and choose "],
  ["For CSV files use \", h(\"b\", null, \"Open With…\"), \" or the table button in the editor title bar.", "For CSV files use the \", h(\"b\", null, \"Table\"), \" tab at the bottom of the editor."],
  ["The **DevSnip Pro** icon in the Activity Bar opens the Tools sidebar.", "The **DevSnip Pro** tool window holds the Tools sidebar."],
  ["You can also run any tool from the Command Palette: type `DevSnip Pro`.", "You can also run any tool from Find Action (Ctrl+Shift+A, or Cmd+Shift+A on macOS): type `DevSnip Pro`."],
  // Not text: the extension takes a host without Node for VS Code for the web and hides desktop tools.
  // A page has no Node, but the IDE is a desktop host (environment.ts and layout.ts each make this check).
  ["typeof process === \"undefined\" || !process.versions?.node", "false"]
];

/** Applies the phrases that belong to `file` (all of them when a phrase names no file); records which were used. */
export function reword(text, file, used) {
  let out = text;
  for (const [index, [from, to, only]] of WORDING.entries()) {
    if (only && !file.endsWith(only)) continue;
    if (!out.includes(from)) continue;
    out = out.split(from).join(to);
    used?.add(index);
  }
  return out;
}
