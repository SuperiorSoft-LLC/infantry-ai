# Infantry end-to-end task (E2E-TASK-1)

The go/no-go test for running all agent work in Infantry: an agent creates a ticket, the ticket is started on an assigned
harness/model, and that session does the work. One row per installed harness.

## Run it

```sh
./gradlew :superiorSoftLibrary:infantry:installDist -x verifyBase
node docs/infantry-e2e-task.mjs                         # every harness
node docs/infantry-e2e-task.mjs --only opencode,claude  # a subset
node docs/infantry-e2e-task.mjs --keep --port 18798     # keep the state dir (transcripts, frames.json, daemon.log)
node docs/infantry-e2e-task.mjs --only claude --mode deny                       # creator in deny (BUG-PERM-5)
node docs/infantry-e2e-task.mjs --only claude --mode deny --work-mode allowEdits # ...and the ticket session may edit
```

Exit code 0 means no FAIL (an `unverified` row, a CLI that is not on PATH, is not a failure).
A row takes 1-3 minutes; `--timeout <ms>` (default 300000) bounds each wait.

## What one row does

1. **Daemon.** A throwaway daemon from `build/install/infantry/lib`: fresh `mkdtemp` dir as `INFANTRY_STATE_DIR` /
   `INFANTRY_HOME`, port 18798 with `INFANTRY_PORT_STRICT=true`, `INFANTRY_TOOL_APPROVAL=<--work-mode, default --mode, default allowAll>`, default provider
   `opencode` + Luna 6, and a throwaway git repo (`<state>/project`, one commit on `main`) as `INFANTRY_REPO`.
   It refuses 8787/8788, never reads `~/.infantry`, and drops every inherited `INFANTRY_*` address/identity variable.
2. **Create + assign.** The script first saves a `developer` profile that pins the DAEMON DEFAULT (opencode + Luna 6).
   An agent session on the row's harness/model is then asked to run
   `infantry ticket create --title "E2E HELLO <harness>-<tag>" --desc "Create file HELLO.md in the project root containing exactly: hello from <harness>/<model>" --role developer --provider <harness> [--model <model>]`.
   The script never saves the ticket itself; it reads it back off the board and FAILs the row unless it carries
   `agentId` `role:developer` and the row's `provider` / `model` (BUG-E2E-1). A session on anything but the pin is a FAIL.
3. *(was: assign through `ticket_save`; the CLI flags replaced it.)*
4. **Deploy.** `ticket_move -> in_progress`, exactly what the UI's board sends. The daemon cuts the ticket's branch and
   worktree and spawns the session; the script polls the board until `lastRun.endedAt`.
5. **Verify.** `HELLO.md` in the session's worktree must equal `hello from <harness>/<model>` (one trailing newline is
   allowed); the session record's provider/model must match the pin; no `[infantry] ... denied tool ...` stderr notice;
   the ticket's state and run outcome are recorded.

**PASS** = created + started on the pin + exact file + no denial. The ticket state is reported, not gated (see below).

Models: Luna 6 (`opencode-go/gpt-6-luna`) for OpenCode, `haiku` for Claude,
`commandcode/deepseek/deepseek-v4-flash` for pi, codex's own `config.toml` default (no model pin), gemini if installed.

## Results

### 2026-10-19 (Windows 11, staging-agenthq eaa83d44 installDist, contract 1.76.0)

| harness | model | created? | started on | HELLO.md | ticket state | time | result |
| --- | --- | --- | --- | --- | --- | --- | --- |
| opencode | opencode-go/gpt-6-luna | yes | opencode/opencode-go/gpt-6-luna | exact | in_progress | 82s | PASS |
| claude | haiku | yes | claude/haiku | exact | in_progress | 18s | PASS |
| pi | commandcode/deepseek/deepseek-v4-flash | yes | pi/commandcode/deepseek/deepseek-v4-flash | exact | in_progress | 43s | PASS |
| codex | (config.toml default) | yes | codex/(default) | exact | in_progress | 40s | PASS |
| gemini | - | - | - | - | - | - | unverified (not installed) |

Every run ended `SUCCEEDED` with no denial notice. Time = from the create prompt to the end of the ticket's run.
On every row the ticket session ran on the ticket's pin, not on the profile's or the daemon's default (opencode).

### Findings (not fixed here)

1. **`infantry ticket create` cannot assign a role, harness or model.** It takes only `--title`, `--desc`,
   `--project` and `--epic`. An agent that files a ticket cannot say who runs it; the script assigns these through
   `ticket_save`, as the UI does.
2. **No ticket reaches done/review by itself.** After a successful run the daemon records `lastRun` (`SUCCEEDED`)
   but leaves the ticket `in_progress` (`TicketBoard.finishTicketRun` writes only the run row). Nothing in the
   ticket preamble tells the agent to run `infantry ticket move <id> done`. There is no review column either:
   the columns are todo, in_progress, pending and done.
