#!/usr/bin/env bash
# Builds Sony Ka Sender APK with the plain Android SDK tools (no Gradle needed).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK=/f/Android/sdk
BT=$SDK/build-tools/35.0.0
JAR=$SDK/platforms/android-35/android.jar
export JAVA_HOME=/f/Android/jdk17
export PATH="$JAVA_HOME/bin:$PATH"
A="$ROOT/android"
OUT="$ROOT/build"
rm -rf "$OUT"; mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "1/6 compile resources"
"$BT/aapt2.exe" compile --dir "$A/res" -o "$OUT/res/res.zip"
echo "2/6 link"
"$BT/aapt2.exe" link -o "$OUT/base.apk" -I "$JAR" --manifest "$A/AndroidManifest.xml" \
  -A "$A/assets" --java "$OUT/gen" --min-sdk-version 29 --target-sdk-version 35 \
  --version-code 1 --version-name 1.0 "$OUT/res/res.zip"
echo "3/6 javac"
javac --release 17 -nowarn -encoding UTF-8 -classpath "$JAR" -d "$OUT/classes" \
  $(find "$A/src" "$OUT/gen" -name "*.java")
echo "4/6 dex"
"$BT/d8.bat" --release --min-api 29 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name "*.class")
echo "5/6 package"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && "$JAVA_HOME/bin/jar.exe" uf "$OUT/unsigned.apk" classes.dex)
"$BT/zipalign.exe" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
echo "6/6 sign"
KS="$ROOT/tools/sks-debug.keystore"
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass sksdebug -keypass sksdebug -alias sks \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Sony Ka Sender" >/dev/null 2>&1
"$BT/apksigner.bat" sign --ks "$KS" --ks-pass pass:sksdebug --key-pass pass:sksdebug --ks-key-alias sks \
  --out "$ROOT/SonyKaSender.apk" "$OUT/aligned.apk"
"$BT/apksigner.bat" verify "$ROOT/SonyKaSender.apk" && echo "APK OK -> $ROOT/SonyKaSender.apk"
