/* Placeholder while the database drivers are routed to the IDE (replaced by shims/db-*.js). */
function unavailable() {
  throw new Error("This database driver is not available in the IDE.");
}
export default new Proxy(unavailable, { get: () => unavailable, apply: unavailable, construct: unavailable });
