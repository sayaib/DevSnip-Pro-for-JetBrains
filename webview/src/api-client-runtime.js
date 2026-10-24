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
 * - access: every feature is available, since Milestones & rewards (the
 *   points balance) are not ported to the IDE yet.
 *
 * OAuth token requests are answered by the IDE itself (feature:oauthToken /
 * feature:applyOauthToken), so access tokens stay in the IDE password safe.
 */
import { buildCatalog, handleFeatureMessage } from "@devsnip/commands/api-client-features";
import { FeatureAccessService } from "@devsnip/premium/feature-access";
import { getFeature } from "@devsnip/premium/feature-registry";
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

/** Every known, enabled feature may run, free and without daily limits. */
class IdeFeatureAccess extends FeatureAccessService {
  check(featureId) {
    const feature = getFeature(featureId);
    if (!feature || !feature.enabled) return super.check(featureId);
    return { allowed: true, featureId };
  }

  costFor() {
    return 0;
  }

  limitFor() {
    return undefined;
  }
}

const extensionContext = { globalState, workspaceState: globalState, subscriptions: [] };
const ledger = { balance: () => 0, spend: async () => true, refund: async () => undefined };
const access = new IdeFeatureAccess(extensionContext, ledger);
const collections = new CollectionStore(extensionContext, access);

const featureContext = {
  access,
  collections,
  extensionContext,
  post: message => bridge().deliver(message),
  sendHttp: (request, options) => bridge().rpc("http.send", { request, background: Boolean(options && options.background) })
};

/** The catalog as the page renders it: no prices, no balance (there is nothing to pay). */
function catalog() {
  const result = buildCatalog(featureContext);
  for (const category of result.categories) {
    for (const group of category.groups) {
      for (const feature of group.features) {
        feature.locked = false;
        feature.pointCost = 0;
        delete feature.reason;
        delete feature.message;
        delete feature.pointsShort;
        delete feature.limit;
      }
    }
  }
  result.pointBalance = 0;
  return result;
}

const originalPost = featureContext.post;
featureContext.post = message => {
  if (message && message.command === "featureCatalog") {
    const { command: _command, ...rest } = message;
    originalPost({ command: "featureCatalog", ...rest, ...catalog() });
    return;
  }
  originalPost(message);
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
  catalog
};
