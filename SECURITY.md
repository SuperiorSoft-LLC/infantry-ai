# Security Policy

Infantry AI runs coding agents that can execute commands on your machine, so we take security reports seriously.

## Reporting a vulnerability

**Do not open a public issue, discussion or pull request for a security problem.**

Report it privately, in one of these ways:

1. **GitHub private vulnerability reporting** (preferred): on this repository, open the **Security** tab and choose
   **Report a vulnerability**. <!-- OWNER TODO: enable it under Settings > Code security > Private vulnerability reporting. -->
2. **Email**: **[PLACEHOLDER: security contact email, to be provided by SuperiorSoft LLC]**.

Please include:

- the affected version (Configuration > About, or `infantry version`) and client (Windows, Android, server, Docker, CLI);
- what an attacker can do, and what access they need first (local user, same network, holder of the token, none);
- steps to reproduce, or a proof of concept;
- any logs, with tokens, keys and private paths removed.

## What to expect

- We acknowledge your report within **5 business days**.
- We keep you informed while we investigate and fix it, and agree a disclosure date with you.
- We credit you in the release notes when the fix ships, unless you prefer to stay anonymous.

Please give us reasonable time to release a fix before you disclose the problem publicly, and do not access, change or
delete data that is not yours while testing.

## Supported versions

Infantry AI is in early preview. Only the **latest release** receives security fixes; update before you report if you
can. The in-app updater and `infantry update` install it.

## Scope

In scope: the daemon, the `infantry` / `infantry` CLI, the Windows and Android apps, the headless server archives and the
Docker image built from this repository, and the update and pairing mechanisms.

Out of scope:

- The agent CLIs Infantry AI starts (Claude Code, opencode, pi, ...) and the model providers behind them: report those to
  their own maintainers.
- What an agent does with the permissions you gave it. The daemon token grants command execution through agents by
  design; anyone holding it has full access.
- A daemon exposed directly to the internet without TLS or a VPN, against the documented setup.

## Hardening reminders

- Keep the daemon on loopback, or behind a TLS reverse proxy or a VPN such as Tailscale. Never expose the raw port.
- Use a long random token (at least 32 characters) and keep it out of images, repositories and screenshots.
- A pairing QR code or `infantry://connect` link contains the token: treat it like a password.
- In Docker, mount the keystore key from outside the data volume (`INFANTRY_KEYSTORE_KEY_FILE`) and back it up separately.
