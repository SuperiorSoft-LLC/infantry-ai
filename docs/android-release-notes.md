**What this phone app connects to:** a PC running the normal Infantry AI app, or BattalionOS with Infantry AI 0.8.1 or later. It does **not** connect to BattalionOS with Infantry AI 0.8.0.

**Infantry AI 0.8.1 for Android (arm64).** The phone is a remote control: it runs no daemon and no agents. It connects to the Infantry AI on your PC and shows your board, sessions and mail.

**What changed since 0.8.0**
- The app opens on the connect screen whenever the phone has no working PC: a fresh install, a PC that does not answer within about 5 seconds, a login the PC no longer accepts, or a login the phone cannot read. The screen says which PC and why, with **Scan QR** first and large, a pasted link, **Retry** and **Forget**. 0.8.0 opened the app straight onto a saved PC and, when that PC was off, said the daemon did not start.
- The saved PC is kept until you press Forget or pair again. Retry never loses it.
- **Scan QR** is one tap away inside the app (the QR button in the top bar and on the "not answering" banner), without forgetting the saved PC.
- Nothing on the phone talks about starting a daemon or installing agent tools any more: the guided setup, the first-run Node.js and agent tool steps and the daemon wording are hidden on the phone. Those are the PC's.

This APK is signed with the same release key as 0.7.2 and 0.8.0, so it installs over them and keeps your pairing. It is a prerelease and not the app's "latest" update, so the desktop updater does not see it. Check the file against the `.sha256` asset.

**Pair it**
1. On the PC app: Settings > Daemon > turn on "Allow phone connections".
2. Press "Show pairing code": a link and a QR code appear.
3. On the phone: scan the QR code, or paste the link.

**Away from home:** the phone has to reach the PC. Put both on Tailscale and use the PC's Tailscale address in the pairing code (those are listed first). Do not open the port to the internet.
