/*
 * Stand-in for src/commands/milestoneTracker in the REST API Client bundle.
 * Milestones & rewards (the points balance) are not ported to the IDE yet;
 * every REST client feature is available there, so nothing is charged.
 */
export function getUserStats() {
  return { totalPoints: 0 };
}
export function getPointsBalance() {
  return 0;
}
export function onDidChangePoints() {
  return { dispose() {} };
}
