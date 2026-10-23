# Sample harness descriptors

Drop a file from here into `$AGENTHQ_HOME/harnesses/` (default `~/.agenthq/harnesses/`) to register that CLI as a
harness with no code (HARNESS-DESC-1). Each one carries its audited `toolApproval` map (BUG-PERM-3), so every Tool
approval mode reaches the CLI.

| File | CLI | deny | allowEdits | allowAll | Verified |
| --- | --- | --- | --- | --- | --- |
| `codex.json` | codex 0.162 `codex exec --json` | its `config.toml` | `--sandbox workspace-write` | `--dangerously-bypass-approvals-and-sandbox` | live, BUG-PERM-4 (see `docs/agenthq-tool-approval-smoke.md`) |
| `gemini.json` | gemini `-p` | its settings | `--approval-mode auto_edit` | `--approval-mode yolo` | **unverified**: from gemini's docs, not installed on the audit machine |

`ToolApprovalMatrixTest` loads this directory, so a sample that loses a mapping fails the build.
