# Bluetooth TV Remote

Use your Android phone as a TV remote without looking at it. The phone pairs with the TV as a Bluetooth LE keyboard, so the TV does not need Wi-Fi.

- Drag anywhere = arrows, with a tick for each step
- Tap = OK, swipe in from the edge = Back
- Hold, then slide = Home / Input / Mute / Settings; lift = Play/Pause
- Hold, then twist the wrist = seek
- A different vibration for each action; volume keys control the TV
- Text and voice typing, lock screen use, Galaxy Flip cover screen widget

## Works with

- Phone: Android 13+ with Bluetooth LE peripheral support. Tested on a Galaxy Z Flip7.
- TV: tested on LG webOS (LG G5). Android TV / Google TV / Fire TV: choose it in Settings (not tested yet).

## Install

Two apps: **Remote Core** keeps the Bluetooth link, **Bluetooth TV Remote** is the remote.

1. Install Remote Core first, then Bluetooth TV Remote.
2. Open Remote Core and allow Bluetooth.
3. On the TV, pair with the phone from the Bluetooth settings.

Updates of the remote app keep the pairing. A restart of Remote Core can need a new pairing.

## Build

Needs the Android SDK (platform 35, build-tools 35) and a JDK. No Gradle.

```sh
./build.sh all            # core/core.apk and remote/remote.apk
./build.sh all install    # also installs with adb
remote/test/run.sh        # gesture engine tests
```

## License

GPL-3.0
