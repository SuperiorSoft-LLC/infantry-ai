**What this phone app connects to:** a PC running the normal Infantry AI app, or BattalionOS with Infantry AI 0.8.4 (0.8.1 or later works). It does **not** connect to BattalionOS with Infantry AI 0.8.0.

**Infantry AI 0.8.4 for Android (arm64).** The same code as Infantry AI 0.8.4. The phone is a remote control: it runs no daemon and no agents. It connects to the Infantry AI on your PC and shows your board, sessions and mail.

**What changed since 0.8.2**
- A message to a worker that has finished and been removed now says so ("This worker is gone ... messages to it are not sent") and keeps your words in the box, instead of sending into nothing.
- A chat is not left blocked after the PC's daemon restarts; a gone chat stays open and says so.
- The message you are typing belongs to that one chat (drafts per chat).
- Chat numbers, removed sessions no longer come back to the list, and the other 0.8.4 fixes of the PC app.

This APK is signed with the same release key as 0.7.2, 0.8.0 and 0.8.2, so it installs over them and keeps your pairing. It is a prerelease and not the app's "latest" update, so the desktop updater does not see it. Check the file against the `.sha256` asset.

**Pair it**
1. On the PC app: Settings > Daemon > turn on "Allow phone connections".
2. Press "Show pairing code": a link and a QR code appear.
3. On the phone: scan the QR code, or paste the link.

**Away from home:** the phone has to reach the PC. Put both on Tailscale and use the PC's Tailscale address in the pairing code (those are listed first). Do not open the port to the internet.

**Updates from the next version on:** phones on 0.8.4 or older install the next APK by hand once; from then on the app finds updates itself (Settings > About).
