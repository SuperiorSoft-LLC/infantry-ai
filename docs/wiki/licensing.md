<!-- SPDX-License-Identifier: Apache-2.0 -->
# Licensing

<!-- shared:license_line -->BattalionOS, the core and shell (the daemon, protocol, runner and desktop shell), is the foundation: it is licensed MPL-2.0 to protect its integrity, so apps and plugins can rely on a stable base that does not break, with backward compatibility as the promise. Everything built on top (the SDKs, the UI core, the apps and every plugin) is free under Apache-2.0: build, fork, sell and extend it without copyleft.<!-- /shared:license_line -->

Not legal advice. The full texts are in `LICENSES/MPL-2.0.txt` and `LICENSES/Apache-2.0.txt`; each crate states its
license in the `license` field of its `Cargo.toml`, and each source file carries an SPDX header
(`scripts/license-headers.sh`, rules in `scripts/license-paths.txt`).

## What the two licenses mean

- **MPL-2.0** is file-level copyleft. If you change an MPL file and ship it, that file stays MPL and open. You may link
  MPL code into a larger work under any license.
- **Apache-2.0** is permissive, with a patent grant. Build on it, fork it, sell it, no copyleft.

## The line, crate by crate

| License | What | Where |
|---|---|---|
| MPL-2.0 (BattalionOS core) | Wire, platform core, runner, mail, scripting, vault, usage, projects, knowledge, plugin host, the daemon and CLI | `infantry-protocol`, `infantry-core`, `infantry-proc`, `infantry-runner`, `infantry-runner-local`, `infantry-runner-link`, `infantry-mail`, `infantry-scripting`, `infantry-vault`, `infantry-usage`, `infantry-projects`, `infantry-knowledge`, `infantry-plugin-host`, `infantry-daemon`, `infantry-cli`, `infantry-migrate` |
| Apache-2.0 | What plugin and app authors build against, and every feature on top | `infantry-plugin-wit`, `infantry-plugin-sdk`, `infantry-runner-api`, `infantry-runner-pool`, `infantry-runner-source`, `infantry-harness` and the four harness crates, `infantry-orchestrator-api`, `infantry-board`, `infantry-distribution`, `infantry-guards`, `conformance`, every official plugin, the React app and the Tauri host (`app/`), `docs/`, `scripts/`, `docker/`, `.github/` |
| Neither (trademark) | The name Infantry AI, the logo and the emblem | `assets/branding/` |

The rule behind the line: the core never contains plugin code, and the SDK a plugin compiles against (`infantry-plugin-wit`,
`infantry-plugin-sdk`) imports nothing MPL-only, so a plugin author is never pulled onto MPL terms.

## For a plugin author

Your plugin is yours: license it as you like. It compiles against the Apache-2.0 SDK and talks to the MPL core only
through the plugin ABI, so no copyleft reaches it. See [Plugins](plugins/overview.md).

## Trademark and contributions

- The name and logo are not licensed by either license; forks rename. The draft policy is `docs/legal/TRADEMARK.md`.
- Contributions need a CLA: `docs/legal/CLA.md` (individual and corporate).
