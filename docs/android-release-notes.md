**What this phone app connects to:** a PC running the normal Infantry AI app, or BattalionOS with Infantry AI 0.8.11 (0.8.1 or later works). It does **not** connect to BattalionOS with Infantry AI 0.8.0.

**Infantry AI 0.8.8 for Android (arm64).** Built from the same code as Infantry AI 0.8.11 for BattalionOS. The phone is a remote control: it runs no daemon and no agents. It connects to the Infantry AI on your PC and shows your board, sessions and mail.

**What changed since 0.8.7**
- Settings > Appearance > UI size (85/100/115/130%, per device) replaces pinch zoom; pinch no longer breaks the layout.
- A session waiting on background work shows "idle · N background" instead of a false Running.
- A Running card's timer turns red after 30 minutes without new activity.
- The chat's buttons sit attached to the message box.
- The footer status line is back: last frame, free RAM, agents' CPU.
- One done notice per ticket.

This APK is signed with the same release key as 0.7.2, 0.8.0, 0.8.2, 0.8.4, 0.8.5, 0.8.6 and 0.8.7, so it installs over them and keeps your pairing. It is a prerelease and not the app's "latest" update, so the desktop updater does not see it. Check the file against the `.sha256` asset.

**Pair it**
1. On the PC app: Settings > Daemon > turn on "Allow phone connections".
2. Press "Show pairing code": a link and a QR code appear.
3. On the phone: scan the QR code, or paste the link.

**Away from home:** the phone has to reach the PC. Put both on Tailscale and use the PC's Tailscale address in the pairing code (those are listed first). Do not open the port to the internet.

**Updates:** phones on 0.8.5 or later find this update themselves (Settings > About). Phones on 0.8.4 or older install it by hand once.
