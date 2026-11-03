# Infantry AI: Assistant knowledge

This file is what the in-app Assistant reads before it answers a question about setup, troubleshooting, known issues,
the roadmap or the pitch. It ships inside the app and is copied next to the Assistant at every start, so an edit here
reaches users with the next release.

How to edit it (owner and PM): plain Markdown, one `##` section per topic, short sentences. Keep the section names: the
Assistant's persona points at them. Never put a date, a token, a key or a private path in this file. Anything marked
**DRAFT** or **TODO** is waiting for the owner.

## What it is

Infantry AI (formerly Infantry) runs a team of AI coding agents on your own machine and lets you supervise them like a
team: a ticket board, roles, mail between agents, and shared project knowledge.

- A small local daemon starts and supervises agent CLI sessions (Claude Code, pi, opencode, or any CLI described in a
  JSON file). Each ticket gets its own git branch and worktree, so agents never edit each other's checkout.
- The desktop app shows every agent, what it is doing and what it needs from you. The `infantry` CLI (`agenthq` is a deprecated alias)
  and the Android app talk to the same daemon.
- Local first: the daemon listens on this machine only by default. Your code and your provider logins stay where they
  are; Infantry AI drives the CLIs you already installed and signed in to, and never stores provider API keys.
- Status: pre-1.0, private prototype. Things move quickly and some edges are rough (see Known issues).

## Setup: Windows

1. Run the installer (`.msi` or setup `.exe`). It installs per user, with no admin prompt, and adds a Start-menu entry
   and a desktop shortcut.
2. Start the app. It runs its own daemon; nothing else to start.
3. Install and sign in to at least one agent CLI: Claude Code (`claude`), `pi` or `opencode`. Settings can install a
   missing one for you (it needs Node 20 or newer).
4. Your data lives apart from the program, so upgrades and uninstalls keep your projects, tickets and knowledge.
5. Updates: the app checks for a newer version at every start; a toast and the footer button offer it. Nothing installs
   without your click, and it asks first if agents are working.

## Setup: macOS and Linux

- The desktop installer is Windows-first today. On macOS and Linux, use the headless server download (a zip or tar.gz
  with its own Java runtime, nothing else to install) or the Docker image, then connect with the CLI or the Android app.
- Start the server with `bin/infantry`. Without extra settings it listens on this machine only.
- To reach it from another device, set a token (at least 32 characters) first: the daemon refuses to listen on the
  network without one. Never expose the raw port to the internet; use a VPN such as Tailscale, an SSH tunnel, or a TLS
  reverse proxy.
- Updates: `infantry update` checks, shows the notes, asks, verifies the download and switches; `infantry rollback` goes
  back one version. Docker users pull the new image (the app tells you the exact command).

## Setup: Android (over Tailscale)

1. Install Tailscale on the PC and the phone, signed in to the same tailnet.
2. On the PC, run `infantry remote on`. It turns remote access on, creates a token once and prints the address and the
   token. (The Assistant can run this for you; it will not paste the token into the chat: read it in your own terminal
   or in Settings.)
3. Restart the app so the daemon listens on the network.
4. Allow the daemon port through the PC firewall from Tailscale addresses only (100.64.0.0/10).
5. On the phone, enter the address and the token on the connect screen.

`infantry remote status` shows them again; `infantry remote off` goes back to this-machine-only (restart to apply).

## First 10 minutes

1. Check the connection: the top bar should not say reconnecting. If it does, see Troubleshooting.
2. Check that at least one agent CLI is found: Settings (the gear, bottom right) shows the harnesses and whether each is
   installed. Install or sign in to one if none is.
3. Create a project and point it at a git repository.
4. Add a ticket to the board, give it a role (for example backend or frontend) and start it. A worker opens on the
   ticket's own branch; watch it in the agents list and answer its questions in the chat.
5. Use the knowledge base (notes, decisions, issues) for anything the agents should remember across sessions.

The Assistant can do any of these steps for you: just ask.

## FAQ

- **Does it send my code anywhere?** Infantry AI itself does not. The agent CLIs you use talk to their own providers, as
  they would without Infantry AI.
- **Which agents does it support?** Claude Code and pi are built in, opencode is supported, and any other CLI can be
  added with a JSON descriptor, no code change.
- **Do I need an API key?** Not for Infantry AI. Each agent CLI uses its own login or key.
- **Can agents step on each other?** Not on files: each ticket works in its own branch and worktree.
- **Can I use it from my phone?** Yes, with the Android app over Tailscale (see Setup: Android).
- **Is it free?** It is a private prototype today. **TODO (owner): pricing and license wording.** No license is chosen yet.
- **Where are my settings?** The gear, bottom right. The CLI covers the same ground (`infantry --help`).

## Troubleshooting

- **The app says reconnecting / daemon not reachable.** Restart the app. A taken port does not stop the daemon: it moves
  to the next free one and the app follows. If it still fails, the log says why (the Assistant can read it for you and
  summarise it).
- **A refused / 401 connection.** The token does not match. Check the token on the connecting device; the Assistant can
  tell you where it is, it will not paste it.
- **An agent CLI is missing from the picker.** The CLI is not installed, not on the daemon's PATH, or not signed in.
  Install it from Settings or by hand, sign in, then restart the app.
- **A worker shows FAILED right after start.** Usually the CLI is missing or not signed in. Open the session to see the
  first error.
- **A ticket will not start.** The project has no base branch set, or the ticket waits on a blocked-by ticket (the
  pending column is automatic and cannot be set by hand).
- **A console window keeps popping up (Windows).** Known issue, see below.
- **Slash commands do nothing in pi.** pi has no slash commands yet; they work in Claude Code sessions.

## Known issues

- **Console windows flash on Windows.** Some processes the daemon starts open a console window briefly. It is being
  worked on. Workaround: none needed, it is harmless; close it or ignore it.
- **pi has no slash commands yet** (`/model`, `/effort`, `/compact` work for Claude Code only).
- **opencode lists no slash commands.**
- **The installed Windows app cannot connect to a remote server yet**; it runs its own daemon. Use the CLI or the Android
  app for a remote one.
- **macOS and Linux desktop builds are less tested** than Windows.

## Coming next

**DRAFT for owner sign-off. No dates, ever. Only what is listed here may be called planned.**

- A Windows fix for the flashing console windows.
- A global Assistant popup you can open from anywhere in the app.
- More polish on the board, roles and knowledge base, driven by daily use.
- A public GitHub release with a license.
- **TODO (owner):** iOS app, desktop installers for macOS and Linux, remote connect in the Windows app: planned or not?

Anything not listed: say it is not on the roadmap yet and point to the issues link.

## Pitch

**DRAFT for owner sign-off.**

Short: Infantry AI runs a team of coding agents on your own machine and lets you lead them like a team: a ticket board,
roles, mail and shared memory, with every agent on its own branch. You keep your code, your tools and your logins; it
keeps the agents organised.

Long:

- **Who it is for:** developers and small teams who already use coding agents (Claude Code, pi, opencode) and want more
  than one at a time without the chaos.
- **Why it beats juggling terminals:** one board for all the work, one place to see what each agent is doing and what it
  needs from you, and memory that outlives a single chat.
- **Strengths:** local first; bring your own agent CLI (or describe any CLI in a JSON file); one branch per ticket so
  agents never collide; roles with standing rules; a phone app over Tailscale; safe, confirmed updates.
- **Honest limits:** pre-1.0, Windows-first desktop app, some known issues (see above).

Next steps to offer (pick ONE that fits the person): download it, star it on GitHub, or sponsor it.

## Where to get help

- README / documentation: **TODO (owner): public README URL**
- Report a bug or request a feature (GitHub issues): **TODO (owner): issues URL**
- Download: **TODO (owner): releases / download URL**
- Sponsor the project: **TODO (owner): sponsors URL**

Until these are filled in, say the links are not published yet and offer to file the request on the project's own
board instead.
