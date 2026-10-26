/*
 * The `mongodb` driver for the Database Client's MongoDB adapter, on the
 * IDE's MongoDB Java driver (DbBridge.kt / MongoOps.kt). Documents cross as
 * canonical Extended JSON, so the BSON types (ObjectId, Date, Decimal128,
 * Binary, Long) are the real ones from the `bson` package.
 */
import * as BSON from "bson";
import { rpc } from "./db-wire.js";

const { EJSON } = BSON;
const CAP = 5000;

function wire(value) {
  return EJSON.stringify(value === undefined ? null : value, { relaxed: false });
}

class Remote {
  constructor(client, db, collection) {
    this.client = client;
    this.dbName = db;
    this.collectionName = collection;
  }

  async op(op, args = [], options = {}, cap) {
    const id = await this.client.ready();
    const answer = await rpc("mongo.op", {
      id,
      db: this.dbName,
      collection: this.collectionName ?? null,
      op,
      args: wire(args.map(a => (a === undefined ? null : a))),
      options: wire(options ?? {}),
      cap: cap ?? CAP
    });
    return EJSON.parse(answer, { relaxed: false }).r;
  }
}

/** A find or aggregate cursor: options chain, then toArray() or async iteration. */
class Cursor {
  constructor(remote, op, args, options) {
    this.remote = remote;
    this.opName = op;
    this.args = args;
    this.options = { ...(options ?? {}) };
    this.buffer = null;
    this.closed = false;
  }
  sort(sort) { this.options.sort = sort; return this; }
  skip(n) { this.options.skip = n; return this; }
  limit(n) { this.options.limit = n; return this; }
  project(p) { this.options.projection = p; return this; }
  batchSize() { return this; }
  maxTimeMS(ms) { this.options.maxTimeMS = ms; return this; }
  async toArray() {
    if (!this.buffer) this.buffer = (await this.remote.op(this.opName, this.args, this.options)) ?? [];
    return this.buffer;
  }
  async next() {
    const docs = await this.toArray();
    return docs.length ? docs.shift() : null;
  }
  async hasNext() { return (await this.toArray()).length > 0; }
  async close() { this.closed = true; }
  async *[Symbol.asyncIterator]() {
    for (const doc of await this.toArray()) {
      if (this.closed) return;
      yield doc;
    }
  }
}

class Collection extends Remote {
  constructor(client, db, name) {
    super(client, db, name);
    this.collectionName = name;
  }
  get namespace() { return `${this.dbName}.${this.collectionName}`; }
  find(filter = {}, options = {}) { return new Cursor(this, "find", [filter], options); }
  aggregate(pipeline = [], options = {}) { return new Cursor(this, "aggregate", [pipeline], options); }
  findOne(filter = {}, options = {}) { return this.op("findOne", [filter], options); }
  countDocuments(filter = {}, options = {}) { return this.op("countDocuments", [filter], options).then(Number); }
  estimatedDocumentCount(options = {}) { return this.op("estimatedDocumentCount", [], options).then(Number); }
  distinct(field, filter = {}, options = {}) { return this.op("distinct", [field, filter], options); }
  insertOne(doc) {
    // The driver adds an _id before sending, so the caller sees it on its document too.
    if (doc && typeof doc === "object" && doc._id === undefined) doc._id = new BSON.ObjectId();
    return this.op("insertOne", [doc]);
  }
  insertMany(docs) {
    for (const doc of docs ?? []) if (doc && typeof doc === "object" && doc._id === undefined) doc._id = new BSON.ObjectId();
    return this.op("insertMany", [docs]).then(r => ({ ...r, insertedCount: Number(r.insertedCount) }));
  }
  updateOne(filter, update, options = {}) { return this.op("updateOne", [filter, update, options]).then(counts); }
  updateMany(filter, update, options = {}) { return this.op("updateMany", [filter, update, options]).then(counts); }
  replaceOne(filter, doc, options = {}) { return this.op("replaceOne", [filter, doc, options]).then(counts); }
  deleteOne(filter) { return this.op("deleteOne", [filter]).then(counts); }
  deleteMany(filter) { return this.op("deleteMany", [filter]).then(counts); }
  findOneAndUpdate(filter, update, options = {}) { return this.op("findOneAndUpdate", [filter, update, options]); }
  findOneAndReplace(filter, doc, options = {}) { return this.op("findOneAndReplace", [filter, doc, options]); }
  findOneAndDelete(filter, options = {}) { return this.op("findOneAndDelete", [filter, options]); }
  bulkWrite(ops, options = {}) { return this.op("bulkWrite", [ops, options]).then(counts); }
  createIndex(keys, options = {}) { return this.op("createIndex", [keys, options]); }
  dropIndex(index) { return this.op("dropIndex", [index]); }
  dropIndexes() { return this.op("dropIndexes", []); }
  indexes() { return this.op("indexes", []); }
  drop() { return this.op("drop", []); }
  rename(name) { return this.op("rename", [name]); }
}

/** Counts as numbers (they cross as Int64). */
function counts(r) {
  const out = { acknowledged: true, ...r };
  for (const key of ["matchedCount", "modifiedCount", "upsertedCount", "deletedCount", "insertedCount"]) if (out[key] !== undefined) out[key] = Number(out[key]);
  return out;
}

class Db extends Remote {
  constructor(client, name) {
    super(client, name, null);
    this.databaseName = name;
  }
  command(command, options = {}) { return this.op("command", [command], options); }
  collection(name) { return new Collection(this.client, this.databaseName, name); }
  listCollections(filter = {}) {
    return { toArray: () => this.op("listCollections", [filter]) };
  }
  stats() { return this.op("stats", []); }
  dropDatabase() { return this.op("dropDatabase", []); }
  createCollection(name, options = {}) { return this.op("createCollection", [name, options]).then(() => this.collection(name)); }
  admin() {
    return {
      listDatabases: (options = {}) => this.op("listDatabases", [], options),
      command: command => this.op("command", [command]),
      ping: () => this.op("command", [{ ping: 1 }])
    };
  }
}

export class MongoClient {
  constructor(url, options = {}) {
    this.url = url;
    this.settings = options;
    this.options = {};
    this.opened = null;
  }

  ready() {
    if (!this.opened) return Promise.reject(new Error("The MongoDB client is not connected."));
    return this.opened.then(r => r.id);
  }

  async connect() {
    this.opened ??= rpc("mongo.connect", {
      url: this.url,
      connectTimeoutMs: this.settings.serverSelectionTimeoutMS ?? this.settings.connectTimeoutMS ?? 10000,
      socketTimeoutMs: this.settings.socketTimeoutMS ?? 40000
    }).catch(error => {
      this.opened = null;
      throw error;
    });
    const info = await this.opened;
    if (info.dbName) this.options.dbName = info.dbName;
    return this;
  }

  db(name) {
    return new Db(this, name || this.options.dbName || "test");
  }

  async close() {
    const opened = this.opened;
    this.opened = null;
    if (opened) await opened.then(r => rpc("mongo.close", { id: r.id })).catch(() => undefined);
  }
}

export { BSON };
export const ObjectId = BSON.ObjectId;
export default { MongoClient, BSON, ObjectId };
