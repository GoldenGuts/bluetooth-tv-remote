# App Store listing: Eyes-Free TV Remote (iPhone)

The listing text is in the `asc` metadata format: `app-info/en-US.json` (name, subtitle, privacy URL) and
`version/1.0.0/en-US.json` (description, keywords, promotional text, support URL). The word "Bluetooth" is
not in the listing: the iPhone app has no Bluetooth link (iOS does not let an app act as a Bluetooth keyboard).
Screenshots (6.9-inch, 1320 x 2868) are in `screenshots/`; `../scripts/screenshots.sh` makes them again.

Check the text offline:

```sh
asc metadata validate --dir ios/store
```

## App Store Connect answers

| Field | Value |
|---|---|
| Name | Eyes-Free TV Remote (home screen: TV Remote) |
| Bundle ID | in.weenja.tvremote |
| SKU | TVREMOTE-IOS |
| Primary category | Utilities |
| Secondary category | Entertainment |
| Price | Free |
| Age rating | 4+ (no objectionable content, no web access, no user content) |
| App Privacy | Data Not Collected |
| Encryption | Exempt: only the system TLS (`ITSAppUsesNonExemptEncryption` = NO in Info.plist) |
| Copyright | 2026 Weenja |
| Devices | iPhone only, iOS 17 or newer |

## App Review notes

> The app is a remote control for an LG TV (webOS), an Android TV / Google TV box, or a Kodi box on the same
> Wi-Fi network. It connects only to the addresses that the user picks (Bonjour or typed). Without a box, you can try every
> gesture: the tutorial opens on the first launch, and ••• → Practice mode shows each gesture's name. The
> Local Network permission is used to reach the TV and the box; the microphone and speech recognition only for the Voice
> button (typing into TV search boxes).

Apple can ask for a video of the app with a real TV or box. Record one with the iPhone screen recorder and a phone
camera on the TV, and add the link to the notes.

## Steps (the user runs these: they need the Apple account)

1. Set the team: `cp ios/Signing.example.xcconfig ios/Signing.xcconfig` and fill in `TVREMOTE_TEAM_ID`.
2. Create the app record (web login, asks for the password and 2FA):

   ```sh
   asc web apps create --name "Eyes-Free TV Remote" --bundle-id in.weenja.tvremote --sku TVREMOTE-IOS --version 1.0.0 --apple-id YOUR_APPLE_ID
   ```

3. An API key for the other commands: `asc auth login --name tvremote ...` (App Store Connect → Users and Access → Integrations).
4. Push the text, then the screenshots:

   ```sh
   asc metadata push --app APP_ID --version 1.0.0 --platform IOS --dir ios/store --dry-run
   asc metadata push --app APP_ID --version 1.0.0 --platform IOS --dir ios/store
   asc screenshots upload --app APP_ID --version 1.0.0 --locale en-US --path ios/store/screenshots --device-type IPHONE_69
   ```

5. Archive and upload the build from Xcode (Product → Archive → Distribute), or with `xcodebuild archive` and
   `asc builds upload`. Then answer the questions above in App Store Connect and submit for review.
