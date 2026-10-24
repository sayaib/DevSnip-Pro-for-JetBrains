/*
 * The DevSnip Pro toolkit, running inside the JetBrains plugin's embedded
 * browser. The tools are the VS Code extension's own (bundled from its
 * source); this file only replaces the extension host's runner
 * (src/toolkits/runner.ts): same select normalisation, same outcome shape,
 * same 90 second limit.
 */
import { ALL_TOOLS, findTool } from "@devsnip/toolkits/registry";
import { ToolInputError } from "@devsnip/toolkits/types";

const RUN_TIMEOUT_MS = 90_000;

class TimeoutError extends Error {}

/** runner.ts normalizeSelects: a select only ever receives one of its options. */
function normalizeSelects(tool, values) {
  const out = { ...values };
  for (const field of tool.fields) {
    if (field.kind !== "select" || !field.options?.length) continue;
    const value = out[field.id];
    if (value === undefined || field.options.some(option => option.value === String(value))) continue;
    out[field.id] = field.default !== undefined ? field.default : field.options[0].value;
  }
  return out;
}

/** runner.ts sanitizeValues: only plain string / number / boolean values reach a tool. */
function sanitizeValues(raw) {
  const values = {};
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return values;
  for (const [key, value] of Object.entries(raw)) {
    if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") values[key] = value;
  }
  return values;
}

/** runner.ts runTool. */
async function runTool(tool, rawValues, ctx, action) {
  let timer;
  const values = normalizeSelects(tool, sanitizeValues(rawValues));
  try {
    const timeout = new Promise((_, reject) => { timer = setTimeout(() => reject(new TimeoutError()), RUN_TIMEOUT_MS); });
    const result = await Promise.race([Promise.resolve().then(() => tool.run(values, ctx, action)), timeout]);
    return { outcome: "success", result };
  } catch (error) {
    if (error instanceof ToolInputError) return { outcome: "input_error", error: error.message };
    if (error instanceof TimeoutError) return { outcome: "timeout", error: "The tool did not finish within 90 seconds." };
    console.error(`DevSnip Pro: ${tool.id} failed.`, error);
    return { outcome: "error", error: `Something went wrong: ${error instanceof Error ? error.message : String(error)}` };
  } finally {
    if (timer) clearTimeout(timer);
  }
}

/** runner.ts allExamples: static examples plus presets computed from the context (saved templates). */
async function allExamples(tool, ctx) {
  let dynamic = [];
  try {
    dynamic = tool.dynamicExamples ? await tool.dynamicExamples(ctx) : [];
  } catch (error) {
    console.warn(`DevSnip Pro: could not load presets for ${tool.id}.`, error);
  }
  return [...(tool.examples ?? []), ...dynamic];
}

globalThis.DevSnipToolkit = {
  ids: () => ALL_TOOLS.map(t => t.id),
  find: id => findTool(id),
  run(id, values, ctx, action) {
    const tool = findTool(id);
    if (!tool) return Promise.resolve({ outcome: "error", error: `Unknown tool ${id}` });
    const validAction = typeof action === "string" && tool.actions?.some(a => a.id === action) ? action : undefined;
    return runTool(tool, values, ctx, validAction);
  },
  examples(id, ctx) {
    const tool = findTool(id);
    return tool ? allExamples(tool, ctx) : Promise.resolve([]);
  }
};
