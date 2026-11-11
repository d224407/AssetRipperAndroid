#!/usr/bin/env bash
# Turns a self-contained linux-arm64 publish folder into a linux-bionic-arm64 one.
#
# ASP.NET Core has no linux-bionic runtime pack, but its assemblies are managed, so the publish is
# done for linux-arm64 and the .NET runtime files plus the apphost are then replaced by bionic ones.
#
# Usage: scripts/make-bionic.sh [publish-dir]   (default: out)
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="${1:-out}"
APP="AssetRipper.GUI.Free"

VER=$(grep -oP 'Microsoft\.NETCore\.App\.Runtime\.linux-arm64/\K[0-9A-Za-z.\-]+' "$OUT/$APP.deps.json" | head -1)
test -n "$VER" || { echo "Cannot read the runtime version from $APP.deps.json" >&2; exit 1; }
echo "Runtime version: $VER"

PK="$(mktemp -d)"
for p in Microsoft.NETCore.App.Runtime.linux-bionic-arm64 Microsoft.NETCore.App.Host.linux-bionic-arm64; do
  curl -fsSL -o "$PK/$p.zip" "https://www.nuget.org/api/v2/package/$p/$VER"
  unzip -q -o "$PK/$p.zip" -d "$PK/$p"
done

RT="$PK/Microsoft.NETCore.App.Runtime.linux-bionic-arm64"
HOST="$PK/Microsoft.NETCore.App.Host.linux-bionic-arm64"
find "$RT" -path "*/lib/*" -name "*.dll" -exec cp -f {} "$OUT/" \;
find "$RT" -path "*/native/*" -type f ! -path "*/include/*" -exec cp -f {} "$OUT/" \;
APPHOST=$(find "$HOST" -name apphost -type f | head -1)
test -n "$APPHOST" || { echo "apphost not found in the host pack" >&2; exit 1; }
cp -f "$APPHOST" "$OUT/$APP"
chmod +x "$OUT/$APP"

# The apphost from the pack is an unbound template: write the managed DLL name into its
# placeholder, exactly like the SDK does when publishing.
python3 - "$OUT/$APP" "$APP.dll" << 'PY'
import sys
path, dll = sys.argv[1], sys.argv[2].encode()
placeholder = b"c3ab8ff13720e8ad9047dd39466b3c8974e592c2fa383d4a3960714caef0c4f2"
data = open(path, "rb").read()
i = data.index(placeholder)
data = data[:i] + dll + b"\0" * (1024 - len(dll)) + data[i + 1024:]
open(path, "wb").write(data)
print("apphost bound to " + dll.decode())
PY

echo "== linkage check =="
for f in "$OUT/$APP" "$OUT/libcoreclr.so" "$OUT/libhostfxr.so" "$OUT/libhostpolicy.so" "$OUT/libSystem.Native.so"; do
  if [ -e "$f" ]; then
    echo "-- $(basename "$f"): $(readelf -d "$f" | grep -c 'libc\.so\.6') glibc reference(s)"
  else
    echo "-- $(basename "$f"): MISSING"
  fi
done
echo "== files still linked to glibc =="
for f in "$OUT"/*.so "$OUT/$APP"; do
  readelf -d "$f" 2>/dev/null | grep -q 'libc\.so\.6' && echo "$f" || true
done
du -sh "$OUT"
rm -rf "$PK"
