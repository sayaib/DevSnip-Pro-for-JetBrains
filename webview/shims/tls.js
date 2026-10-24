/*
 * Node's tls.connect for the browser bundle, as the endpoint security scan
 * uses it: the IDE performs the handshake (without trusting the certificate
 * blindly: `authorized` is the JDK's own verdict) and reports what Node's
 * TLSSocket would.
 */
export function connect(options) {
  const listeners = {};
  const socket = {
    authorized: false,
    authorizationError: undefined,
    once(event, listener) {
      (listeners[event] = listeners[event] || []).push(listener);
      return socket;
    },
    on(event, listener) {
      return socket.once(event, listener);
    },
    destroy() {
      listeners.secureConnect = [];
      listeners.error = [];
      listeners.timeout = [];
    },
    getPeerCertificate() {
      return socket.__certificate || {};
    },
    getCipher() {
      return socket.__cipher ? { name: socket.__cipher } : null;
    },
    getProtocol() {
      return socket.__protocol || null;
    }
  };
  const emit = (event, value) => (listeners[event] || []).slice().forEach(listener => listener(value));

  const bridge = globalThis.__devsnipApiHost;
  if (!bridge) {
    Promise.resolve().then(() => emit("error", new Error("The IDE connection is not available.")));
    return socket;
  }
  bridge
    .rpc("tls.probe", { host: options.host, port: options.port || 443, servername: options.servername || options.host, timeoutMs: options.timeout || 15000 })
    .then(result => {
      socket.authorized = Boolean(result.authorized);
      socket.authorizationError = result.authorizationError || undefined;
      socket.__certificate = result.certificate || {};
      socket.__cipher = result.cipher;
      socket.__protocol = result.protocol;
      emit("secureConnect");
    })
    .catch(error => {
      if (error && error.code === "ETIMEDOUT") emit("timeout");
      else emit("error", error instanceof Error ? error : new Error(String(error)));
    });
  return socket;
}

export default { connect };
