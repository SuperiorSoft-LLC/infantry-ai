# Reaching Infantry from your phone over Tailscale

1. On the PC, run `infantry remote on`. It writes `remote=true` to `<state dir>/network.properties`
   (default `~/.infantry`), generates a token once (`remote.token`, reused afterwards) and prints the
   `ws://<tailscale-ip>:<port>/ws` address and the token.
2. Restart the app (the command does not restart the daemon). The daemon now binds `0.0.0.0`
   (set `host=` in `network.properties` to pin one address) and requires the token on every route except `/health`.
   The desktop app and the `infantry` CLI on the same machine pick the stored token up automatically.
3. Open the firewall for the daemon port (8788 for the installed app) from Tailscale only:
   `New-NetFirewallRule -DisplayName Infantry -Direction Inbound -Protocol TCP -LocalPort 8788 -RemoteAddress 100.64.0.0/10 -Action Allow`
4. On the phone, enter the ws address and the token.

`infantry remote status` re-prints both; `infantry remote off` returns to loopback only (restart to apply).

Precedence: `INFANTRY_HOST`, `INFANTRY_PORT`, `INFANTRY_TOKEN` / `INFANTRY_TOKEN_FILE` in the environment win over the file.
Traffic is cleartext `ws://`; Tailscale encrypts the link, so keep the firewall rule scoped to `100.64.0.0/10`.
