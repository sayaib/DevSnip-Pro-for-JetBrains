/*
 * worker_threads and node:sqlite for DevSnip Pro's extension host: a page
 * has neither, so the SQLite adapter's own check (hasNodeSqlite) finds no
 * DatabaseSync and uses sql.js, as on VS Code builds without node:sqlite.
 */
export class Worker {
  constructor() {
    throw new Error("Worker threads are not available in the IDE's extension host.");
  }
}
export const parentPort = null;
export const workerData = undefined;
export const isMainThread = true;
export default { Worker, parentPort, workerData, isMainThread };
