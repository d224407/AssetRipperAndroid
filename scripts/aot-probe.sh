#!/usr/bin/env bash
# Informational: checks whether NativeAOT for linux-bionic-arm64 is possible. Never fails the build.
# Usage: scripts/aot-probe.sh (after scripts/make-bionic.sh)
set -uo pipefail
cd "$(dirname "$0")/.."
SRC_DIR="${SRC_DIR:-src}"
APP="AssetRipper.GUI.Free"

VER=$(grep -oP 'Microsoft\.NETCore\.App\.Runtime\.linux-arm64/\K[0-9A-Za-z.\-]+' "out/$APP.deps.json" | head -1)
echo "Runtime version: $VER"

echo "== package availability on nuget.org (200 = exists) =="
for id in \
  runtime.linux-bionic-arm64.Microsoft.DotNet.ILCompiler \
  Microsoft.AspNetCore.App.Runtime.linux-bionic-arm64 \
  Microsoft.NETCore.App.Runtime.linux-bionic-arm64; do
  lower=$(echo "$id" | tr 'A-Z' 'a-z')
  code=$(curl -s -o /dev/null -w '%{http_code}' "https://api.nuget.org/v3-flatcontainer/$lower/$VER/$lower.$VER.nupkg")
  echo "$code  $id $VER"
done

echo "== direct NativeAOT publish for linux-bionic-arm64 =="
dotnet publish "$SRC_DIR/Source/$APP" -c Release -r linux-bionic-arm64 -p:PublishAot=true -o aot-out 2>&1 | tail -40
echo "dotnet publish exit code: ${PIPESTATUS[0]}"
exit 0
