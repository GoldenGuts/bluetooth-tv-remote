# Bluetooth TV Remote

Use your Android phone as a TV remote without looking at it. The phone pairs with the TV as a Bluetooth LE keyboard, so the TV needs no Wi-Fi and no app. An Android TV or Kodi box on the TV can get the same gestures over Wi-Fi.

- Swipe anywhere = arrows: one swipe = one step, with a tick. A long drag or a hold adds steps
- Tap = OK; two-finger tap or swipe in from the edge = Back
- Hold, then slide = Home / Input / Mute / Settings; lift = Play/Pause
- Hold, then twist the wrist = seek
- A different vibration for each action; volume keys control the TV, or a soundbar through HDMI-CEC
- Text and voice typing, lock screen use, Galaxy Flip cover screen widget
- Optional: an Android TV or Kodi box over Wi-Fi, with the same gestures, typing and a touchpad (Kodi)

## Works with

| Device | Link | Status |
|---|---|---|
| Galaxy Z Flip7 | the phone | tested |
| LG G5 TV (webOS 25) | Bluetooth LE keyboard | tested |
| Ugoos AM9 Pro, Android TV | Wi-Fi, Google TV remote protocol version 1 | tested |
| Ugoos AM9 Pro, CoreELEC (Kodi) | Wi-Fi, Kodi JSON-RPC and EventServer | tested |
| Samsung soundbar, on HDMI between the box and the TV | volume and mute through HDMI-CEC | tested |
| A TV with Android TV, Google TV or Fire TV | Bluetooth LE keyboard (Settings → TV type) | not tested |
| Newer Google TV boxes | Wi-Fi, Google TV remote protocol version 2 | not tested |

The phone needs Android 13 or newer with Bluetooth LE peripheral support. The app finds the remote protocol
version of an Android TV box when it pairs.

## Android TV or Kodi box (Wi-Fi)

A box on an HDMI input of the TV gets its keys over Wi-Fi. Input and power still go to the TV over Bluetooth.

- **Android TV, Google TV**: the Google TV remote service of the box (ports 6466 and 6467, TLS). Pair once.
- **Kodi** (for example CoreELEC): Kodi's JSON-RPC (port 9090) and EventServer (UDP 9777). No pairing.
  Touchpad mode moves Kodi's mouse pointer. If Kodi does not answer, turn on Settings → Services → Control →
  Allow remote control from applications on other systems.

A dual-boot box (Android TV and CoreELEC) keeps one address, and the remote follows the system that runs.

1. Give the box a fixed IP address in your router.
2. Settings → Android TV box → Address. The app finds Kodi by itself. For Android TV, type the code that the box shows.
3. Switch with ••• → Send keys to, or put "TV ↔ box" on a thumb-menu slot.

Volume and mute go to the TV, or to the box when the TV link is down (Settings → Volume and mute go to). The box
changes the volume of the TV or the soundbar only with HDMI-CEC; without it, the box turns its own output down.

The link lives in the remote app, not in Remote Core: a Wi-Fi link has no pairing that breaks on an update.

## Install

Two apps: **Remote Core** keeps the Bluetooth link, **Bluetooth TV Remote** is the remote.

1. Install Remote Core first, then Bluetooth TV Remote.
2. Open Remote Core and allow Bluetooth.
3. Keep Remote Core open and pair the TV with the phone from the TV's Bluetooth settings.

Updates of the remote app keep the pairing. A restart of Remote Core can need a new pairing.

To save battery, the phone advertises (so the TV can find it and connect) only while Remote Core is open,
while the remote is open (up to a minute; a key starts a new minute), or for a minute after a key from a
shortcut. It stops as soon as the TV connects; the link itself stays.

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

## iPhone

`ios/` holds the iPhone app, **Eyes-Free TV Remote** (home screen: TV Remote). It has no Bluetooth link: iOS does
not let an app act as a Bluetooth keyboard. It controls the TV and the box over Wi-Fi instead:

- **LG TV (webOS)**: the TV's own remote-app API (the one LG's ThinQ app uses): a WebSocket on port 3000, or
  3001 with TLS on newer firmware. The TV asks once to allow the iPhone. Buttons, volume, inputs, power off,
  typing and a mouse pointer. Power on is Wake-on-LAN: turn on "Turn on via Wi-Fi" in the TV settings.
- **Android TV or Kodi box**: the same protocols as the Android app.
- **Both**: like the Android app. Keys go to the box or the TV (••• → Send keys to, or "TV ↔ box" on a
  thumb-menu slot), input and power go to the TV, and Settings → Volume and mute go to picks the volume target.

Same as on Android: the gesture engine (a Swift port, with the same tests), the thumb menu, the knob, the
vibrations (Core Haptics), typing and voice, the touchpad, the tutorial, the cheat sheet and all settings.

Different on the iPhone:

- The app finds the TV and boxes on the Wi-Fi (Bonjour; an LG TV shows up through AirPlay). Typing an address
  works too: the app finds out whether an LG TV or a box answers there.
- Volume: ••• → Volume, a thumb-menu slot, or the knob. An app cannot take the iPhone volume buttons.
- No lock screen use, no cover screen widget, no Remote Core.
- Wake-on-LAN goes to the TV's address (and its subnet). Without Apple's multicast entitlement iOS may block the
  subnet broadcast; the direct packet still reaches a TV in standby.

Build (Xcode 26 or newer and XcodeGen; no signing needed for the simulator):

```sh
cd ios && xcodegen
xcodebuild test -project ios/TVRemote.xcodeproj -scheme TVRemote -destination 'platform=iOS Simulator,name=iPhone 17'
ios/scripts/test-link.sh       # pairing and link against mocks: Android TV v2, v1, Kodi, LG webOS
ios/scripts/screenshots.sh     # App Store screenshots
```

For an iPhone or the App Store: copy `ios/Signing.example.xcconfig` to `ios/Signing.xcconfig` and set the team.
The listing and the release steps are in `ios/store/`.

## Support

The app is free and has no ads. If it helps you: [sponsor on GitHub](https://github.com/sponsors/GoldenGuts).

## License

GPL-3.0
