#!/bin/sh
# Builds Remote Core and/or the remote app without Gradle. Both use the same key (signature permission).
# Usage: ./build.sh core|remote|all [install|aab]
#   install: also installs with adb. Install core FIRST: it defines the permission the remote needs.
#   aab:     builds core/core.aab and remote/remote.aab for Google Play. Needs BUNDLETOOL (jar path),
#            UPLOAD_KEYSTORE and UPLOAD_PASSWORD (alias "upload").
# Reinstalling core restarts the Bluetooth link: the TV may need a new pairing. Reinstall remote freely.
set -e
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
BT=$SDK/build-tools/${BUILD_TOOLS:-36.1.0}
JAR=$SDK/platforms/android-36/android.jar
KS=$PWD/release.keystore

[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias tv -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=bttvremote" >/dev/null 2>&1

# $1 app, $2 output, $3 extra aapt2 link flags
link() {
    out=$1/gen
    res=""
    if [ -d "$1/res" ]; then
        "$BT/aapt2" compile --dir "$1/res" -o "$out/res.zip"
        res="$out/res.zip"
    fi
    mkdir -p "$out/java"
    "$BT/aapt2" link -I "$JAR" --manifest "$1/AndroidManifest.xml" -o "$2" $3 \
        ${res:+-R "$res" --java "$out/java" --auto-add-overlay}
}

compile() {
    out=$1/gen
    mkdir -p "$out/classes"
    javac -nowarn -Xlint:-options -source 11 -target 11 -cp "$JAR" -d "$out/classes" \
        $(find "$1/src" common/src "$out/java" -name '*.java')
    "$BT/d8" --lib "$JAR" --min-api 33 --output "$out" $(find "$out/classes" -name '*.class')
}

apk() {
    out=$1/gen
    rm -rf "$out" && mkdir -p "$out"
    link "$1" "$out/unsigned.apk"
    compile "$1"
    (cd "$out" && zip -q unsigned.apk classes.dex)
    "$BT/zipalign" -f -p 4 "$out/unsigned.apk" "$out/aligned.apk"
    "$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --out "$1/$1.apk" "$out/aligned.apk"
    echo "built $1/$1.apk"
    if [ "$2" = install ]; then adb install -r "$1/$1.apk"; fi
}

aab() {
    : "${BUNDLETOOL:?set BUNDLETOOL to the bundletool jar}" "${UPLOAD_KEYSTORE:?}" "${UPLOAD_PASSWORD:?}"
    out=$1/gen
    rm -rf "$out" && mkdir -p "$out"
    link "$1" "$out/proto.apk" --proto-format
    compile "$1"
    # Module layout: manifest/, dex/, res/, resources.pb
    mkdir -p "$out/module/manifest" "$out/module/dex"
    (cd "$out/module" && unzip -q ../proto.apk)
    mv "$out/module/AndroidManifest.xml" "$out/module/manifest/"
    cp "$out/classes.dex" "$out/module/dex/"
    (cd "$out/module" && zip -q -r ../base.zip .)
    rm -f "$1/$1.aab"
    java -jar "$BUNDLETOOL" build-bundle --modules="$out/base.zip" --output="$1/$1.aab"
    jarsigner -keystore "$UPLOAD_KEYSTORE" -storepass "$UPLOAD_PASSWORD" "$1/$1.aab" upload >/dev/null
    echo "built $1/$1.aab"
}

build() {
    if [ "$2" = aab ]; then aab "$1"; else apk "$1" "$2"; fi
}

case "$1" in
    core) build core "$2" ;;
    remote) build remote "$2" ;;
    all) build core "$2"; build remote "$2" ;;
    *) echo "usage: $0 core|remote|all [install|aab]"; exit 1 ;;
esac
