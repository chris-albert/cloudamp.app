# CloudAmp Keystores

## Debug Keystore (debug.keystore)

This is the **debug keystore** used for development and testing builds.

- **Alias**: cloudamp
- **Passwords**: android/android (standard debug credentials)
- **Purpose**: Ensures all debug builds are signed consistently
- **Security**: Safe to commit - only used for debug/testing, not production

## Release Keystore (Not Included)

If you plan to publish CloudAmp to Google Play or distribute it publicly:

1. **Generate a release keystore** (keep it private!):
   ```bash
   keytool -genkeypair -v -keystore cloudamp-release.keystore \
     -alias cloudamp-release -keyalg RSA -keysize 2048 -validity 10000
   ```

2. **Store it securely** - DO NOT commit to Git!

3. **Add to GitHub Secrets**:
   - Go to repository Settings > Secrets and variables > Actions
   - Add these secrets:
     - `RELEASE_KEYSTORE_BASE64`: Base64-encoded keystore file
     - `RELEASE_KEYSTORE_PASSWORD`: Keystore password
     - `RELEASE_KEY_ALIAS`: Key alias
     - `RELEASE_KEY_PASSWORD`: Key password

4. **Update build.gradle** to use release keystore for production builds

For now, we're only building debug APKs, so the committed debug keystore is appropriate.
