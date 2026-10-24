<p align="center"><img src="assets/branding/infantry-ai-emblem.png" alt="Infantry AI emblem" width="220"></p>

<h1 align="center">Infantry AI</h1>

<p align="center">
  <b>Run a whole team of coding agents from one ticket board.</b><br>
  Windows · Android · Headless server (Docker or plain archive)
</p>

<p align="center">
  <a href="#quickstart">Quickstart</a> ·
  <a href="#headless-server">Server setup</a> ·
  <a href="#faq">FAQ</a> ·
  <a href="#roadmap-and-known-issues">Known issues</a> ·
  <a href="#getting-help">Help</a> ·
  <a href="#support-the-project">Sponsor</a>
</p>

---

Infantry AI (formerly AgentHQ) is a command post for coding agents. You file tickets on a board, give each one a role
(full-stack engineer, QA, PM, ...) and a provider (Claude Code, opencode, pi, or any CLI you describe in a JSON file),
and Infantry AI starts the agent on its own git branch and worktree, keeps its mail, progress and shared project
knowledge in one daemon, and lets you watch, steer and merge the work from your desktop or your phone. The daemon runs
on your machine or on your own server: your code, keys and agent output never pass through a third-party service.

> **Status: early preview.** The app is used daily by its own team, but it is not 1.0. Read
> [Roadmap and known issues](#roadmap-and-known-issues) before you rely on it.

<p align="center"><img src="assets/branding/infantry-ai-icon-512.png" alt="Infantry AI app icon" width="120"></p>

<!-- SCREENSHOTS: replace with real captures of the board, an agent session and the Android app when available.
     Suggested paths: assets/screenshots/board.png, assets/screenshots/session.png, assets/screenshots/android.png -->

## Contents

- [The clients](#the-clients)
- [Quickstart](#quickstart)
- [Headless server](#headless-server) (Docker or plain archive)
- [Pairing a phone or another client (QR)](#pairing-a-phone-or-another-client-qr)
- [Updates](#updates)
- [FAQ](#faq) and [Troubleshooting](#troubleshooting)
- [Roadmap and known issues](#roadmap-and-known-issues)
- [Getting help](#getting-help)
- [Contributing](#contributing), [Support the project](#support-the-project), [License](#license)
- [Building from source](#building-from-source) and [reference](#reference)

## The clients

| Client | What it is | How to get it |
|---|---|---|
| **Windows desktop** | The full app: board, agents, mail, knowledge. It runs its own daemon, so it works on its own with no server. | `InfantryAI-<version>.msi` from the [latest Release](../../releases/latest) (or the `-windows.zip` app image). |
| **Android** | A phone client for a daemon running elsewhere (your PC or a server): watch agents, answer questions, move tickets. | `InfantryAI-<version>.apk` from the [latest Release](../../releases/latest), installed by hand (not on Google Play yet). |
| **Web** | A browser client. | **Not available yet.** It is on the [roadmap](#roadmap-and-known-issues). |
| **CLI** (`infantry`, alias `agenthq`) | Every board, ticket, mail and knowledge command, for scripts and for the agents themselves. | Ships inside the desktop app and the server archive. |
| **Headless server** | The daemon with no UI, for a home server, a VM or a cloud box. | Docker image (build it yourself for now) or `InfantryAI-server-<version>-<os>-<arch>` archive from the Release. |

macOS and Linux desktop apps are not packaged yet; on those systems use the headless server plus the Android app or the CLI.

## Quickstart

### Windows (about 5 minutes)

1. Download `InfantryAI-<version>.msi` from the [latest Release](../../releases/latest) and run it. It installs **per
   user** (no admin prompt). The installer is not code-signed yet, so Windows SmartScreen will warn: choose
   **More info > Run anyway**.
2. Install at least one agent CLI and sign in to it, for example [Claude Code](https://docs.anthropic.com/en/docs/claude-code)
   (`npm install -g @anthropic-ai/claude-code`, then run `claude` once to log in). The app can also install the supported
   CLIs for you (`infantry harness-install claude`, also `opencode` and `pi`; needs Node 20+).
3. Start **Infantry AI** from the Start menu. It starts its own daemon; nothing else to run.
4. Create a project and point it at a git repository and a base branch (for example `main`).
5. Create a ticket, give it a role, and press **Start**. The agent works on its own branch in its own worktree; you watch
   its session live and read its report when it is done.

Your data lives in `%LOCALAPPDATA%\SuperiorSoft\AgentHQ`, apart from the program, so upgrades and uninstalls keep it.
Details: [docs/AGENTHQ-INSTALL.md](docs/AGENTHQ-INSTALL.md).

### Android (about 5 minutes)

The Android app is a client: it needs a daemon to connect to (your Windows app or a [headless server](#headless-server)).

1. Download `InfantryAI-<version>.apk` from the [latest Release](../../releases/latest) on the phone and install it
   (allow "install unknown apps" for your browser when Android asks).
2. Make the daemon reachable from the phone. On your own network or over [Tailscale](https://tailscale.com), run
   `infantry remote on` on the PC and restart the app; it prints the address and a token. See
   [ANDROID-TAILSCALE.md](superiorSoftLibrary/agentHq/docs/ANDROID-TAILSCALE.md).
3. On the PC open **Configuration > Pair a device** and scan the QR code with the phone's connect screen (or type the
   `ws://<host>:<port>/ws` address and the token by hand). See [Pairing](#pairing-a-phone-or-another-client-qr).

### Web

There is no web client yet. Until there is, use the Android app or the CLI against a server.

### Server (about 5 minutes with Docker)

See [Headless server](#headless-server) below: build the image, create a token, `docker run`, then connect the
Android app or the CLI.

## Headless server

The same daemon as the desktop app, with no UI. Use it on a home server, a VM or a cloud machine, then connect from
Android or the CLI.

**Security first**: the token is the only credential, and it grants command execution through agents. Never expose the
raw port to the internet. Bind to loopback and put a TLS reverse proxy (Caddy, nginx) or a VPN/SSH tunnel (Tailscale works
well) in front; it must pass WebSocket upgrades.

### Docker

The image (`infantryai/infantry-ai`) is **not published yet**; build it locally:

    ./gradlew :superiorSoftLibrary:agentHq:dockerContext
    docker build -t infantryai/infantry-ai:dev superiorSoftLibrary/agentHq/build/docker

Run (the token is mandatory, at least 32 characters: the daemon refuses a non-loopback bind without one):

    openssl rand -hex 32 > agenthq_token
    openssl rand -hex 32 > agenthq_keystore_key
    docker run -d --name infantry-ai -p 127.0.0.1:8787:8787 \
      -e AGENTHQ_TOKEN_FILE=/run/secrets/agenthq_token \
      -v ./agenthq_token:/run/secrets/agenthq_token:ro \
      -e AGENTHQ_KEYSTORE_KEY_FILE=/run/secrets/agenthq_keystore_key \
      -v ./agenthq_keystore_key:/run/secrets/agenthq_keystore_key:ro \
      -v agenthq-data:/data infantryai/infantry-ai:dev
    infantry --url ws://127.0.0.1:8787/ws --token-file ./agenthq_token board

(Git Bash on Windows rewrites `/run/...` into a Windows path: prefix the `docker run` with `MSYS_NO_PATHCONV=1`.)

- **Volume**: `/data` holds the database, logs and state (`AGENTHQ_STATE_DIR`). Keep the same volume across image upgrades.
- **Config**: `AGENTHQ_PORT` (default 8787), `AGENTHQ_HOST` (default `0.0.0.0`), `AGENTHQ_TOKEN` or `AGENTHQ_TOKEN_FILE`
  (e.g. a Docker secret at `/run/secrets/agenthq_token`); other `AGENTHQ_*` variables are read as usual.
- **Health**: `HEALTHCHECK` polls `GET /health` (the one path open without a token).
- **Repositories**: starting a ticket cuts a git worktree, so mount a checkout and name its base branch:
  `-e AGENTHQ_REPO=/work/repo -e AGENTHQ_BASE_BRANCH=main` (see [docker/README.md](docker/README.md)).
- **Agent CLIs**: the image ships Node/npm, not the CLIs. Install the ones you use from any client:
  `infantry --url ... --token-file ... harness-install claude` (also `opencode`, `pi`); they land in
  `/data/harness-tools`, so they survive container upgrades, and `harness-status` shows them. Sign-in is per CLI: pass the
  provider's API key (`-e ANTHROPIC_API_KEY=...`) or log in once with `docker exec -it infantry-ai claude` and keep the login with
  `-v agenthq-home:/home/agenthq`. A CLI you already have can be mounted instead (`-v /path/to/cli:/usr/local/bin/<name>:ro`).
- **Keystore key**: the daemon seals its secrets in `/data/keystore.json`. A container has no OS keychain, so mount the master
  key and point `AGENTHQ_KEYSTORE_KEY_FILE` at it (as above; `AGENTHQ_KEYSTORE_KEY` also works). Without it the daemon falls
  back to `/data/keystore.key`, which is only as safe as the volume. Keep the key file outside the volume and back it up.
- The container runs as non-root (uid 10001). Do not bake the token into an image.

### Plain archive (no Docker)

The same daemon with its own Java runtime bundled (nothing to install), one per OS on the Release:
`InfantryAI-server-<version>-windows-x64.zip`, `InfantryAI-server-<version>-linux-x64.tar.gz`,
`InfantryAI-server-<version>-macos-aarch64.tar.gz`. Unpack it and start the daemon (`README.txt` inside lists every setting):

    # Linux / macOS
    AGENTHQ_TOKEN_FILE=./agenthq_token AGENTHQ_HOST=0.0.0.0 ./InfantryAI-server-<version>-linux-x64/bin/agenthq-server.sh
    # Windows (cmd)
    set AGENTHQ_TOKEN_FILE=%CD%\agenthq_token& set AGENTHQ_HOST=0.0.0.0& InfantryAI-server-<version>-windows-x64\bin\agenthq-server.bat

Without `AGENTHQ_HOST` it binds to 127.0.0.1 only. State goes to `~/.agenthq` unless `AGENTHQ_STATE_DIR` says otherwise, so do
not run it next to an installed desktop app on the same state dir and port. `bin/agenthq` in the archive is the CLI, on the
same bundled runtime. Agent CLIs are the machine's own (`infantry harness-install <id>` needs Node 20+).

## Pairing a phone or another client (QR)

A client connects to a daemon with two things: its address (`ws://<host>:<port>/ws`, or `wss://` behind TLS) and its
token. Pairing hands both over in one step:

1. On a client that is already connected (the desktop app), open **Configuration > Pair a device**. It shows a QR code
   and a copyable link of the form `agenthq://connect?url=...&token=...&name=...`.
2. On the new client (the Android connect screen), **scan** the code with the camera, or **paste** the link. The desktop
   app can also open a QR image file.
3. The new client saves the server in its list of saved servers and connects.

The QR code and the link contain the token: treat them like a password. Show them only to devices you own, and never
post a screenshot of them. To revoke access, change the token (`infantry remote off` / `on`, or a new token file) and
restart the daemon.

## Updates

- **Windows app**: checks the GitHub Releases of this repository at every start and from **Configuration > Updates >
  Check for updates**. When a newer version exists, a dialog shows the release notes. **Update** downloads the MSI,
  verifies its size and SHA-256 against the release's `SHA256SUMS` (a mismatch is refused and deleted), asks first if
  agents are still running, then upgrades in place and restarts. Your data is kept.
- **Headless server**: `infantry update` (or **Configuration > Server > Update**) installs the archive for its own OS from
  the same Release, checks it against `SHA256SUMS`, keeps the previous version, and `infantry rollback` returns to it. The
  data dir is never touched.
- **Docker**: an image cannot replace itself. The daemon tells you a newer release exists and the `docker pull` to run;
  recreate the container on the same `/data` volume.
- **Android**: install the newer APK over the old one. Android only accepts it when both are signed with the same key,
  so stay on `InfantryAI-<version>.apk` (release-signed) builds; a `-debug.apk` cannot upgrade a release install.

Details: [docs/RELEASING.md](docs/RELEASING.md) and [UPDATES.md](superiorSoftLibrary/agentHq/docs/UPDATES.md).

## FAQ

**Which agents does it run?** Claude Code, opencode and pi out of the box. Any other command-line agent can be added
with a JSON descriptor; see [QUICKSTART.md](superiorSoftLibrary/agentHq/docs/QUICKSTART.md#4-add-a-harness-with-a-json-descriptor).

**Do I need an account with you?** No. There is no Infantry AI cloud service. You bring your own agent CLIs and their
logins or API keys; the daemon runs on your hardware.

**Where is my data?** Windows app: `%LOCALAPPDATA%\SuperiorSoft\AgentHQ`. Server: `~/.agenthq` or `AGENTHQ_STATE_DIR`
(`/data` in Docker). Secrets (tokens, keys) are sealed in a keystore there.

**Does it cost anything?** The app is free to download. The agents use your own subscriptions or API keys with their
providers, billed by those providers.

**Can several people share one server?** One daemon has one token today; anyone with it has full access. Per-user
accounts are not built yet.

**Why do some names say AgentHQ?** That was the project's earlier name. Folders, variables (`AGENTHQ_*`, also read as
`INFANTRY_*`) and the `agenthq` command keep working so nothing breaks on upgrade.

**Is it safe to expose to the internet?** Not directly. See the security note under [Headless server](#headless-server).

## Troubleshooting

| Symptom | What to check |
|---|---|
| SmartScreen blocks the installer | Expected for now: the MSI is not code-signed yet. **More info > Run anyway**. |
| An agent provider is missing from the picker | The CLI must be installed and on the daemon's PATH (`infantry harness-status`), then restart the app. |
| A ticket refuses to start: "no directory configured" / "no base branch configured" | Set the project's repository and base branch (Docker: `AGENTHQ_REPO` and `AGENTHQ_BASE_BRANCH`). A ticket also needs a role. |
| Phone says it cannot connect | Same network or Tailscale? Firewall open for the daemon port? Address ends in `/ws`? A 401/403 means a wrong token. |
| Server refuses to start: "refusing to start" | A non-loopback bind needs a token of at least 32 characters. |
| "Check for updates" says updates are off | Set `AGENTHQ_UPDATE_REPO=owner/repo` (or `repo=` in `update.properties`). |
| Anything else | Open **Configuration > Logs > Open logs folder** and attach `agenthq.log` to a [bug report](../../issues/new/choose). Read it first: logs can hold project paths and agent output. |

More: [QUICKSTART.md troubleshooting](superiorSoftLibrary/agentHq/docs/QUICKSTART.md#6-troubleshooting) and
[INSTALL logs](docs/AGENTHQ-INSTALL.md#logs).

## Roadmap and known issues

What is **not** finished, stated plainly:

- **No web client** yet.
- **No macOS or Linux desktop app** packages yet (the headless server runs there).
- **The Docker image is not published**; build it yourself (above).
- **The Windows installer is not code-signed**, so SmartScreen warns.
- **The Android app is sideloaded** (no Play Store listing) and some views (for example the git view) are incomplete.
- **Single-user access**: one token per daemon, no accounts or per-user permissions.
- **Console windows can flash** on Windows when the daemon starts agent processes; a fix is in progress.
- **arm64** server archives exist for macOS only; Linux and Windows arm64 come later.
- opencode and pi have no slash commands in the app yet (Claude does).
- The installed Windows app bundles a full Java runtime, so the download is larger than it needs to be.

Planned next: web client, published Docker image, signed Windows builds, macOS/Linux desktop packages, multi-user access.
Priorities can change; watch the [Releases](../../releases) page.

## Getting help

- **Questions and ideas**: [GitHub Discussions](../../discussions) <!-- OWNER: enable Discussions in the repo settings -->
- **Bugs**: [open an issue](../../issues/new/choose) with the bug template (version, OS, steps, `agenthq.log`).
- **Security problems**: do **not** open a public issue; follow [SECURITY.md](SECURITY.md).

## Contributing

Bug reports and feature requests are welcome now. Code contributions from outside the team are **on hold until a
license is chosen** (see [License](#license)). How to build, branch, test and commit: [CONTRIBUTING.md](CONTRIBUTING.md).
Everyone taking part follows the [Code of Conduct](CODE_OF_CONDUCT.md).

## Support the project

If Infantry AI saves you time, you can support its development. The **Sponsor** button at the top of this repository
lists the ways to contribute money.

<!-- OWNER TODO: replace the placeholder below once GitHub Sponsors (and any other platform) is set up, and fill in
     .github/FUNDING.yml with the real handles. Do not publish links to accounts that do not exist yet. -->
- GitHub Sponsors: _coming soon_ (`[PLACEHOLDER: github.com/sponsors/<handle>]`)
- Other platforms: _to be announced_ (`[PLACEHOLDER]`)

Starring the repository and filing good bug reports help too.

## License

> **[PLACEHOLDER] License not yet chosen.** Until a license is published in [LICENSE](LICENSE), all rights are reserved
> by SuperiorSoft LLC: you may view the code here, but no permission to copy, modify or redistribute it is granted.

## Building from source

Requires JDK 21 (Gradle toolchain resolution via foojay) and, for the Android module, an Android SDK. Run only one Gradle
build at a time.

    ./gradlew :superiorSoftLibrary:agentHq:compileKotlin :superiorSoftLibrary:agentHqUi:compileKotlin
    ./gradlew :superiorSoftLibrary:agentHq:installDist     # build/install/agenthq/bin/infantry (and agenthq)
    ./gradlew :superiorSoftLibrary:agentHqUi:packageReleaseMsi   # Windows installer
    ./gradlew :superiorSoftLibrary:agentHq:headlessDist    # server archive for this OS

Modules (all under `superiorSoftLibrary/`; the prefix keeps `:superiorSoftLibrary:*` paths stable):

- `agentHq`: daemon + `infantry` CLI (JVM; `agenthq` stays as an alias)
- `agenthqUiCore`: shared UI core (Compose Multiplatform)
- `agentHqUi`: desktop UI
- `agentHqAndroid`: Android shell
- `agentHqHeadless`: test-only guard that the daemon runs with no UI

Secrets guard: `./gradlew verifySecretsNotTracked` fails if keys, keystores or tokens are tracked. Signing secrets belong in
`secrets.local.properties` (gitignored) or environment variables. More in [CONTRIBUTING.md](CONTRIBUTING.md).

## Reference

- Every `AGENTHQ_*` variable is also read as `INFANTRY_*` (`INFANTRY_PORT`, `INFANTRY_TOKEN`, ...). The old `AGENTHQ_*`
  names keep working; when both are set the `AGENTHQ_*` one wins.
- [Install and data layout (Windows)](docs/AGENTHQ-INSTALL.md) · [Releasing](docs/RELEASING.md) ·
  [Owner quickstart (from source)](superiorSoftLibrary/agentHq/docs/QUICKSTART.md) ·
  [Architecture](superiorSoftLibrary/agentHq/docs/ARCHITECTURE.md) · [MCP tools](superiorSoftLibrary/agentHq/docs/MCP.md) ·
  [Agent quick reference](superiorSoftLibrary/agentHq/docs/AGENT-QUICKREF.md)

---

<p align="center">© SuperiorSoft LLC. Infantry AI is a product of SuperiorSoft LLC.</p>
