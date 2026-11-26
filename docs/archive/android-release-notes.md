**What this phone app connects to:** a PC running the normal Infantry AI app, or BattalionOS with Infantry AI 0.8.6 (0.8.1 or later works). It does **not** connect to BattalionOS with Infantry AI 0.8.0.

**Infantry AI 0.8.6 for Android (arm64).** The same code as Infantry AI 0.8.6. The phone is a remote control: it runs no daemon and no agents. It connects to the Infantry AI on your PC and shows your board, sessions and mail.

**What changed since 0.8.5**
- Infantry AI Hosting sign-in now reaches the real service. The fix is in the PC app (BattalionOS 0.8.6); the phone app itself is unchanged.

This APK is signed with the same release key as 0.7.2, 0.8.0, 0.8.2, 0.8.4 and 0.8.5, so it installs over them and keeps your pairing. It is a prerelease and not the app's "latest" update, so the desktop updater does not see it. Check the file against the `.sha256` asset.

**Pair it**
1. On the PC app: Settings > Daemon > turn on "Allow phone connections".
2. Press "Show pairing code": a link and a QR code appear.
3. On the phone: scan the QR code, or paste the link.

**Away from home:** the phone has to reach the PC. Put both on Tailscale and use the PC's Tailscale address in the pairing code (those are listed first). Do not open the port to the internet.

**Updates:** phones on 0.8.4 or older install 0.8.5 by hand once; from 0.8.5 on the app finds updates itself (Settings > About).
