# Tests, APKs and releases

Pushes, pull requests and manual runs execute the local JVM business-logic tests and build both APK variants. The installable debug APK and JUnit reports are available as workflow artifacts for seven days. Pull requests never receive signing secrets.

Tests cover real decisions: host parsing and domain ownership, local budget-day boundaries including DST, snooze/off-for-today expiration, fractional time accounting, foreground changes and sleep gaps. `UsageClock` discards unobserved background intervals and the interval crossing a reset (at most one normal tick); it never attributes the night to the next day's budget. The integrated fork also tests hard-limit enforcement, deferred settings changes, overlapping rules and reset-period transitions. No Android UI or mock-interaction tests are required.

## One-time release signing setup

Create a private, password-protected RSA signing key with `keytool` and keep a secure offline backup of the keystore, alias and passwords. Never commit these files or place them in workflow artifacts. Reuse the same key for every release of this app ID. Do not generate a new key per run.

Configure these repository Actions secrets:

- `SIGNING_KEYSTORE_BASE64`: base64 of the complete keystore, without line wrapping.
- `SIGNING_KEYSTORE_PASSWORD`: keystore password.
- `SIGNING_KEY_ALIAS`: signing-key alias.
- `SIGNING_KEY_PASSWORD`: private-key password.

The release build is unsigned until the separate signing step. Gradle never receives private-key material. Passwords are passed to `apksigner` through environment variables. Missing secrets fail the signing job; there is no debug-key fallback.

Run **Android tests and APK → Run workflow → signing_check** on the repository's default branch to verify signing without creating or publishing a release. Inspect the signed-release artifact and its certificate before the first public release.

## Publish a release

1. Choose a commit already contained in the default branch and tag it `vMAJOR.MINOR.PATCH`.
2. Publish a GitHub release for that tag. Published prereleases also trigger this workflow, but the tag itself uses the same three-part numeric format.
3. The pipeline checks ancestry, tests and builds the exact tag, signs the APK, verifies it and uploads `SiteLimiter-vMAJOR.MINOR.PATCH.apk` plus its `.sha256` file to that release.

The version code is `MAJOR * 1000000 + MINOR * 1000 + PATCH` (minor/patch at most 999, total 2–2100000000). Release numbers must increase to support normal Android updates. The default development version remains 1.0/code 1. Existing assets are not overwritten: a rerun fails explicitly if assets of the same name already exist. If a run stops after a partial upload, inspect and remove the incomplete assets before rerunning.

An existing installation signed by the original developer or a debug key must be uninstalled before installing the first APK signed by this fork's release key. Subsequent releases signed with this same key update normally. Keep an offline key backup; GitHub secrets cannot be read back as a backup.

## Local commands

Install JDK 21, Android SDK Platform 36 (and 37.0 when using the dependency update), and SDK Build Tools 35.0.0 and 36.0.0. Set `JAVA_HOME` and `ANDROID_HOME`.

```sh
./gradlew -Dorg.gradle.java.home="$JAVA_HOME" :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
./gradlew -Dorg.gradle.java.home="$JAVA_HOME" -PreleaseVersion=v1.0.0 :app:assembleRelease
# Set the four SIGNING_* environment values securely, including SIGNING_KEYSTORE_PATH.
scripts/sign-apk.sh app/build/outputs/apk/release/app-release-unsigned.apk SiteLimiter-v1.0.0.apk
```
