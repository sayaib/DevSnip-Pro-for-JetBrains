/*
 * Extension modules the scanner panels call that have no place in the IDE
 * (yet): usage analytics, onboarding milestones, and the theme service (the
 * IDE puts the theme into the page itself, see WebviewAssets.kt).
 */
export function trackToolUsed() {}
export async function reach() {}
export function setWebviewHtml(webview, html) {
  webview.html = html;
}
