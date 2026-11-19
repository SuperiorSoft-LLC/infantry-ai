<!-- SPDX-License-Identifier: Apache-2.0 -->
# Infantry_Sites: the host contract

The daemon side of Infantry AI Hosting is a **closed-source native sidecar**. This folder holds only the contract the
open daemon offers it; the implementation is built and shipped from the private repository, and its binary is dropped
in as `bin/infantry-sites` (`.exe` on Windows) beside `module.json`. Without it the module is listed as "not loaded"
and the Sites panel shows its frames as not served.

The sidecar speaks the plugin host's JSON-lines mapping (`infantry-plugin-wit`, `jsonl`), and must serve:

| Surface | Name | Request | Answer |
|---|---|---|---|
| frame | `sites_list` | `{}` | `sites_list` `{state, message?, servers?, limit?, siteUrl?, signUpUrl?, planStatus?, reclaimableUntil?}`; a server is `{id, name, status, sshReady, ip, region, size, createdAt, destroyAt}` |
| frame | `sites_ssh_exec` | `{server?, command, timeoutSec?}` | `sites_ssh_exec` `{server, exitCode?, stdout, stderr, truncated?, timedOut?}` (owner only) |
| frame | `sites_ssh_upload` | `{server?, path, content}` | `sites_ssh_upload` `{server, exitCode?, stdout, stderr}` (owner only) |
| tool | `infantry_sites_list` | `{}` | the server array |
| tool | `infantry_sites_ssh_exec` | `{server?, command, timeoutSec?}` | as the frame |
| tool | `infantry_sites_ssh_upload` | `{server?, path, content}` | as the frame |

Errors are `"<kind>: <message>"`, kind one of `signed_out, not_entitled, limit, not_configured, bad_request, not_found,
unavailable, email_unverified, not_running, host_key_mismatch, auth_failed`. A server still being set up is not an error:
`{status: "setting_up", retryAfterSec, message}`.

Its account sign-in goes through the daemon's account hub: the sidecar's release adds its provider descriptor to this
`module.json` under `account.providers` (the `describe` shape: id, name, icon, signIn {method: device_code, clientId,
scopes, deviceAuthorizationUrl, tokenUrl, revokeUrl}, accountUrl, services, consumers).
