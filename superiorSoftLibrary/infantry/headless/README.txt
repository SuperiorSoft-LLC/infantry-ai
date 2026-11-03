Infantry AI server (headless daemon, no UI)
=======================================

One daemon; the Windows/Android apps and the `infantry` CLI connect to it as thin clients.
The archive bundles its own Java runtime (runtime/), so nothing else needs to be installed.

Start
  Linux / macOS:  INFANTRY_TOKEN=<secret> INFANTRY_HOST=0.0.0.0 ./bin/infantry-server.sh
  Windows:        set INFANTRY_TOKEN=<secret>& set INFANTRY_HOST=0.0.0.0& bin\infantry-server.bat

  With no INFANTRY_HOST the daemon binds to 127.0.0.1 only (local clients). A non-loopback bind
  refuses to start without a token. Generate one with e.g. `openssl rand -hex 32`.

Updates (the supervisor)
  Run the server through the launcher instead of infantry-server.*: ./bin/infantry (Windows: bin\infantry.cmd).
  The first run copies this folder to <parent>/versions/<version>/ and writes <parent>/infantry, the stable entry point;
  start it from there afterwards. Your data dir (INFANTRY_STATE_DIR) is never touched.
    infantry update [--check-only] [--yes]   check the latest release, verify its SHA256SUMS, install it, restart into it
    infantry rollback                        switch back to the previous version (the last two are kept)
  A client's Update button does the same (POST /update/apply). A new version that exits within 30 seconds of the switch
  is rolled back automatically, once.
  Release source: INFANTRY_UPDATE_REPO=owner/repo (GitHub Releases) or INFANTRY_UPDATE_DIR=<folder>. A private repo needs
  INFANTRY_UPDATE_TOKEN (or INFANTRY_UPDATE_TOKEN) in the environment; it is never written to a file.
  Updates are checked only when you ask; nothing installs by itself. Docker images cannot update themselves: pull the new image.

Settings (environment variables)
  INFANTRY_PORT         port (default 8787; a taken port falls back to a free one unless INFANTRY_PORT_STRICT=1)
  INFANTRY_HOST         bind address (default 127.0.0.1)
  INFANTRY_TOKEN        bearer token, or INFANTRY_TOKEN_FILE=<file holding it>
  INFANTRY_STATE_DIR    database, logs and state (default ~/.infantry)
  INFANTRY_KEYSTORE_KEY_FILE  master key for the secret keystore on machines without an OS keychain
  INFANTRY_JAVA_OPTS    extra JVM flags (the default heap cap is -Xmx512m)

Connect
  CLI:      INFANTRY_URL=ws://<host>:<port>/ws INFANTRY_TOKEN=<secret> ./bin/infantry board
  Apps:     add a remote endpoint ws://<host>:<port>/ws (wss:// behind TLS) with the token.
  Health:   GET http://<host>:<port>/health (the one path open without a token).

Harness CLIs (claude, opencode, pi) are NOT bundled: the server finds the ones installed on this machine,
or installs them for you with `./bin/infantry harness-install claude` (needs Node.js 20+ and npm).
`./bin/infantry harness-status` shows what it found.

Security: the token grants command execution through agents. Do not expose the raw port to the
internet; put a TLS reverse proxy (it must pass WebSocket upgrades) or a VPN/SSH tunnel in front.

Starting tickets headlessly
  A ticket start cuts a worktree from a git repo, so the daemon needs two settings:
    INFANTRY_REPO=<path of a git repository on this machine>   (or give each project a directory)
    INFANTRY_BASE_BRANCH=main                                   (the branch new ticket branches are cut from)
  Without them a Start is refused ("no base branch configured ..." / "this project has no directory configured").
  A ticket also needs a role: set one with `infantry ticket update <id> --role <role id>`.
