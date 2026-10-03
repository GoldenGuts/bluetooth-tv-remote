#!/bin/sh
# Builds Remote Core and/or TV Remote without Gradle. Both APKs use the same key (signature permission).
# Usage: ./build.sh core|remote|all [install]
# Install Remote Core first: TV Remote gets the CONTROL permission only if the core is already installed.
# WARNING: installing core kills its process = the GATT server = the pairing with the TV. Install remote freely.
set -e
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
BT=$SDK/build-tools/35.0.0
JAR=$SDK/platforms/android-35/android.jar
KS=$PWD/release.keystore

[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias tv -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=bttvremote" >/dev/null 2>&1

build() {
    app=$1
    out=$app/gen
    rm -rf "$out" && mkdir -p "$out/classes"
    res=""
    if [ -d "$app/res" ]; then
        "$BT/aapt2" compile --dir "$app/res" -o "$out/res.zip"
        res="$out/res.zip"
        mkdir -p "$out/java"
    fi
    "$BT/aapt2" link -I "$JAR" --manifest "$app/AndroidManifest.xml" -o "$out/unsigned.apk" \
        ${res:+-R "$res" --java "$out/java" --auto-add-overlay}
    javac -nowarn -Xlint:-options -source 11 -target 11 -cp "$JAR" -d "$out/classes" \
        $(find "$app/src" common/src -name '*.java') $( [ -n "$res" ] && find "$out/java" -name '*.java')
    "$BT/d8" --lib "$JAR" --min-api 33 --output "$out" $(find "$out/classes" -name '*.class')
    (cd "$out" && zip -q unsigned.apk classes.dex)
    "$BT/zipalign" -f -p 4 "$out/unsigned.apk" "$out/aligned.apk"
    "$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --out "$app/$app.apk" "$out/aligned.apk"
    echo "built $app/$app.apk"
    if [ "$2" = install ]; then adb install -r "$app/$app.apk"; fi
}

case "$1" in
    core) build core "$2" ;;
    remote) build remote "$2" ;;
    all) build core "$2"; build remote "$2" ;;
    *) echo "usage: $0 core|remote|all [install]"; exit 1 ;;
esac
