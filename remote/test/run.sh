#!/bin/sh
# Compiles the gesture engine and its desktop test with a plain JDK and runs the test.
set -e
cd "$(dirname "$0")/.."
OUT=test/out/classes
rm -rf "$OUT"
mkdir -p "$OUT"
javac -source 11 -target 11 -Xlint:-options -d "$OUT" \
    src/io/github/goldenguts/bttvremote/gesture/GestureConfig.java \
    src/io/github/goldenguts/bttvremote/gesture/GestureEngine.java \
    test/GestureEngineTest.java
java -cp "$OUT" GestureEngineTest
