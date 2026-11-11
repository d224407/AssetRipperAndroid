#!/usr/bin/env bash
# Builds OpenSSL for Android arm64 (bionic) with the NDK.
#
# .NET's crypto shim (libSystem.Security.Cryptography.Native.OpenSsl.so) loads libssl/libcrypto at
# run time and aborts the process when it cannot find them. Android does not expose a usable system
# OpenSSL to app processes, so the app ships its own copy next to the server.
#
# Usage: scripts/build-openssl.sh <dest-dir>      (OPENSSL_VERSION defaults to 3.5.0, an LTS line)
set -euo pipefail
OPENSSL_VERSION="${OPENSSL_VERSION:-3.5.0}"
API=26
DEST="${1:?usage: build-openssl.sh <dest-dir>}"
mkdir -p "$DEST"
DEST="$(cd "$DEST" && pwd)"

NDK="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}}"
test -d "$NDK" || { echo "Android NDK not found" >&2; exit 1; }
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
export ANDROID_NDK_ROOT="$NDK"
export PATH="$BIN:$PATH"

WORK="$(mktemp -d)"
curl -fsSL -o "$WORK/openssl.tar.gz" \
  "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VERSION/openssl-$OPENSSL_VERSION.tar.gz"
tar -xzf "$WORK/openssl.tar.gz" -C "$WORK"
cd "$WORK/openssl-$OPENSSL_VERSION"

./Configure android-arm64 "-D__ANDROID_API__=$API" shared no-tests
make -j"$(nproc)" build_libs

# The Android build may name its libraries libssl.so or libssl_3.so, and libssl's DT_NEEDED entry
# refers to libcrypto by its SONAME. Ship every file under all the names that can be asked for.
for lib in ssl crypto; do
  f=$(ls lib$lib*.so* 2>/dev/null | head -1)
  test -n "$f" || { echo "lib$lib was not built" >&2; exit 1; }
  "$BIN/llvm-strip" --strip-unneeded "$f"
  soname=$("$BIN/llvm-readelf" -d "$f" | sed -n 's/.*SONAME.*\[\(.*\)\]/\1/p')
  for name in "$soname" "lib$lib.so.3" "lib$lib.so"; do
    [ -n "$name" ] && cp -L "$f" "$DEST/$name"
  done
  echo "lib$lib: built as $f, soname '$soname'"
done

echo "== needed libraries =="
for f in "$DEST"/*.so*; do
  echo "-- $(basename "$f"): $("$BIN/llvm-readelf" -d "$f" | sed -n 's/.*NEEDED.*\[\(.*\)\]/\1/p' | tr '\n' ' ')"
done
ls -l "$DEST"
rm -rf "$WORK"
