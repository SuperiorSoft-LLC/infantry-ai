# Install the desktop app

## Download

Get the newest release from the
[downloads page](https://github.com/SuperiorSoft-LLC/infantry-ai/releases/latest). On Windows there are two
installers. Pick one:

- the **MSI** installer, or
- the **NSIS** setup `.exe`.

Run it and start **Infantry AI** from the Start menu.

## What happens on the first start

The app starts the Infantry AI **daemon** in the background. The daemon listens only on this PC, on port **18990**.
It keeps running when you close the window, so your agents keep working. The next time you open the app, it finds the
running daemon again.

Your data lives in `%LOCALAPPDATA%\SuperiorSoft\InfantryAI-Rust`.

The **guided setup** opens until three things are ready:

1. **Node.js.** If it is missing, the app installs it into its own folder.
2. **An agent tool**, such as Claude Code. Pick one and the app installs it.
3. **Sign-in** to that tool's provider. This step is skipped when the tool already works.

At the end it starts a first agent in a starter project and shows its reply. "Not now" closes the setup. You can open
it again at any time from **Settings > Daemon > Guided setup**.

## Updates

The app checks GitHub for a new release every 60 minutes. When one is found, you can download and install it from the
app. The installer runs on its own and the app restarts.

There are two channels:

| Channel | What you get |
|---|---|
| `stable` (default) | The latest full release. |
| `nightly` | The newest pre-release. Newer, less tested. |

Change the channel in the app's update settings.

## Next

[Create your first project](first-project.md).
