# Releases and updates

The app is distributed as an APK (no Play Store for now).

## Two apps: release and debug

| | Release (the real app) | Debug (testing) |
|---|---|---|
| App ID | `com.tfassbender.shoppinglist` | `com.tfassbender.shoppinglist.debug` |
| Default server | `https://shopping-list.tobias-fassbender.com/` | local dev server (`http://10.0.2.2:4723/`) |
| Signed with | release key in `signing/` | Android Studio debug key of the building PC |
| Shrinking | R8 (`app/proguard-rules.pro`) | none |

Both can be installed side by side; they have separate storage.

## Versioning

- One place: `appVersion` in `app/build.gradle.kts`, `MAJOR.MINOR.PATCH` (first release: `1.0.0`).
- `versionCode` is derived: `MAJOR * 10000 + MINOR * 100 + PATCH` (`1.0.0` = 10000, `1.0.1` = 10001).
  Android only installs an update with an equal or higher code, so bump the version for every release.

## Release a new version

1. Bump `appVersion` in `app/build.gradle.kts`.
2. `./gradlew testDebugUnitTest lintDebug assembleRelease` → `app/build/outputs/apk/release/app-release.apk`.
3. Smoke-test the release APK on a device (`adb install -r …`): R8 only runs in release builds, so a missing
   keep rule shows up only there (first release: WorkManager crashed at start).
4. Commit, tag (`v1.0.0`), and publish the APK (e.g. a GitHub Release). Installing it needs
   "install unknown apps" once on the phone.
5. Keep `app/build/outputs/mapping/release/mapping.txt` of that version - it turns crash stack traces from
   users back into readable ones.

Without `signing/` (e.g. on another machine) `assembleRelease` builds an unsigned APK that can't be installed.

## The release key

- `signing/release.jks` (PKCS12, alias `shopping-list`, `CN=Shopping List`, RSA 4096, valid until 2126) and
  `signing/keystore.properties` (path, alias, password). **Gitignored - never commit them: the repo is public.**
- SHA-256 of the certificate (needed later for App Links / `assetlinks.json` and the Play Store):
  `5B:A0:E7:2E:A1:DE:01:F0:A1:BE:B3:C9:16:D1:08:FF:C3:17:93:C7:D6:6B:3B:36:A0:22:39:DB:0C:C6:46:11`
- **Back both files up outside this PC.** If the key or its password is lost, no update can be installed over
  the existing app: users would have to uninstall (losing changes not yet sent to the server) and reinstall.
- Play Store later: Play App Signing can take this key as the app signing key, so APK users can update via
  the store.
- Check an APK's signer: `apksigner verify --print-certs app-release.apk` (Android SDK build-tools).
