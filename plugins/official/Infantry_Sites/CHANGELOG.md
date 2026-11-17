# Infantry_Sites changelog

## Unreleased (SITES-SSH-1)

- Agents SSH into the account's servers with no setup: `infantry_sites_ssh_exec {server?, command, timeoutSec?}` and
  `infantry_sites_ssh_upload {server?, path, content}`. `server` is a name or id; optional when the account has one server.
- The PC's Ed25519 key is made in the JVM on first use and kept only in this plugin's keystore field `ssh_device_ed25519`.
  Each connect uses a short-lived user certificate from `POST .../servers/{id}/ssh/certificate`, cached in memory until
  5 minutes before it expires.
- Host keys: the fingerprint the hosting service reports wins; without one, the first key is pinned (plugin storage table
  `infantry_sites_ssh_host_pins`) and a change is refused.
- The SSH client (Apache MINA SSHD 2.14, eddsa) is shaded into the daemon jar; no OpenSSH install is needed (Windows too).
- Bounds are `daemon.config` keys: `ssh.connectTimeoutSec` (15), `ssh.execTimeoutSec` (120), `ssh.execTimeoutMaxSec`
  (1800), `ssh.maxOutputBytes` (65536), `ssh.maxUploadBytes` (1048576).

## Unreleased (ENDPOINT-ENV-1)

- The hosting service address is no longer a user setting. `sites.apiUrl` and `sites.siteUrl` are gone from Settings; the
  addresses live in module.json `daemon.endpoints` (`api`, `site`) and the build environment picks one (dev / staging / prod).
  A value stored under the old keys is ignored.
- Developers override an address for local testing with `INFANTRY_ENDPOINT_INFANTRY_SITES_API` / `..._SITE`.
- The prod addresses are the live host: API `https://superiorsoftllc.tech/api/superiorsoft` (Caddy route on the site host; no `api.` subdomain exists), site `https://superiorsoftllc.tech`.
- Needs daemon API 1.63.0.
