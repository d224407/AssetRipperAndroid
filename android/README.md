# AssetRipper for Android

A native shell around the real AssetRipper server. The server (`linux-bionic-arm64`) is bundled in
the APK and runs as a foreground service; the app shows its web UI in a WebView, so every
AssetRipper feature is available. The web UI's "Select file/folder" buttons are answered with
Android pickers (see `MainActivity.pick`).

## Repositories

This repository holds only the app and the build scripts. The AssetRipper source is checked out by
CI from `SOURCE_REPO` (default `AssetRipper/AssetRipper`; set the repository variable `SOURCE_REPO`
to use a fork, and `SOURCE_REF` to pin a branch or tag). It is never modified in git: the few
Android-specific changes live in `scripts/apply.sh` and `scripts/patches/` and are applied to the CI
checkout only, so nothing can conflict with upstream. A nightly run builds a new release only when
the source moved since the last one.

## How versions stay paired

One CI run builds the server and the APK together and tags the release
`v<AssetRipper version>-android.<run number>`.

* The APK contains `assets/ar.zip` (the server) and `assets/ar.info` (`tag=...`). On first start the
  app unpacks the server into `files/ar/<tag>/` and refuses to run it if the tag differs from its
  own `BuildConfig.BUILD_TAG`. Older unpacked servers are deleted.
* The updater reads the latest GitHub release and installs it only if the downloaded APK has the
  published SHA-256, this app's package name, a version code equal to the release's build number,
  and an `ar.info` whose tag equals the release tag. Updating the app therefore always updates the
  server with it.

## One-time setup

In-app updates only install over an older build when both are signed with the same key.

1. Run `scripts/make-keystore.sh` once and keep `release.jks` safe.
2. Add the repository secrets `KEYSTORE_CONTENT`, `KEYSTORE_ALIAS`, `KEYSTORE_PASSWORD`.

Without them CI still builds, but signs with a throwaway debug key and warns about it.

## Notes

* `targetSdk` is 28 on purpose. Apps targeting 29+ cannot `execve()` files from their own data
  directory, and the server is an ordinary executable (the same approach Termux uses).
* arm64 only. The first start unpacks about the size of the server (a few hundred MB).
* `run_number` must keep increasing: do not rename or recreate the workflow, or the version code
  would restart and updates would stop being offered.
* Local build: copy a server build to `app/src/main/assets/ar.zip` and write
  `tag=v<ver>-android.<n>` to `ar.info`, then run
  `gradle assembleDebug -PbuildNumber=<n> -PupstreamVersion=<ver>` (Gradle 8.7, JDK 17).
