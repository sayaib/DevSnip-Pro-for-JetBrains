/*
 * `ioredis` for the Database Client's Redis adapter, on the IDE's Jedis
 * (DbBridge.kt): call(), any command as a method, pipeline() and multi()
 * with exec() giving [[error, reply], ...], as ioredis does.
 */
/* CommonJS, because the adapter does `new (require("ioredis"))(...)`: the module itself is the class, as in ioredis. */
const { Buffer } = require("buffer");
const { EventEmitter, rpc } = require("./db-wire.js");

/** Arguments as Redis strings: objects and arrays are flattened (hset(key, { field: value })), Buffers as text. */
function flatten(args) {
  const out = [];
  for (const arg of args) {
    if (arg === undefined || arg === null) continue;
    if (Array.isArray(arg)) out.push(...flatten(arg));
    else if (arg instanceof Uint8Array) out.push(Buffer.from(arg).toString("utf8"));
    else if (arg instanceof Map) for (const [k, v] of arg) out.push(String(k), String(v));
    else if (typeof arg === "object") for (const [k, v] of Object.entries(arg)) out.push(String(k), String(v));
    else out.push(String(arg));
  }
  return out;
}

/** ioredis's reply transformers for the commands that have one. */
function transform(name, reply) {
  if (reply === null || reply === undefined) return reply;
  if (name === "hgetall" && Array.isArray(reply)) {
    const out = {};
    for (let i = 0; i + 1 < reply.length; i += 2) out[reply[i]] = reply[i + 1];
    return out;
  }
  return reply;
}

function replyError(text) {
  const error = new Error(String(text));
  error.name = "ReplyError";
  return error;
}

/** Property names that are never Redis commands (a client or pipeline is awaited, inspected or serialised). */
const NOT_COMMANDS = new Set(["then", "catch", "finally", "toJSON", "inspect", "constructor", "valueOf", "toString", "asymmetricMatch", "nodeType", "$$typeof"]);

/** Records commands (any method name) for exec(). */
function batch(client, transaction) {
  const commands = [];
  const target = {
    exec: async () => {
      if (!commands.length) return [];
      const replies = await client.run(commands.map(c => c.args), transaction);
      return replies.map(([error, value], i) => [error ? replyError(error) : null, error ? null : transform(commands[i].name, value)]);
    },
    call: (name, ...args) => { commands.push({ name: String(name).toLowerCase(), args: [String(name), ...flatten(args)] }); return proxy; },
    get length() { return commands.length; }
  };
  const proxy = new Proxy(target, {
    get(t, property) {
      if (property in t) return t[property];
      if (typeof property !== "string" || NOT_COMMANDS.has(property)) return undefined;
      return (...args) => { commands.push({ name: property.toLowerCase(), args: [property.toUpperCase(), ...flatten(args)] }); return proxy; };
    }
  });
  return proxy;
}

class Redis extends EventEmitter {
  constructor(options = {}) {
    super();
    this.options = options;
    this.status = options.lazyConnect ? "wait" : "connecting";
    this.id = null;
    if (!options.lazyConnect) void this.connect().catch(error => this.emit("error", error));
    return new Proxy(this, {
      get(target, property, receiver) {
        if (property in target || typeof property !== "string" || NOT_COMMANDS.has(property)) return Reflect.get(target, property, receiver);
        return (...args) => target.call(property, ...args).then(reply => transform(property.toLowerCase(), reply));
      }
    });
  }

  async connect() {
    if (this.id !== null) return;
    this.status = "connecting";
    const o = this.options;
    try {
      const answer = await rpc("redis.connect", {
        host: o.host || "localhost",
        port: Number(o.port) || 6379,
        user: o.username || "",
        password: o.password || "",
        db: Number(o.db) || 0,
        tls: Boolean(o.tls),
        rejectUnauthorized: o.tls ? o.tls.rejectUnauthorized !== false : true,
        connectTimeoutMs: o.connectTimeout || 10000,
        commandTimeoutMs: o.commandTimeout || 30000
      });
      this.id = answer.id;
      this.status = "ready";
      this.emit("ready");
    } catch (error) {
      this.status = "end";
      throw error;
    }
  }

  async run(commands, transaction) {
    if (this.id === null) await this.connect();
    return rpc("redis.exec", { id: this.id, commands, transaction: Boolean(transaction) });
  }

  async call(name, ...args) {
    const [[error, reply]] = await this.run([[String(name), ...flatten(args)]], false);
    if (error) throw replyError(error);
    return reply;
  }

  sendCommand(command) {
    return this.call(command.name, ...(command.args ?? []));
  }

  pipeline() {
    return batch(this, false);
  }

  multi() {
    return batch(this, true);
  }

  async quit() {
    this.disconnect();
    return "OK";
  }

  disconnect() {
    const id = this.id;
    this.id = null;
    this.status = "end";
    if (id !== null) void rpc("redis.close", { id }).catch(() => undefined);
  }
}

module.exports = Redis;
module.exports.default = Redis;
module.exports.Redis = Redis;
