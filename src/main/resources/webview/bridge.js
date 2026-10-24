/*
 * The VS Code webview API, provided to DevSnip Pro's own pages inside the
 * JetBrains plugin's embedded browser (JCEF).
 *
 * - acquireVsCodeApi(): postMessage goes to the IDE (Kotlin) through a
 *   JCEF query; getState/setState persist per page like a VS Code webview.
 * - Messages from the IDE arrive as `message` events, as in VS Code.
 * - Tool pages run their tool in the page (window.DevSnipToolkit) instead of
 *   an extension host; the tool's ToolContext calls back into the IDE.
 * - Scanner pages (osv, sca) run the extension's panel code in the page
 *   (scanner-runtime.js): the page's messages go to it, and its I/O goes to
 *   the IDE through window.__devsnipRpc.
 * - The REST API Client page runs its feature system in the page
 *   (window.DevSnipApiClient); its HTTP, files and storage go to the IDE
 *   through window.__devsnipApiHost.
 *
 * The host sets window.__devsnipHost ({ page, toolId, storage, workspaceName,
 * networkInterfaces, platform }) and window.__devsnipQuery before this runs.
 */
(function () {
  "use strict";
  var host = window.__devsnipHost || {};
  var stateKey = "devsnip-state:" + (host.page || "page") + ":" + (host.toolId || "");
  var pending = {};
  var nextRpc = 1;
  var streams = {};

  function toHost(message) {
    try {
      window.__devsnipQuery(JSON.stringify(message));
    } catch (error) {
      console.error("DevSnip Pro: could not reach the IDE", error);
    }
  }

  function deliver(message) {
    window.dispatchEvent(new MessageEvent("message", { data: message }));
  }

  /** Called by the IDE with each message for this page. */
  window.__devsnipReceive = function (message) {
    if (message && message.type === "__rpcResult") {
      var call = pending[message.id];
      delete pending[message.id];
      if (!call) return;
      if (message.error) call.reject(Object.assign(new Error(message.error), message.errorData || {}));
      else call.resolve(message.result);
      return;
    }
    if (message && message.type === "__stream") {
      var listener = streams[message.id];
      if (listener) listener(message.event, message.value);
      if (message.event === "end" || message.event === "error") delete streams[message.id];
      return;
    }
    if (message && message.type === "__storage") {
      host.storage = message.storage || {};
      return;
    }
    if (message && typeof message.type === "string" && message.type.indexOf("__") === 0) {
      if (window.__devsnipInternal) window.__devsnipInternal(message);
      return;
    }
    deliver(message);
  };

  function rpc(method, params) {
    var id = nextRpc++;
    return new Promise(function (resolve, reject) {
      pending[id] = { resolve: resolve, reject: reject };
      toHost({ type: "__rpc", id: id, method: method, params: params || {} });
    });
  }

  window.__devsnipRpc = rpc;
  window.__devsnipDeliver = deliver;

  function base64ToBytes(b64) {
    var bin = atob(b64 || "");
    var out = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }

  /** The tool's environment (src/toolkits/types.ts ToolContext), served by the IDE. */
  function toolContext() {
    var ctx = {
      now: function () { return new Date(); },
      storage: {
        get: function (key, fallback) {
          var store = host.storage || {};
          return Object.prototype.hasOwnProperty.call(store, key) ? store[key] : fallback;
        },
        set: function (key, value) {
          host.storage = host.storage || {};
          host.storage[key] = value;
          return rpc("storage.set", { key: key, value: value }).then(function () { return undefined; });
        }
      },
      network: {
        isPortFree: function (port, hostName) { return rpc("network.isPortFree", { port: port, host: hostName || null }); }
      },
      files: {
        pick: function (options) {
          return rpc("files.pick", options).then(function (picked) {
            return picked ? { name: picked.name, path: picked.path, bytes: base64ToBytes(picked.base64) } : undefined;
          });
        },
        save: function (content, options) { return rpc("files.save", { content: content, options: options }); }
      }
    };
    if (host.workspaceName) {
      ctx.workspace = {
        name: host.workspaceName,
        exists: function (path) { return rpc("workspace.exists", { path: path }); },
        readFile: function (path) { return rpc("workspace.readFile", { path: path }).then(function (text) { return text === null ? undefined : text; }); },
        findFiles: function (glob, limit) { return rpc("workspace.findFiles", { glob: glob, limit: limit }); }
      };
    }
    return ctx;
  }

  /** What the REST API Client's in-page feature system needs from the IDE. */
  if (host.page === "rest-client") {
    window.__devsnipApiHost = {
      rpc: rpc,
      deliver: deliver,
      onStream: function (id, listener) {
        if (listener) streams[id] = listener;
        else delete streams[id];
      },
      storage: {
        get: function (key) {
          var store = host.storage || {};
          return Object.prototype.hasOwnProperty.call(store, key) ? store[key] : undefined;
        },
        set: function (key, value) {
          host.storage = host.storage || {};
          host.storage[key] = value;
          return rpc("storage.set", { key: key, value: value === undefined ? null : value }).then(function () { return undefined; });
        },
        keys: function () { return Object.keys(host.storage || {}); }
      }
    };
  }

  var latestRequest = 0;

  /** runner.ts handleMessage("run"), executed in the page. */
  function runInPage(message) {
    var requestId = Number(message.requestId) || 0;
    latestRequest = Math.max(latestRequest, requestId);
    var trigger = ["run", "live", "preset", "action"].indexOf(message.trigger) >= 0 ? message.trigger : "run";
    var started = Date.now();
    var ctx = toolContext();
    window.DevSnipToolkit.run(host.toolId, message.values, ctx, message.action).then(function (outcome) {
      if (requestId < latestRequest && trigger === "live") return;
      deliver(Object.assign({ type: "result", requestId: requestId, durationMs: Date.now() - started }, outcome));
      if (message.action) window.DevSnipToolkit.examples(host.toolId, ctx).then(function (examples) { deliver({ type: "examples", examples: examples }); });
      toHost({ type: "__ran", outcome: outcome.outcome });
    });
  }

  var api = {
    postMessage: function (message) {
      if (host.page === "tool" && message && message.type === "run" && window.DevSnipToolkit) {
        runInPage(message);
        return;
      }
      if ((host.page === "osv" || host.page === "sca") && window.__devsnipToExtension) {
        var copy = JSON.parse(JSON.stringify(message === undefined ? null : message));
        setTimeout(function () { window.__devsnipToExtension(copy); }, 0);
        return;
      }
      if (host.page === "rest-client" && window.DevSnipApiClient && window.DevSnipApiClient.owns(message)) {
        window.DevSnipApiClient.handle(message);
        return;
      }
      toHost(message);
    },
    getState: function () {
      try {
        var raw = window.localStorage.getItem(stateKey);
        return raw ? JSON.parse(raw) : undefined;
      } catch (error) {
        return undefined;
      }
    },
    setState: function (state) {
      try {
        window.localStorage.setItem(stateKey, JSON.stringify(state));
      } catch (error) {
        /* storage unavailable: state lasts for this session only */
      }
      return state;
    }
  };
  var acquired = false;
  window.acquireVsCodeApi = function () {
    if (acquired) throw new Error("An instance of the VS Code API has already been acquired");
    acquired = true;
    return api;
  };

  // Presets computed from the context (saved templates) arrive after load, as VS Code sends them on open.
  if (host.page === "tool" && window.DevSnipToolkit) {
    window.addEventListener("load", function () {
      window.DevSnipToolkit.examples(host.toolId, toolContext()).then(function (examples) {
        deliver({ type: "examples", examples: examples });
      });
    });
  }
})();
