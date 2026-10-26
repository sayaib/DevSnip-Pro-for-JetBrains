/*
 * The REST API Client's feature system (collections, chaining, assertions,
 * batch and load tests, JSON tools, code / SDK / mock generation, the AI and
 * RAG tools, the endpoint security scan, ...) running in the JetBrains
 * plugin's embedded browser. The handlers are the extension's own
 * (src/commands/api-client-features.ts, bundled from its source); this file
 * only supplies the FeatureContext the extension host would:
 *
 * - sendHttp: the client's own sender, in the IDE (shared cookies, history);
 * - collections and the other global state: kept by the IDE;
 * - HTTP made by the services (axios) and TLS probes: sent by the IDE;
 * - access: premium features cost points from the extension host's
 *   milestone tracker, as in VS Code.
 *
 * OAuth token requests are answered by the IDE itself (feature:oauthToken /
 * feature:applyOauthToken), so access tokens stay in the IDE password safe.
 */
import { buildCatalog, handleFeatureMessage } from "@devsnip/commands/api-client-features";
import { FeatureAccessService } from "@devsnip/premium/feature-access";
import { CollectionStore } from "@devsnip/services/collections";

const HOST_COMMANDS = new Set(["feature:oauthToken", "feature:applyOauthToken"]);

function bridge() {
  const host = globalThis.__devsnipApiHost;
  if (!host) throw new Error("The IDE connection is not available.");
  return host;
}

/** vscode.Memento over the IDE's storage for this page. */
const globalState = {
  get(key, fallback) {
    const value = bridge().storage.get(key);
    return value === undefined ? fallback : value;
  },
  update(key, value) {
    return bridge().storage.set(key, value);
  },
  keys() {
    return bridge().storage.keys();
  }
};

/**
 * Premium features cost points, as in VS Code: the balance is the extension
 * host's (the IDE keeps this page's copy current), and spending or refunding
 * goes to the extension's milestone tracker through the IDE.
 */
let balance = 0;
/** The page's copy of the balance (the milestone stand-in reads it for "remaining points"). */
function setBalance(value) {
  balance = Number(value) || 0;
  globalThis.__devsnipPoints = balance;
}
setBalance((globalThis.__devsnipHost || {}).points);
const ledger = {
  balance: () => balance,
  async spend(amount, reason) {
    const answer = await bridge().rpc("points.spend", { amount, reason });
    if (typeof answer?.balance === "number") setBalance(answer.balance);
    return answer?.ok === true;
  },
  async refund(amount, reason) {
    const answer = await bridge().rpc("points.refund", { amount, reason });
    if (typeof answer?.balance === "number") setBalance(answer.balance);
  }
};

const extensionContext = { globalState, workspaceState: globalState, subscriptions: [] };
const access = new FeatureAccessService(extensionContext, ledger);
const collections = new CollectionStore(extensionContext, access);

const featureContext = {
  access,
  collections,
  extensionContext,
  post: message => bridge().deliver(message),
  sendHttp: (request, options) => bridge().rpc("http.send", { request, background: Boolean(options && options.background) })
};

/** The balance changed elsewhere (a tool run, the milestone page): the badge and the catalog's prices follow it. */
globalThis.__devsnipInternal = message => {
  if (!message || message.type !== "__points") return;
  setBalance(message.balance);
  bridge().deliver({ command: "showPoints", points: balance });
};

globalThis.DevSnipApiClient = {
  /** Whether the page handles this message itself (the IDE handles everything else). */
  owns(message) {
    const command = message && typeof message.command === "string" ? message.command : "";
    return command.startsWith("feature:") && !HOST_COMMANDS.has(command);
  },
  handle(message) {
    return handleFeatureMessage(message, featureContext).catch(error => {
      console.error("DevSnip Pro: a REST client feature failed.", error);
      featureContext.post({ command: "featureError", featureId: String(message.featureId || message.command), error: error instanceof Error ? error.message : String(error) });
    });
  },
  catalog: () => buildCatalog(featureContext)
};
