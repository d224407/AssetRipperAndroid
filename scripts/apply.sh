#!/usr/bin/env bash
# Applies Android patches to the CI checkout of the AssetRipper source (SRC_DIR, default: src).
# Diffs in scripts/patches/*.patch or replace_once edits; fails if upstream changed the target.
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

# Show the select file/folder buttons; the app answers /Dialogs/* itself.
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
