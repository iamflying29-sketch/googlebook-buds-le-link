#!/bin/bash
# Builds and signs BudsLELink.apk with the Android SDK in ~/tools/android-sdk.
# Set ANDROID_SDK to use an SDK somewhere else, and KS_PASS to choose your own keystore password.
set -euo pipefail

cd "$(dirname "$0")"
SDK="${ANDROID_SDK:-$HOME/tools/android-sdk}"
BT=$SDK/build-tools/35.0.0
JAR=$SDK/platforms/android-35/android.jar
KS=keystore.jks
KS_PASS="${KS_PASS:-change-me}"

# A fresh build directory each time (nothing is ever deleted).
B=build/$(date +%Y%m%d-%H%M%S)
mkdir -p "$B/classes" "$B/gen"

"$BT/aapt2" compile --dir res -o $B/res.zip
"$BT/aapt2" link -o $B/unsigned.apk -I "$JAR" --manifest AndroidManifest.xml \
    --min-sdk-version 33 --target-sdk-version 35 --version-code 4 --version-name 2.2 \
    --java $B/gen $B/res.zip

javac -Xlint:-options -source 11 -target 11 -cp "$JAR" -d $B/classes \
    $B/gen/local/budslelink/R.java src/local/budslelink/*.java
"$BT/d8" --min-api 33 --lib "$JAR" --output $B $(find $B/classes -name '*.class')

python3 -I -c 'import sys, zipfile; zipfile.ZipFile(sys.argv[1], "a").write(sys.argv[2], "classes.dex")' \
    $B/unsigned.apk $B/classes.dex
"$BT/zipalign" -p -f 4 $B/unsigned.apk $B/aligned.apk

if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" -alias budslelink \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Buds LE Link (local build)"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --out BudsLELink.apk $B/aligned.apk
"$BT/apksigner" verify BudsLELink.apk
echo "Built $(pwd)/BudsLELink.apk"
