# Bluetooth TV Remote

Use your Android phone as a TV remote without looking at it. The phone pairs with the TV as a Bluetooth LE keyboard, so the TV does not need Wi-Fi.

- Swipe anywhere = arrows: one swipe = one step, with a tick. A long drag or a hold adds steps
- Tap = OK; two-finger tap or swipe in from the edge = Back
- Hold, then slide = Home / Input / Mute / Settings; lift = Play/Pause
- Hold, then twist the wrist = seek
- A different vibration for each action; volume keys control the TV
- Text and voice typing, lock screen use, Galaxy Flip cover screen widget
- Optional: an Android TV box (Ugoos, Google TV, Shield) over Wi-Fi, with the same gestures

## Works with

- Phone: Android 13+ with Bluetooth LE peripheral support. Tested on a Galaxy Z Flip7.
- TV: tested on LG webOS (LG G5). Android TV / Google TV / Fire TV: choose it in Settings (not tested yet).
- Android TV box over Wi-Fi: tested on a Ugoos AM9 Pro (remote protocol version 1). Newer Google TV devices use
  version 2; the app finds the version when it pairs.

## Android TV box (Wi-Fi)

A box on an HDMI input of the TV gets its keys over Wi-Fi, through the Google TV remote service of the box
(ports 6466 and 6467). Input and power still go to the TV over Bluetooth. Volume and mute go to the TV, or to
the box when the TV link is down (Settings → Volume and mute go to). The box changes the TV volume only with
HDMI-CEC; without it, the box turns its own output down.

1. Give the box a fixed IP address in your router.
2. Settings → Android TV box → Address, then Pair. Type the code that the box shows.
3. Switch with ••• → Send keys to, or put "TV ↔ box" on a thumb-menu slot.

The link lives in the remote app, not in Remote Core: a Wi-Fi link has no pairing that breaks on an update.

## Install

Two apps: **Remote Core** keeps the Bluetooth link, **Bluetooth TV Remote** is the remote.

1. Install Remote Core first, then Bluetooth TV Remote.
2. Open Remote Core and allow Bluetooth.
3. On the TV, pair with the phone from the Bluetooth settings.

Updates of the remote app keep the pairing. A restart of Remote Core can need a new pairing.

## Why two apps

The TV pairs with a Bluetooth keyboard and remembers where its keys are (the GATT attribute handles).
When the app that holds the Bluetooth link restarts, Android gives that keyboard new handles. Some TVs
(tested: LG webOS) do not look again, so the keys stop working until you delete the pairing and pair again.

An app update always restarts the app. So the link lives in its own small app, **Remote Core**, which
rarely changes. All gestures, screens and settings live in **Bluetooth TV Remote**, which can update
often without touching the pairing. A crash in the remote screens cannot break the link either.

## Build

Needs the Android SDK (platform 36, build-tools 36.1.0) and a JDK. No Gradle.

```sh
./build.sh all            # core/core.apk and remote/remote.apk
./build.sh all install    # also installs with adb
./build.sh all aab        # Play bundles (see build.sh for the signing variables)
remote/test/run.sh        # gesture engine tests
```

## Support

The app is free and has no ads. If it helps you: [sponsor on GitHub](https://github.com/sponsors/GoldenGuts).

## License

GPL-3.0
