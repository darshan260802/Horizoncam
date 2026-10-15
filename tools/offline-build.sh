#!/usr/bin/env bash
# Builds a signed HorizonCam APK *without* the Android SDK or the Android Gradle Plugin.
#
# Useful in locked-down environments where dl.google.com / Google Maven are unreachable.
# The normal way to build is still Android Studio / `./gradlew assembleDebug`.
#
# Requirements (Debian/Ubuntu):
#   sudo apt-get install -y aapt zipalign apksigner openjdk-17-jdk zip unzip curl
# Everything else is downloaded into $HORIZONCAM_CACHE (default ~/.cache/horizoncam-offline):
#   - Kotlin compiler (GitHub releases)
#   - Android framework jars published by Robolectric (Maven Central)
#   - Google's official D8 dexer (R8 release jar on storage.googleapis.com)
#
# Usage: tools/offline-build.sh [output.apk]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="${HORIZONCAM_CACHE:-$HOME/.cache/horizoncam-offline}"
WORK="$ROOT/build/offline"
OUT_APK="${1:-$ROOT/build/outputs/HorizonCam-debug.apk}"

KOTLIN_VERSION="2.0.21"
R8_VERSION="8.11.18"
# Compile against the full Android 16 (API 36) framework published by Robolectric on Maven Central.
ANDROID_ALL_COMPILE="16-robolectric-13921718"
# aapt (v1) cannot parse the newest resource table format, so link resources against API 34.
ANDROID_ALL_RES="14-robolectric-10818077"
PACKAGE="com.darshan.horizoncam"
MIN_SDK=26
TARGET_SDK=36

prop() { grep -E "^$1=" "$ROOT/gradle.properties" | cut -d= -f2- | tr -d '[:space:]'; }
VERSION_CODE="$(prop horizoncam.versionCode)"
VERSION_NAME="$(prop horizoncam.versionName)"

for tool in aapt zipalign apksigner java javac jar curl unzip zip; do
  command -v "$tool" >/dev/null || { echo "Missing tool: $tool" >&2; exit 1; }
done

mkdir -p "$CACHE"
fetch() { # url dest
  if [ ! -s "$2" ]; then
    echo "Downloading $(basename "$2")"
    curl -fL --retry 3 -o "$2.part" "$1"
    mv "$2.part" "$2"
  fi
}

MAVEN="https://repo1.maven.org/maven2"
fetch "$MAVEN/org/robolectric/android-all/$ANDROID_ALL_COMPILE/android-all-$ANDROID_ALL_COMPILE.jar" "$CACHE/android-all-$ANDROID_ALL_COMPILE.jar"
fetch "$MAVEN/org/robolectric/android-all/$ANDROID_ALL_RES/android-all-$ANDROID_ALL_RES.jar" "$CACHE/android-all-$ANDROID_ALL_RES.jar"
fetch "https://github.com/JetBrains/kotlin/releases/download/v$KOTLIN_VERSION/kotlin-compiler-$KOTLIN_VERSION.zip" "$CACHE/kotlin-compiler-$KOTLIN_VERSION.zip"
fetch "https://storage.googleapis.com/r8-releases/raw/$R8_VERSION/r8lib.jar" "$CACHE/r8lib-$R8_VERSION.jar"

KOTLINC_HOME="$CACHE/kotlinc-$KOTLIN_VERSION"
if [ ! -x "$KOTLINC_HOME/bin/kotlinc" ]; then
  rm -rf "$KOTLINC_HOME.tmp"
  mkdir -p "$KOTLINC_HOME.tmp"
  unzip -q "$CACHE/kotlin-compiler-$KOTLIN_VERSION.zip" -d "$KOTLINC_HOME.tmp"
  mv "$KOTLINC_HOME.tmp/kotlinc" "$KOTLINC_HOME"
  rm -rf "$KOTLINC_HOME.tmp"
fi

# Framework resources only (resources.arsc + manifest) for aapt linking.
FW_RES="$CACHE/framework-res-$ANDROID_ALL_RES.apk"
if [ ! -s "$FW_RES" ]; then
  tmp="$(mktemp -d)"
  unzip -q -o "$CACHE/android-all-$ANDROID_ALL_RES.jar" resources.arsc AndroidManifest.xml -d "$tmp"
  (cd "$tmp" && zip -q -0 "$FW_RES" resources.arsc AndroidManifest.xml)
  rm -rf "$tmp"
fi
ANDROID_JAR="$CACHE/android-all-$ANDROID_ALL_COMPILE.jar"

rm -rf "$WORK"
mkdir -p "$WORK/gen" "$WORK/classes" "$(dirname "$OUT_APK")"

# AGP takes the package name from the Gradle namespace; aapt needs it in the manifest.
sed "s|<manifest |<manifest package=\"$PACKAGE\" |" "$ROOT/app/src/main/AndroidManifest.xml" > "$WORK/AndroidManifest.xml"

echo "[1/5] Compiling resources"
aapt package -f -m --no-version-vectors \
  -J "$WORK/gen" -M "$WORK/AndroidManifest.xml" -S "$ROOT/app/src/main/res" -I "$FW_RES" \
  --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -F "$WORK/resources.ap_"

echo "[2/5] Compiling Kotlin"
javac --release 8 -nowarn -cp "$ANDROID_JAR" -d "$WORK/classes" $(find "$WORK/gen" -name '*.java') 2>&1 | grep -v '^warning: \[options\]' || true
"$KOTLINC_HOME/bin/kotlinc" \
  -jvm-target 1.8 -Xjdk-release=1.8 -Xlambdas=class -Xsam-conversions=class -no-reflect \
  -cp "$ANDROID_JAR:$WORK/classes" -d "$WORK/classes" \
  "$ROOT/app/src/main/java" 2>&1 | grep -v '^warning:' || true
[ -f "$WORK/classes/com/darshan/horizoncam/MainActivity.class" ] || { echo "Kotlin compilation failed" >&2; exit 1; }

echo "[3/5] Dexing (D8)"
(cd "$WORK/classes" && jar cf "$WORK/app-classes.jar" .)
JDK_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
mkdir -p "$WORK/dex"
java -cp "$CACHE/r8lib-$R8_VERSION.jar" com.android.tools.r8.D8 \
  --release --min-api "$MIN_SDK" \
  --lib "$JDK_HOME" --lib "$ANDROID_JAR" \
  --output "$WORK/dex" \
  "$WORK/app-classes.jar" "$KOTLINC_HOME/lib/kotlin-stdlib.jar"
mv "$WORK/dex/classes.dex" "$WORK/classes.dex"

echo "[4/5] Packaging"
cp "$WORK/resources.ap_" "$WORK/unsigned.apk"
(cd "$WORK" && zip -q unsigned.apk classes.dex)
zipalign -f -p 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"

echo "[5/5] Signing"
apksigner sign --ks "$ROOT/keystore/debug.keystore" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey --out "$OUT_APK" "$WORK/aligned.apk"
apksigner verify "$OUT_APK"
echo "Built $OUT_APK ($(du -h "$OUT_APK" | cut -f1))"
