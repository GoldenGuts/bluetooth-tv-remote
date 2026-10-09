# Privacy policy: Bluetooth TV Remote and Bluetooth TV Remote Core

Last updated: 2026-10-09

These apps do not collect, store, share or sell any personal data.

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
