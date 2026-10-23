AgentHQ server (headless daemon, no UI)
=======================================

One daemon; the Windows/Android apps and the `agenthq` CLI connect to it as thin clients.
The archive bundles its own Java runtime (runtime/), so nothing else needs to be installed.

Start
  Linux / macOS:  AGENTHQ_TOKEN=<secret> AGENTHQ_HOST=0.0.0.0 ./bin/agenthq-server.sh
  Windows:        set AGENTHQ_TOKEN=<secret>& set AGENTHQ_HOST=0.0.0.0& bin\agenthq-server.bat

  With no AGENTHQ_HOST the daemon binds to 127.0.0.1 only (local clients). A non-loopback bind
  refuses to start without a token. Generate one with e.g. `openssl rand -hex 32`.

Settings (environment variables)
  AGENTHQ_PORT         port (default 8787; a taken port falls back to a free one unless AGENTHQ_PORT_STRICT=1)
  AGENTHQ_HOST         bind address (default 127.0.0.1)
  AGENTHQ_TOKEN        bearer token, or AGENTHQ_TOKEN_FILE=<file holding it>
  AGENTHQ_STATE_DIR    database, logs and state (default ~/.agenthq)
  AGENTHQ_KEYSTORE_KEY_FILE  master key for the secret keystore on machines without an OS keychain
  AGENTHQ_JAVA_OPTS    extra JVM flags (the default heap cap is -Xmx512m)

Connect
  CLI:      AGENTHQ_URL=ws://<host>:<port>/ws AGENTHQ_TOKEN=<secret> ./bin/agenthq board
  Apps:     add a remote endpoint ws://<host>:<port>/ws (wss:// behind TLS) with the token.
  Health:   GET http://<host>:<port>/health (the one path open without a token).

Harness CLIs (claude, opencode, pi) are NOT bundled: the server finds the ones installed on this machine,
or installs them for you with `./bin/agenthq harness-install claude` (needs Node.js 20+ and npm).
`./bin/agenthq harness-status` shows what it found.

Security: the token grants command execution through agents. Do not expose the raw port to the
internet; put a TLS reverse proxy (it must pass WebSocket upgrades) or a VPN/SSH tunnel in front.
