# Use the phone app

The Android app (arm64) connects to the daemon on your PC. You see the board, your agents and your mail, and you can
answer decisions from your phone.

## Turn on phone connections

Phone access is **off** by default. The daemon then listens only on your PC and needs no password.

1. On the PC, open **Settings > Daemon**.
2. Turn on **Allow phone connections**.

The daemon restarts and now listens on your network. It is protected by a secret token. Settings shows a connect link
(`infantry://connect?...`) and a **QR code**. The token is only shown when you reveal it.

## Connect the phone

1. Install the Infantry AI APK on the phone.
2. Scan the QR code, or open the connect link.

## Use Tailscale to connect from anywhere

The connect link prefers a [Tailscale](https://tailscale.com) address when your PC has one. Install Tailscale on the PC
and on the phone, and sign in to the same account on both. The phone then reaches your PC from any network, not only
your home Wi-Fi. Without Tailscale, the link uses your local network address.

## Phone updates

The phone app has its own update line, separate from the desktop app.

## Turn it off

Turn off **Allow phone connections**. The daemon goes back to listening on your PC only.
