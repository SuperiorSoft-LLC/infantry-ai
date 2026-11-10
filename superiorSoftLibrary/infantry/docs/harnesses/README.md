# Sample harness descriptors

Drop a file from here into `$INFANTRY_HOME/harnesses/` (default `~/.infantry/harnesses/`) to register that CLI as a
harness with no code (HARNESS-DESC-1). Each one carries its audited `toolApproval` map (BUG-PERM-3), so every Tool
approval mode reaches the CLI.

| File | CLI | deny | allowEdits | allowAll | Verified |
| --- | --- | --- | --- | --- | --- |
| `codex.json` | codex 0.162 `codex exec --json` | its `config.toml` | `--sandbox workspace-write` | `--dangerously-bypass-approvals-and-sandbox` | live, BUG-PERM-4 (see `docs/infantry-tool-approval-smoke.md`) |
| `gemini.json` | gemini `-p` | its settings | `--approval-mode auto_edit` | `--approval-mode yolo` | **unverified**: from gemini's docs, not installed on the audit machine |
| `commandcode.json` | Command Code `command-code -p --output-format json`, prompt on stdin (npm `command-code`; `cmdc` on Windows) | none (headless blocks writes/shell) | `--permission-mode accept-edits` | `--yolo` | **unverified**: from commandcode.ai/docs/core-concepts/headless; declares credential `COMMAND_CODE_API_KEY` |

`ToolApprovalMatrixTest` loads this directory, so a sample that loses a mapping fails the build.

## Installing from the app (HARNESS-DESCRIPTOR-INSTALL-1)

A descriptor may carry `"install": {"npm": "<package>", "bin"?: "<path in package>"}`, validated like a manifest's `harness.cli.install`. The harness installer then lists and installs it into the app's own prefix (no key stored). An install ships these samples in `<install>/harness-samples` (override: `INFANTRY_HARNESS_SAMPLES`); a sample with an `install` field shows as "available, not installed", and installing it copies the file into `harnesses/` and registers it. Command Code uses the binary name `command-code` because `cmd` clashes with cmd.exe on Windows; its usage field names are undocumented, so it has no USAGE mapping.
