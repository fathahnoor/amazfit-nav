#!/usr/bin/env bash
# Offline build: no Gradle, no network. aapt2 + javac + d8 + zipalign + apksigner.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK_ROOT:-$LOCALAPPDATA/Android/Sdk}"
SDK="$(cygpath -u "$SDK" 2>/dev/null || echo "$SDK")"
BT="$SDK/build-tools/36.1.0"
[ -x "$BT/aapt2.exe" ] || BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-36/android.jar"
[ -f "$PLATFORM" ] || { echo "android.jar not found: $PLATFORM" >&2; exit 1; }

# The Android tools are Windows binaries: hand them Windows paths explicitl.
# (MSYS does not translate POSIX paths embedded in a colon-separated list.)
w() { cygpath -w "$1" 2>/dev/null || echo "$1"; }

MIN_SDK=26
TARGET_SDK=36
OUT=build
APK_NAME=amazfitnav-debug.apk

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "==> aapt2 compile"
"$BT/aapt2.exe" compile --dir app/res -o "$OUT/res.zip"

echo "==> aapt2 link"
"$BT/aapt2.exe" link \
  -o "$OUT/app-unsigned.apk" \
  -I "$(w "$PLATFORM")" \
  --manifest app/AndroidManifest.xml \
  -R "$OUT/res.zip" \
  --java "$OUT/gen" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --auto-add-overlay

echo "==> javac"
find app/src "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -nowarn -source 17 -target 17 \
  -classpath "$(w "$PLATFORM");$(w "$OUT/gen")" \
  -d "$OUT/classes" \
  @"$(w "$OUT/sources.txt")"

echo "==> d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8.bat" --lib "$(w "$PLATFORM")" --min-api "$MIN_SDK" \
  --output "$OUT/dex" @"$(w "$OUT/classes.txt")"

echo "==> package dex"
# No `zip` on this machine: append classes.dex with Python's zipfile.
python - "$OUT/app-unsigned.apk" "$OUT/dex/classes.dex" <<'PY'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
PY

echo "==> zipalign"
"$BT/zipalign.exe" -f -p 4 "$OUT/app-unsigned.apk" "$OUT/app-aligned.apk"

if [ ! -f keystore/debug.keystore ]; then
  echo "==> generate debug keystore"
  mkdir -p keystore
  keytool -genkeypair -keystore keystore/debug.keystore -storepass android \
    -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi

echo "==> apksigner"
"$BT/apksigner.bat" sign \
  --ks keystore/debug.keystore --ks-pass pass:android \
  --key-pass pass:android --out "$OUT/$APK_NAME" \
  "$OUT/app-aligned.apk"
"$BT/apksigner.bat" verify --print-certs "$OUT/$APK_NAME" | head -3

echo "==> built $OUT/$APK_NAME"
