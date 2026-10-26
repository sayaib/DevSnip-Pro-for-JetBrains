/*
 * Stand-in for src/commands/milestoneTracker in the REST API Client bundle:
 * the balance is the extension host's (api-client-runtime.js keeps it); the
 * page's own code only reads it.
 */
export function getPointsBalance() {
  const live = globalThis.__devsnipPoints;
  return Number(live !== undefined ? live : (globalThis.__devsnipHost || {}).points) || 0;
}
export function getUserStats() {
  return { totalPoints: getPointsBalance() };
}
export function onDidChangePoints() {
  return { dispose() {} };
}
