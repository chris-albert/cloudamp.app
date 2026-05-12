# CASA Security Audit Findings

Pre-publication security review based on OWASP ASVS 4.0 (the framework used by Google's CASA assessment).

## Summary

| Severity | Count | Status |
|----------|-------|--------|
| HIGH     | 6 (Android) + 2 (Worker) = 8 | All Fixed |
| MEDIUM   | 8 (Android) + 6 (Worker) = 14 | Pending |
| LOW      | 5 (Android) + 2 (Worker) = 7 | Informational |

---

## Android App - HIGH Severity

### A1. OAuth tokens stored in plaintext SharedPreferences
- **ASVS:** V2.10.4
- **Files:** `GoogleDriveAuthManager.kt:35,78-79,154-155`
- **Issue:** `access_token` and `refresh_token` are saved as plain strings in SharedPreferences. On rooted devices or via ADB backup, these can be extracted.
- **Fix:** Use `EncryptedSharedPreferences` from `androidx.security:security-crypto`.
- [x] Fixed

### A2. BODY-level HTTP logging leaks full tokens to logcat
- **ASVS:** V3.3.4
- **Files:** `GoogleDriveApiClient.kt:37-39,84-86`
- **Issue:** `HttpLoggingInterceptor.Level.BODY` logs complete request/response bodies including `Authorization: Bearer <token>` headers. No `BuildConfig.DEBUG` guard.
- **Fix:** Gate on `BuildConfig.DEBUG`, use `Level.NONE` for release builds.
- [x] Fixed

### A3. Token prefix logged on every API call
- **ASVS:** V3.3.4
- **Files:** `GoogleDriveApiClient.kt:44`
- **Issue:** `Log.d(TAG, "...Bearer token (${token.take(10)}...)...")` logs the first 10 characters of the access token on every request.
- **Fix:** Remove token logging entirely, or gate behind `BuildConfig.DEBUG`.
- [x] Fixed

### A4. Exported ContentProvider with no permissions (arbitrary Drive file download)
- **ASVS:** V4.2.1
- **Files:** `AndroidManifest.xml:92-96`, `GDriveImageProvider.kt:154-163`
- **Issue:** Any app on the device can call `content://com.cloudamp.music.gdriveimages/<ANY_FILE_ID>` and the provider will download that file from the user's Drive using the stored OAuth token. No permission check, no file ID validation. This is effectively an SSRF through the content provider.
- **Fix:** Add `android:readPermission` with a signature-level permission, and validate file IDs against known cover image IDs.
- [x] Fixed

### A5. `usesCleartextTraffic="true"`
- **ASVS:** V9.1.1
- **Files:** `AndroidManifest.xml:16`
- **Issue:** Globally allows HTTP (non-TLS) connections. Any library or redirect could send data over plaintext.
- **Fix:** Set `android:usesCleartextTraffic="false"`.
- [x] Fixed

### A6. `allowBackup="true"` exposes tokens via ADB backup
- **ASVS:** V8.1.2
- **Files:** `AndroidManifest.xml:11`
- **Issue:** ADB backup can extract the full app data directory including `gdrive_auth.xml` with plaintext tokens.
- **Fix:** Set `android:allowBackup="false"`.
- [x] Fixed

---

## Android App - MEDIUM Severity

### A7. HEADERS-level logging on streaming client leaks Bearer token
- **ASVS:** V3.3.4
- **Files:** `GoogleDriveApiClient.kt:108-109`
- **Issue:** The streaming OkHttp client uses `Level.HEADERS`, which logs the full `Authorization: Bearer <token>` header on every audio stream request.
- **Fix:** Gate on `BuildConfig.DEBUG`.
- [x] Fixed

### A8. No token expiration tracking (`expires_in` ignored)
- **ASVS:** V2.10.1
- **Files:** `GoogleDriveAuthManager.kt:148-161`
- **Issue:** The `expires_in` field from Google's token response is never read or stored. Token refresh is purely reactive (waits for 401).
- **Fix:** Store `expires_in` and refresh proactively before expiry.
- [ ] Fixed

### A9. Error responses logged with full body
- **ASVS:** V3.3.4
- **Files:** `GoogleDriveAuthManager.kt:163,207`
- **Issue:** `Log.e(TAG, "Token exchange failed: ${response.code} $responseBody")` logs the complete error response which may contain sensitive context.
- **Fix:** Log only the status code in release builds.
- [ ] Fixed

### A10. MediaBrowserService allows all clients
- **ASVS:** V4.2.1
- **Files:** `CloudAmpService.kt:475`
- **Issue:** `onGetRoot()` returns a `BrowserRoot` for all clients without verifying `clientPackageName`. Any app can browse the user's library structure.
- **Fix:** Verify `clientPackageName` against a known-good list (Android Auto, system UI, self).
- [ ] Fixed

### A11. No network security config / no certificate pinning
- **ASVS:** V6.2.1, V9.2.3
- **Files:** (absent)
- **Issue:** No `res/xml/network_security_config.xml` exists. No certificate pinning for `googleapis.com` or `cloudamp.io`.
- **Fix:** Add a network security config with certificate pinning and cleartext restrictions.
- [ ] Fixed

### A12. Release builds use debug keystore
- **ASVS:** V10.3.2
- **Files:** `build.gradle:35-39,52-53`
- **Issue:** `signingConfig signingConfigs.debug` is used for release builds. The debug keystore password is "android". An attacker can sign a modified APK with the same identity.
- **Fix:** Generate a proper release keystore with strong passwords stored in CI secrets.
- [ ] Fixed

### A13. No code obfuscation (`minifyEnabled false`)
- **ASVS:** V10.3.1
- **Files:** `build.gradle:50`
- **Issue:** ProGuard/R8 is disabled. All class/method names and string constants are readable via decompilation.
- **Fix:** Enable `minifyEnabled true` for release builds and add ProGuard rules.
- [ ] Fixed

### A14. Firebase API key committed to version control
- **ASVS:** V8.3.3
- **Files:** `google-services.json:19`
- **Issue:** Firebase API key `AIzaSyAXjhftc7qXp23aICbM-3aMDdVVegcTQrg` is in the repo. While Firebase keys are semi-public (restricted by package + SHA-1), CASA auditors flag this.
- **Fix:** Add `google-services.json` to `.gitignore` and inject via CI.
- [ ] Fixed

---

## Cloudflare Worker - HIGH Severity

### W1. No authentication on token proxy endpoint
- **ASVS:** V4.1.1, V13.1.1
- **Files:** `web/functions/api/token.ts:16-75`
- **Issue:** Anyone on the internet can POST to `https://cloudamp.io/api/token` and use the app's `GOOGLE_CLIENT_SECRET` to exchange codes or refresh tokens. No origin check, no auth, no rate limiting.
- **Fix:** Add origin validation (allowlist of known origins), and consider adding a shared app key or CORS restriction.
- [x] Fixed

### W2. Missing OAuth `state` parameter (CSRF)
- **ASVS:** V3.5.2, V2.1.1
- **Files:** `web/functions/api/android-callback.ts:18-28`, `web/src/lib/google-auth.ts:83-94`
- **Issue:** Neither the Android callback nor the web auth flow use an OAuth `state` parameter. An attacker could force a victim to link the attacker's Google account.
- **Fix:** Generate a random `state` value, store it, and validate it on the callback.
- [x] Fixed

---

## Cloudflare Worker - MEDIUM Severity

### W3. Unvalidated `redirect_uri` passed through to Google
- **ASVS:** V5.1.5
- **Files:** `web/functions/api/token.ts:10,43,51`
- **Issue:** The `redirect_uri` from the request body is forwarded directly to Google without validation.
- **Fix:** Validate against an allowlist of known redirect URIs.
- [ ] Fixed

### W4. No CORS headers / missing preflight handling
- **ASVS:** V14.5.1, V13.1.1
- **Files:** `web/functions/api/token.ts:16`
- **Issue:** No `onRequestOptions` handler, no explicit CORS policy. Security posture depends on platform defaults.
- **Fix:** Add explicit CORS with origin allowlist, or add a `_middleware.ts`.
- [ ] Fixed

### W5. No rate limiting on any endpoint
- **ASVS:** V11.1.1
- **Files:** `web/functions/api/token.ts`, `web/functions/api/android-callback.ts`
- **Issue:** Zero rate limiting. The token proxy could be abused to exhaust Google API quotas.
- **Fix:** Add Cloudflare rate limiting rules or implement in-worker throttling.
- [ ] Fixed

### W6. Missing security response headers
- **ASVS:** V14.4.1-V14.4.7
- **Files:** `web/functions/api/token.ts:74`, `web/functions/api/android-callback.ts:62`
- **Issue:** Missing `Cache-Control: no-store` on token responses, missing `X-Frame-Options`, `CSP`, `HSTS`, `X-Content-Type-Options` on all responses.
- **Fix:** Add security headers to all responses. Token responses must include `Cache-Control: no-store`.
- [ ] Fixed

### W7. Google API errors proxied verbatim
- **ASVS:** V7.4.1
- **Files:** `web/functions/api/token.ts:73-74`
- **Issue:** Full Google error responses are forwarded to the client, potentially leaking internal details.
- **Fix:** Map Google errors to sanitized responses.
- [ ] Fixed

### W8. Token passed in URL query string (service worker)
- **ASVS:** V3.1.1
- **Files:** `web/public/sw.js:17,21,35`, `web/src/lib/player-store.ts:117,236`
- **Issue:** OAuth access token is passed as `?token=...` in audio stream URLs. Appears in browser history and could leak via Referer headers.
- **Fix:** Use `postMessage` to pass token to service worker, or use a header-based approach.
- [ ] Fixed

---

## Positive Findings (no action needed)

These will receive positive marks in the CASA assessment:

- PKCE with S256 correctly implemented (`GoogleDriveAuthManager.kt:86-96`)
- Client secret kept server-side via token proxy (`GoogleDriveAuthManager.kt:22`)
- Google Client ID injected via BuildConfig, not hardcoded (`build.gradle:31`)
- OAuth uses external browser, no WebViews
- No SQL injection surface (no SQLite usage)
- All first-party API URLs use HTTPS
- No `console.log` of secrets in worker code
- `SettingsActivity` validates OAuth callback scheme and host (`SettingsActivity.kt:124-126`)

---

## Remediation Priority

### Must fix before CASA submission:
1. ~~A1 - EncryptedSharedPreferences for tokens~~ **DONE**
2. ~~A2/A3/A7 - Gate all logging behind BuildConfig.DEBUG~~ **DONE**
3. ~~A4 - Secure the ContentProvider~~ **DONE**
4. ~~A5 - Set usesCleartextTraffic=false~~ **DONE**
5. ~~A6 - Set allowBackup=false~~ **DONE**
6. ~~W1 - Add auth/origin check to token proxy~~ **DONE**
7. W6 - Add Cache-Control: no-store to token responses

### Should fix (likely flagged but may not block):
8. A11 - Add network security config
9. ~~W2 - Add OAuth state parameter~~ **DONE**
10. W3 - Validate redirect_uri
11. W4 - Add CORS configuration
12. W6 - Add security headers
13. A8 - Track token expiration
14. A10 - Verify MediaBrowser clients

### Nice to have (strengthens assessment):
15. A12 - Proper release keystore
16. A13 - Enable ProGuard/R8
17. W5 - Rate limiting
18. W7 - Sanitize error responses
