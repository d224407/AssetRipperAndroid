# AssetRipper for Android

Runs the real AssetRipper server (linux-bionic-arm64, bundled in the APK) as a foreground service and shows its web UI in a WebView.


## Notes
- arm64 only, targetSdk 28 (needed to exec the bundled server)
- Do not recreate the workflow: the run number is the version code