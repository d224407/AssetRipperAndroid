# AssetRipper for Android

Native shell around the real AssetRipper server (`linux-bionic-arm64`, bundled in the APK and run as a
foreground service). The app shows the web UI in a WebView, so all AssetRipper features are available.
The "Select file/folder" buttons are answered with Android pickers (`MainActivity.pick`).

## Build

CI checks out `SOURCE_REPO` (default `AssetRipper/AssetRipper`; repo variable) at `SOURCE_REF`
(optional), applies `scripts/apply.sh`, builds the server and the APK together and publishes
`v<AssetRipper version>-android.<run number>`. It runs on push to main or manually.

* The APK carries `assets/ar.zip` (server) and `assets/ar.info` (`tag=...`); the app refuses a server
  whose tag differs from `BuildConfig.BUILD_TAG`.
* The updater installs a release only if the APK matches the published SHA-256, the package name,
  the build number as version code, and the bundled tag.
* Server settings (`AssetRipper.Settings.json`) live in `files/keep/` and survive updates.

## Setup

Run `scripts/make-keystore.sh` once and add the secrets `KEYSTORE_CONTENT`, `KEYSTORE_ALIAS`,
`KEYSTORE_PASSWORD`. Updates only install over a build signed with the same key.

## Notes

* `targetSdk` 28: apps targeting 29+ cannot `execve()` from their data directory.
* OpenSSL is built with the NDK (`scripts/build-openssl.sh`) and shipped next to the server.
* arm64 only. Do not recreate the workflow: `run_number` must keep increasing.
* Local build: put a server zip in `app/src/main/assets/ar.zip`, `tag=v<ver>-android.<n>` in `ar.info`,
  then `gradle assembleDebug -PbuildNumber=<n> -PupstreamVersion=<ver>` (Gradle 8.7, JDK 17).
