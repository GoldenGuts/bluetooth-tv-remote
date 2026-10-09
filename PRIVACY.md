# Privacy policy: Bluetooth TV Remote, Bluetooth TV Remote Core, and Eyes-Free TV Remote (iPhone)

Last updated: 2026-10-09

These apps do not collect, store, share or sell any personal data.

## Eyes-Free TV Remote for iPhone

- No account, no analytics, no ads, no tracking.
- The app sends key presses and typed text to the LG TV and the Android TV or Kodi box that you set up, over
  your local Wi-Fi, and to no other address. It uses the Local Network permission for this, and Bonjour to find
  them. An LG TV gets them through its own remote-app connection (TLS on newer TVs); the app keeps the key that
  the TV gives when you allow the iPhone, and the TV's network address (MAC) to turn it on. An Android TV box
  gets them through the Google TV remote protocol, encrypted with TLS. A Kodi box gets them through Kodi's own
  remote control (JSON-RPC and EventServer), which Kodi does not encrypt.
- Voice typing (the Voice button only) uses the microphone and Apple's speech recognition. On iPhones that can,
  the recognition runs on the iPhone. Otherwise Apple processes the audio under Apple's privacy policy. The app
  gets only the recognized text and sends it to the TV or the box.
- Settings, the addresses and the pairing keys stay on your iPhone (the box key in the iOS Keychain).

## Bluetooth TV Remote and Remote Core (Android)

- No account, no analytics, no ads, no tracking.
- Remote Core has no internet permission. It talks only to your TV, directly over Bluetooth, as a keyboard.
- Bluetooth TV Remote has the internet permission for one use only: if you set up an Android TV or Kodi box,
  it sends key presses and typed text to that box over your local Wi-Fi. An Android TV box gets them through
  the Google TV remote protocol, encrypted with TLS. A Kodi box gets them through Kodi's own remote control
  (JSON-RPC and EventServer), which Kodi does not encrypt. The app connects to no other address and sends
  nothing to the internet.
- Settings stay on your phone.
- Voice typing uses the speech service of your phone. The apps get only the recognized text and send it to the TV as key presses.

Permissions and why:

- Bluetooth (connect, advertise): to act as a Bluetooth keyboard for the TV.
- Internet (Bluetooth TV Remote only): to reach an Android TV or Kodi box on your local network, if you set one up.
- Notifications and foreground service: to keep the Bluetooth link alive and show that it runs.
- Start at boot: to start the link after a phone restart.
- Vibration: feedback for each action.

Questions: use the developer contact on the Google Play listing.
