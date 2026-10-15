#!/bin/sh
# Runs the real pairing and Wi-Fi link in the simulator against a mock box on this Mac (scripts/mock-box.py):
# Android TV protocol version 2, version 1, Kodi (and Kodi 18), and an LG webOS TV. Needs the local ports 6466, 6467, 9090,
# 9777, 3000, 3001 and 9 free.
# Usage: ios/scripts/test-link.sh [simulator name]   (default: iPhone 17)
set -e
cd "$(dirname "$0")/.."
SIM=${1:-iPhone 17}
DIR=$(mktemp -d)
DD=build/dd
trap 'kill $MOCK 2>/dev/null || true; rm -rf "$DIR"' EXIT

xcodegen generate --quiet
xcodebuild build-for-testing -project TVRemote.xcodeproj -scheme TVRemote -destination "platform=iOS Simulator,name=$SIM" \
    -derivedDataPath $DD -quiet

for MODE in ${MODES:-atv2 atv1 kodi kodi18 webos}; do
    rm -rf "$DIR/$MODE" && mkdir -p "$DIR/$MODE"
    python3 scripts/mock-box.py $MODE "$DIR/$MODE" > "$DIR/$MODE.out" 2>&1 &
    MOCK=$!
    sleep 2
    echo "==> $MODE"
    if TEST_RUNNER_TVREMOTE_MOCK_DIR="$DIR/$MODE" TEST_RUNNER_TVREMOTE_MOCK_MODE=$MODE \
        xcodebuild test-without-building -project TVRemote.xcodeproj -scheme TVRemote \
        -destination "platform=iOS Simulator,name=$SIM" -derivedDataPath $DD \
        -only-testing:TVRemoteTests/LinkIntegrationTests 2>&1 | grep -E "✔ Test run|✘|Issue|error:" ; then :; fi
    echo "--- mock log"
    cat "$DIR/$MODE/log.txt"
    kill $MOCK 2>/dev/null; wait $MOCK 2>/dev/null || true
done
