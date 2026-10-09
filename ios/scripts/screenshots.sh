#!/bin/sh
# App Store screenshots on the 6.9-inch simulator: debug build, fixed screens (TVRemote/App/Shots.swift),
# a mock LG TV and a mock Kodi box on this Mac for the "connected" state. Output: ios/store/screenshots/
# Usage: ios/scripts/screenshots.sh [simulator name]   (default: iPhone 18 Pro Max)
set -e
cd "$(dirname "$0")/.."
SIM=${1:-iPhone 18 Pro Max}
OUT=store/screenshots
DIR=$(mktemp -d)
trap 'kill $MOCK $MOCK_TV 2>/dev/null || true; rm -rf "$DIR"' EXIT

xcodegen generate --quiet
xcrun simctl boot "$SIM" 2>/dev/null || true
xcrun simctl bootstatus "$SIM" -b >/dev/null
xcodebuild build -project TVRemote.xcodeproj -scheme TVRemote -destination "platform=iOS Simulator,name=$SIM" \
    -derivedDataPath build/dd -quiet
xcrun simctl install "$SIM" build/dd/Build/Products/Debug-iphonesimulator/TVRemote.app
xcrun simctl status_bar "$SIM" override --time 9:41 --batteryState charged --batteryLevel 100 --wifiBars 3 --cellularBars 4

mkdir -p "$DIR/kodi" "$DIR/tv"
MOCK_KODI_NAME=CoreELEC python3 scripts/mock-box.py kodi "$DIR/kodi" >/dev/null 2>&1 &
MOCK=$!
python3 scripts/mock-box.py webos "$DIR/tv" >/dev/null 2>&1 &
MOCK_TV=$!
sleep 1

mkdir -p $OUT
shot() {   # $1 file name, $2 screen, then extra launch arguments
    name=$1 screen=$2
    shift 2
    xcrun simctl terminate "$SIM" in.weenja.tvremote 2>/dev/null || true
    xcrun simctl launch "$SIM" in.weenja.tvremote -tutorialSeen YES -boxHost 127.0.0.1 -tvHost 127.0.0.1 -target 0 \
        -shot "$screen" "$@" >/dev/null
    sleep 4
    xcrun simctl io "$SIM" screenshot --type=png "$OUT/$name.png" >/dev/null 2>&1
    echo "$OUT/$name.png"
}
shot 1-thumb-menu menu
shot 2-swipe drag
shot 3-panel panel
shot 4-settings settings
shot 5-tutorial tutorial -shotLesson 2
xcrun simctl terminate "$SIM" in.weenja.tvremote 2>/dev/null || true
xcrun simctl status_bar "$SIM" clear
