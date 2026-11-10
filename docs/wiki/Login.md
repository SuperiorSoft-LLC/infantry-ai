# CLI login

`infantry login` saves the daemon connection the CLI uses by default. It is **not** Claude's `/login`: that signs the
Claude harness in to an Anthropic account; this one tells the `infantry` CLI which Infantry AI daemon to talk to.

- `infantry login` asks for the server URL (`host:port`, `ws://`, `wss://` or `http(s)://`; Tailscale addresses work) and
  the access token (typed hidden), checks the connection, saves it and prints the server.
- `infantry login --url ws://host:8787/ws --token <t>` (or `--token-file <f>`, or `INFANTRY_TOKEN`) skips the prompts.
- `infantry whoami` shows the saved server; `infantry logout` removes it.
- Saved in the state dir: `login.json` (URL, token file path) and `login.token` (the token, never printed or put in
  `login.json`). Precedence: `--url`/`--endpoint`/env first, then the saved login, then the installed/default daemon.
- A failed check (unreachable or token refused) saves nothing.

Code: `superiorSoftLibrary/infantry/src/main/kotlin/com/superiorsoftllc/infantry/cli/LoginCli.kt`.
