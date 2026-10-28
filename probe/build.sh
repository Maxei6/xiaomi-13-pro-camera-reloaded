#!/usr/bin/env bash
# Stage 19 access probe build: ECJ -> D8 -> aapt2 manifest -> dex injection -> zipalign -> apksigner (project key).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
PROJ="$(cd "$ROOT/../.." && pwd)"
ART="$PROJ/artifacts"
JAVA_HOME="$ART/tools/jdk4py/jdk4py/java-runtime"; export PATH="$JAVA_HOME/bin:$PATH"
BT="$ART/tools/sdk/bt/android-14"; ANDROID_JAR="$ART/tools/sdk/plat/android-34/android.jar"
ECJ="$ART/tools/dl/ecj.jar"; D8JAR="$BT/lib/d8.jar"; APKSIGNER="$BT/lib/apksigner.jar"; ZIPALIGN="$BT/zipalign"; AAPT2="$BT/aapt2"
KEYDIR="$PROJ/stage3/keys"; KS="$KEYDIR/experiment-keystore.p12"; KS_ALIAS="xmcam-oemderived-exp"
OUT="$ROOT/build"; REL="$ROOT/candidates"
rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/dex" "$REL"
find "$ROOT/app/src" -name '*.java' > "$OUT/sources.txt"
java -jar "$ECJ" -1.8 -nowarn -encoding UTF-8 -bootclasspath "$ANDROID_JAR" -d "$OUT/classes" @"$OUT/sources.txt"
java -cp "$D8JAR" com.android.tools.r8.D8 --release --min-api 29 --lib "$ANDROID_JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
"$AAPT2" link -o "$OUT/base.apk" --manifest "$ROOT/app/AndroidManifest.xml" -I "$ANDROID_JAR" --min-sdk-version 29 --target-sdk-version 34
cp "$OUT/base.apk" "$OUT/unsigned.apk"; ( cd "$OUT/dex" && zip -q "$OUT/unsigned.apk" classes.dex )
"$ZIPALIGN" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
java -jar "$APKSIGNER" sign --ks "$KS" --ks-key-alias "$KS_ALIAS" --ks-pass "file:$KEYDIR/store.pass" --key-pass "file:$KEYDIR/key.pass" --out "$REL/XMCam-lens-probe.apk" "$OUT/aligned.apk"
java -jar "$APKSIGNER" verify --min-sdk-version 29 "$REL/XMCam-lens-probe.apk" && echo VERIFY_OK
sha256sum "$REL/XMCam-lens-probe.apk"
