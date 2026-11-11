#!/usr/bin/env bash
# Applies Android-specific source patches to the checked-out AssetRipper source.
# The source repository is never modified; patches only touch the CI checkout.
#
# SRC_DIR: where the source is checked out (default: src).
#
# Two ways to patch:
#   1. Drop unified diffs into scripts/patches/*.patch (applied inside SRC_DIR with git apply).
#   2. Call replace_once below for small literal edits.
# Every patch fails loudly if upstream changed the code it targets.
set -euo pipefail
cd "$(dirname "$0")/.."
SRC_DIR="${SRC_DIR:-src}"
test -d "$SRC_DIR/Source" || { echo "Source checkout not found in $SRC_DIR" >&2; exit 1; }

replace_once() {
  local file="$SRC_DIR/$1" old="$2" new="$3"
  python3 - "$file" "$old" "$new" <<'PY'
import sys
path, old, new = sys.argv[1:4]
text = open(path, encoding="utf-8", newline="").read()
if new in text:
    print("already patched: " + path)
    sys.exit(0)
if text.count(old) != 1:
    sys.exit("PATCH FAILED: expected exactly one match in " + path + " for: " + old)
open(path, "w", encoding="utf-8", newline="").write(text.replace(old, new))
print("patched: " + path)
PY
}

# Show the "Select folder/file" buttons in the web UI. The Android app answers the /Dialogs/*
# requests itself (see MainActivity.pick), so the server only has to render the buttons.
replace_once Source/AssetRipper.GUI.Web/Dialogs.cs \
  'public static bool Supported => NativeDialog.Supported;' \
  'public static bool Supported => NativeDialog.Supported || Environment.GetEnvironmentVariable("ASSETRIPPER_ANDROID") == "1";'

for p in scripts/patches/*.patch; do
  [ -e "$p" ] || continue
  git -C "$SRC_DIR" apply --check "$PWD/$p"
  git -C "$SRC_DIR" apply "$PWD/$p"
  echo "applied: $p"
done

echo "apply.sh: done"
