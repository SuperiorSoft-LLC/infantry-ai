# Infantry AI privacy policy

Last updated: 2026-11-15. Publisher: SuperiorSoft LLC.

Infantry AI runs on your computer. Your tickets, notes, mail, agent transcripts and settings are stored on your
machine (`%LOCALAPPDATA%\SuperiorSoft\InfantryAI`, or the package's private folder in the Microsoft Store build). SuperiorSoft
LLC does not receive them and runs no server that collects them.

## What leaves your machine, and only when you use it
- **Agent CLIs and model providers.** When you start an agent, the harness you picked (for example Claude Code or
  opencode) sends your prompts and the files it reads to that provider, under that provider's terms and privacy policy.
  Infantry AI does not read or relay that traffic.
- **Installing harnesses.** Installing a harness downloads its package (npm) from the public registry.
- **Update checks.** A non-Store install asks GitHub Releases for the newest version. No account data is sent. The
  Microsoft Store build does not self-update; the Store does.
- **Optional accounts and runners.** If you sign in, pair a remote runner or a phone, only what that feature needs is
  sent to the endpoint you chose.

## What Infantry AI does not do
No advertising, no analytics or telemetry sent to SuperiorSoft LLC, and no sale of data.

## Local network
The app's daemon listens on `127.0.0.1` (loopback) only, for its own window and your paired clients.

## Your control
Delete the data folder above to remove everything Infantry AI stored. Uninstalling keeps it unless you delete it.

## Contact
Questions: open an issue at https://github.com/SuperiorSoft-LLC/infantry-ai/issues
