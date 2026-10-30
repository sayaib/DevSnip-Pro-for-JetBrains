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
  ["Queries run inside VS Code, so", "Queries run inside the IDE, so"],
  ["never leave VS Code", "never leave the IDE"],
  ["so VS Code stays responsive", "so the IDE stays responsive"],
  ["locally in VS Code unless", "locally in the IDE unless"],
  ["of the VS Code process", "of the IDE process"],
  ["the environment VS Code was started from", "the environment the IDE was started from"],
  // Themes: System Default follows the IDE's theme.
  ["Follows your VS Code color theme", "Follows your IDE color theme"],
  ["\"VS Code theme\"", "\"IDE theme\"", "service.ts"],
  // The scanners' User-Agent names the host.
  ["(VS Code ${", "(JetBrains IDE ${"],
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
