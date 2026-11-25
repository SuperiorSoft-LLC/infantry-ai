Works with a 0.7.2 desktop daemon: the wire protocol did not change between 0.7.2 and 0.8.0 and the app checks no daemon version. Updating the desktop app to 0.8.0 is not needed to use this phone app.

**Infantry AI 0.8.0 for Android (arm64).** The phone is a remote control: it runs no daemon and no agents. It connects to the Infantry AI daemon on your desktop and shows your board, sessions and mail.

This APK is signed with the same release key as 0.7.2, so it installs over 0.7.2 and keeps your pairing. It is a prerelease and not the app's "latest" update, so the desktop updater does not see it. Check the file against the `.sha256` asset.

**Pair it**
1. On the desktop app: Settings > Daemon > turn on "Allow phone connections".
2. Press "Show pairing code": a link and a QR code appear.
3. On the phone: scan the QR code, or paste the link.

**Away from home:** the phone has to reach the desktop. Put both on Tailscale and use the desktop's Tailscale address in the pairing code (those are listed first). Do not open the port to the internet.
