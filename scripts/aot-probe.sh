#!/usr/bin/env bash
# Informational: can this source be compiled with NativeAOT for linux-bionic-arm64?
#
# The normal build cannot just set PublishAot=true: a NativeAOT build is one native executable
# compiled for the target RID, so the "publish for linux-arm64 and swap in the bionic runtime"
# trick has nothing to swap. It needs a real bionic ILCompiler pack and an ASP.NET Core runtime
# pack for linux-bionic-arm64. This script checks both exist and then tries the direct publish.
#
# Usage: scripts/aot-probe.sh   (run after scripts/make-bionic.sh; never fails the build)
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
