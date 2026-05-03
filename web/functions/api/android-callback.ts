/**
 * OAuth callback for the Android app.
 *
 * Google redirects here after the user authorizes. This page immediately
 * redirects to the app's custom URI scheme, passing the authorization
 * code (or error) through.
 *
 * Flow:
 *   1. Android opens browser → Google auth
 *   2. Google redirects → https://cloudamp.io/api/android-callback?code=...
 *   3. This page redirects → com.cloudamp.music://oauth2callback?code=...
 *   4. Android catches the deep link and exchanges the code for tokens
 */

const APP_SCHEME = "com.cloudamp.music";
const APP_HOST = "oauth2callback";

export const onRequestGet: PagesFunction = async (context) => {
  const url = new URL(context.request.url);
  const code = url.searchParams.get("code");
  const error = url.searchParams.get("error");

  // Build the deep link back to the Android app
  const appUri = new URL(`${APP_SCHEME}://${APP_HOST}`);
  if (code) appUri.searchParams.set("code", code);
  if (error) appUri.searchParams.set("error", error);

  const deepLink = appUri.toString();

  // Serve a page that redirects to the deep link.
  // Uses both a meta-refresh and JS redirect for reliability.
  const html = `<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta http-equiv="refresh" content="0;url=${deepLink}">
  <title>CloudAmp</title>
  <style>
    body {
      font-family: monospace;
      background: #1a1a2e;
      color: #00ff41;
      display: flex;
      justify-content: center;
      align-items: center;
      height: 100vh;
      margin: 0;
    }
    a { color: #00ff41; }
  </style>
</head>
<body>
  <div style="text-align:center">
    <h1>CloudAmp</h1>
    <p>Redirecting to app...</p>
    <p><a href="${deepLink}">Tap here if not redirected</a></p>
  </div>
  <script>window.location.href = ${JSON.stringify(deepLink)};</script>
</body>
</html>`;

  return new Response(html, {
    headers: { "Content-Type": "text/html; charset=utf-8" },
  });
};
