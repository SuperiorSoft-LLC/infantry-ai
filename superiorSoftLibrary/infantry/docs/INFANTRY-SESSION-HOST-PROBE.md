# Infantry session host: harness probe (BUG-M2-11a)

Run on 2026-10-19 on the owner's machine: Windows 11 Pro 10.0.26200, node v24.12.0. The worktree was
`bug-m2-11a-session-lifecycle-design` at 1897cca4, base fbcd4c52. The results were posted first as internal
comments PROBE 1/2 and PROBE 2/2 on ticket 662ee553. This file is the durable copy, with the commands to
re-run it. The design these results feed is [INFANTRY-SESSION-HOST-PLAN.md](INFANTRY-SESSION-HOST-PLAN.md).

Every harness was asked the same five questions:

1. Does ONE process serve several turns (same pid, two prompts or more)?
2. Which record marks the end of a turn?
3. Can a turn be aborted without killing the process, and does the next prompt still work?
4. What does closing stdin do: is there a graceful stop?
5. Does a NEW process resume the conversation from the provider session id?

The drivers are `session-host-probe/stdio-probe.mjs` (for any CLI that takes turns on an open stdin) and
`session-host-probe/oc-probe.mjs` (for opencode's HTTP server). Timestamps below are milliseconds since
spawn, as the drivers print them. Long lines are cut, and `…` marks a cut.

## Summary

| | claude | pi (and omp) | opencode | descriptor, stub |
| --- | --- | --- | --- | --- |
| Version | 2.1.303 | 1.3.0 | 1.18.35 | n/a |
| Persistent mode | `-p --input-format stream-json --output-format stream-json` | `--mode rpc` | `serve` (HTTP + SSE) | **none** |
| One process, several turns | **YES**: pid 61068, 4 turns | **YES**: pid 49576, 4 turns + `get_state` | **YES**: pid 62744, 4 turns | n/a |
| End of turn | the `result` record | `agent_settled` (not `agent_end`) | `session.idle`, or `session.status` going idle after busy | the process exits |
| First turn (includes boot) | `system/init` at +3.1 s, reply at +4.6 s | prompt ack at +8.5 s, settled at +10.6 s | listening at +1.6 s; first reply took 28.2 s | n/a |
| Later turns | 1.2 s | 1.2 s (ack in 8 ms) | 1.9 s / 9.0 s | n/a |
| Abort | `control_request` interrupt: `control_response` in 12 ms, then `result/error_during_execution`; the process lives on | `{"type":"abort"}`: `agent_settled {"aborted":true}`, then `response/abort` | `POST /session/{id}/abort` answers `true`, then `MessageAbortedError`, then idle | kill |
| Prompt after abort | works ("FOUR") | works ("SIX") | works ("FOUR") | n/a |
| Close stdin | exits 0 after 0.65 s | exits 0 after 26 ms | **ignored**: still alive 4 s later; a tree kill gives exit 1 | n/a |
| Resume in a new process | `--resume <id>`: pid 62368, same session id, replied "ONE, TWO, FOUR", boot 1.4 s | `--session <id>`: pid 22928, same session id, replied "ONE,TWO,FOUR", boot 4.3 s | not probed on the real binary (see "Not measured") | n/a |

**Verdict.** The three harnesses Infantry runs today all have a real persistent mode, and every one of them
keeps one process across turns, aborts a turn without dying, and resumes in a fresh process by session id.
Descriptor harnesses and the stub have no persistent mode, and none was faked: they get the one-shot
adapter behind the same host.

## claude 2.1.303

Binary: `%APPDATA%\npm\node_modules\@anthropic-ai\claude-code\bin\claude.exe`. Config:
`session-host-probe/claude.json`.

```
claude.exe -p --input-format stream-json --output-format stream-json --verbose --model haiku --strict-mcp-config
```

- **Turn:** one JSON line on stdin:
  `{"type":"user","message":{"role":"user","content":[{"type":"text","text":"Reply with exactly: ONE"}]}}`
- **Interrupt:** `{"type":"control_request","request_id":"int-1","request":{"subtype":"interrupt"}}`

Observed output (`claude.out`, abridged; the session id is `b95e9d00-e780-4161-a19a-1b36f8862c9c`):

```
+    43ms [claude] spawned pid=61068 argv=["-p","--input-format","stream-json","--output-format","stream-json","--verbose","--model","haiku","--strict-mcp-config"]
+  3086ms OUT system/init sid=b95e9d00-… model=claude-haiku-5-5 cwd=…\cwd-claude tools=27
+  4555ms OUT assistant sid=b95e9d00-… content=["ONE"]
+  4636ms OUT result/success sid=b95e9d00-… result="ONE" is_error=false num_turns=1
+  4637ms --- STEP turn2: write {"type":"user",…"Reply with exactly: TWO"…}
+  4688ms OUT system/init sid=b95e9d00-… model=claude-haiku-5-5 … tools=27      <- init again, on EVERY turn
+  5778ms OUT assistant sid=b95e9d00-… content=["TWO"]
+  5799ms OUT result/success sid=b95e9d00-… result="TWO" is_error=false num_turns=1
+  5799ms --- STEP turn3-interrupt: write {…"Write the numbers 1 to 400, one per line, nothing else."…}
+  9300ms --- mid-turn write: {"type":"control_request","request_id":"int-1","request":{"subtype":"interrupt"}}
+  9312ms OUT control_response
+  9327ms OUT result/error_during_execution sid=b95e9d00-… result=undefined is_error=true num_turns=2
+  9327ms --- STEP turn3-interrupt END-OF-TURN SEEN; lines=8 bootLines=1; process alive=true pid=61068
+ 10564ms OUT assistant sid=b95e9d00-… content=["FOUR"]
+ 10594ms OUT result/success sid=b95e9d00-… result="FOUR" is_error=false num_turns=1
+ 10594ms --- closing stdin
+ 11241ms EXIT code=0 signal=null
```

Restart and resume (`claude-resume.json`, the same argv plus `--resume b95e9d00-e780-4161-a19a-1b36f8862c9c`):

```
+    19ms [claude-restart-resume] spawned pid=62368 argv=[…,"--resume","b95e9d00-e780-4161-a19a-1b36f8862c9c"]
+  1354ms OUT system/init sid=b95e9d00-e780-4161-a19a-1b36f8862c9c model=claude-haiku-5-5 … tools=27
+  2288ms OUT assistant sid=b95e9d00-… content=["ONE, TWO, FOUR"]
+  2320ms OUT result/success sid=b95e9d00-… result="ONE, TWO, FOUR" is_error=false num_turns=1
+  2320ms --- closing stdin
+  2923ms EXIT code=0 signal=null
```

What the adapter needs from this:

- **A turn ends on the `result` record.** Its `subtype` is `success`, or `error_during_execution` after an
  interrupt, and `is_error` tells them apart.
- **`system/init` is re-emitted at the start of every turn,** with the same session id. It is NOT a boot,
  and it must never produce a chat line per turn.
- **An interrupt answers in 12 ms** with `control_response`, followed by a `result` with subtype
  `error_during_execution`. A `result` that follows the adapter's own interrupt is INTERRUPTED, not FAILED.
- **Closing stdin is a clean stop** (exit 0 after 0.65 s).
- **The init record** carries `mcp_servers: []` (with `--strict-mcp-config`), `permissionMode: "default"`
  and `claude_code_version: "2.1.303"`. The `result` record also carries `queued_turn_count`, which the
  adapter does not need: Infantry never queues a second turn on a session.

## pi 1.3.0 (and omp)

Launcher: `node %APPDATA%\npm\node_modules\@earendil-works\pi-coding-agent\dist\cli.js`. Configs:
`session-host-probe/pi.json` and `pi-resume.json`.

```
node <pi>/dist/cli.js --mode rpc --model opencode-go/cat-jam-free
```

- **Turn:** `{"id":"p1","type":"prompt","message":"Reply with exactly: ONE"}`
- **Abort:** `{"id":"a1","type":"abort"}`
- **State:** `{"id":"s1","type":"get_state"}`

Observed output (`pi.out`, abridged):

```
+    36ms [pi] spawned pid=49576 argv=[…,"--mode","rpc","--model","opencode-go/cat-jam-free"]
+  8551ms OUT response/prompt success=true data={"disposition":"started"}
+  8559ms OUT extension_ui_request                       <- setStatus "DCP: idle", on every turn
+ 10630ms OUT message_update/text_delta delta="ONE"
+ 10636ms OUT agent_end {"type":"agent_end","messages":[…
+ 10638ms OUT agent_settled {"type":"agent_settled","aborted":false}
+ 10646ms OUT response/prompt success=true data={"disposition":"started"}   <- turn 2 acknowledged 8 ms after the write
+ 11836ms OUT message_update/text_delta delta="TWO"
+ 11842ms OUT agent_settled {"type":"agent_settled","aborted":false}
           (turn 3: the model ran a tool and printed 1..400 within 3.3 s, before the abort sent at 3.5 s;
            so that abort landed on turn 4)
+ 15348ms --- mid-turn write: {"id":"a1","type":"abort"}
+ 15357ms OUT agent_settled {"type":"agent_settled","aborted":true}
+ 15357ms OUT response/abort success=true data=null      <- arrives AFTER agent_settled
+ 15359ms OUT response/get_state sid=01a154bc-bd76-705d-8b8b-795333b644ad success=true data={"model":{"id":"cat-jam-free",…
+ 15359ms --- closing stdin
+ 15385ms EXIT code=0 signal=null
```

Restart and resume, with an abort followed by a prompt (`pi-resume.json`, the same argv plus
`--session 01a154bc-bd76-705d-8b8b-795333b644ad`):

```
+    15ms [pi-restart-resume] spawned pid=22928
+  4344ms OUT extension_ui_request
+  4353ms OUT response/prompt success=true data={"disposition":"started"}
+  5902ms OUT message_update/text_delta delta="ONE,TWO,FOUR"
+  5909ms OUT agent_settled {"type":"agent_settled","aborted":false}
+  7410ms --- mid-turn write: {"id":"a2","type":"abort"}            (1.5 s into a 1500-word essay)
+  7417ms OUT agent_settled {"type":"agent_settled","aborted":true}
+  7418ms OUT response/abort success=true data=null
+  7833ms OUT message_update/text_delta delta="SIX"
+  7836ms OUT agent_settled {"type":"agent_settled","aborted":false}
+  7838ms OUT response/get_state sid=01a154bc-bd76-705d-8b8b-795333b644ad success=true …
+  7838ms --- closing stdin
+  7861ms EXIT code=0 signal=null
```

What the adapter needs from this:

- **A turn ends on `agent_settled`, not on `agent_end`.** pi's own `docs/rpc.md` says: "`agent_end` marks
  the end of one low-level agent run, but retries, overflow recovery, compaction, steering, or follow-up work
  can still follow. Wait for `agent_settled`…". `agent_settled` also carries `aborted`.
- **A prompt is acknowledged by `response/prompt`.** `success:false` means the prompt was refused (FAILED).
  `data.disposition: "handled"` means no run started, so there is no `agent_settled` to wait for (rpc.md
  line 67), and the turn is COMPLETED at once.
- **`response/abort` can arrive after `agent_settled`.** The adapter must not wait for it to end the turn.
- **RPC mode prints no session header.** The session id comes from `get_state` (`sessionId`, with
  `sessionFile` under `~/.pi/agent/sessions/`), and `--session <id>` resumes it.
- **Every turn emits `extension_ui_request`** with method `setStatus` (`statusKey: "dcp"`, "DCP: idle").
  It is fire-and-forget. Dialog methods (`select` / `confirm` / `input` / `editor`) DO need a reply
  (`docs/rpc-extension-ui.md`), and the daemon answers them with
  `{"type":"extension_ui_response","id":"<id>","cancelled":true}`.
- **Closing stdin is a clean stop** (exit 0 after 26 ms).
- **omp:** on this machine `omp` is a shim that runs pi, and oh-my-pi 0.2.0 is a skills pack, not a separate
  protocol. There is no separate omp protocol to probe, and OMP is on the owner's backburner list.

## opencode 1.18.35

Binary: `%APPDATA%\npm\node_modules\opencode-ai\node_modules\opencode-windows-x64\bin\opencode.exe`. Driver:
`session-host-probe/oc-probe.mjs`.

```
opencode.exe serve --port <random 40000-59999> --hostname 127.0.0.1      (env OPENCODE_SERVER_PASSWORD=<random UUID>)
```

The probe uses Basic auth `opencode:<password>`, SSE on `GET /event`, `POST /session`, then per turn
`POST /session/{id}/prompt_async` with `{"parts":[{"type":"text","text":…}],"model":{"providerID":"opencode","modelID":…}}`,
and `POST /session/{id}/abort`.

Run 1 (`oc.out`) used the daemon's own default model, `opencode/space-bunny-free`:

```
+  1562ms OUT opencode server listening on http://127.0.0.1:53350
+  3658ms session ses_eab41847bffeTWhD9hQHXB8OGH
+ 12652ms EVENT session.error {"name":"UnknownError","data":{"message":"Model not found: opencode/space-bunny-free. Did you mean: beam-free, deepseek-v4.1-flash-free, glm-5.3-flash-free?"}}
+ 12652ms EVENT session.error {"name":"UnknownError","data":{"message":"ProviderModelNotFoundError: Model not found: opencode/space-bunny-free. …
```

> **Side finding (not in scope; the PM filed it as BUG-DEFMODEL).** `OpenCodeCommand.DEFAULT_MODEL`
> (`OpenCodeCommand.kt:19`, `"opencode/space-bunny-free"`) is no longer in the catalog: `opencode models`
> printed 84 lines, none of them space-bunny. An opencode session with no model and no
> `INFANTRY_OPENCODE_MODEL` therefore fails every turn today, whatever the session host does.

Run 2 (`oc2.out`) was a transient boot failure. The very next attempt booted fine; the cause was not
investigated.

```
+    14ms spawned pid=38952 argv=["serve","--port","52679","--hostname","127.0.0.1"]
+  1335ms ERR Error: Unexpected error
+  1335ms ERR ServeError
+  1369ms EXIT code=1 signal=null
```

Run 3 (`oc3.out`) used `deepseek-v4.1-flash-free`:

```
+    14ms spawned pid=62744 argv=["serve","--port","48755","--hostname","127.0.0.1"]
+  1565ms OUT opencode server listening on http://127.0.0.1:48755
+  3730ms session ses_eab40d4dfffegx2YqEWc1DGnjn
+ 31936ms --- TURN turn1 IDLE SEEN after 28205ms; reply="ONE"; server pid=62744 alive=true
+ 33884ms --- TURN turn2 IDLE SEEN after 1948ms; reply="TWO"; server pid=62744 alive=true
+ 36412ms --- POST /session/ses_eab40d4dfffegx2YqEWc1DGnjn/abort
+ 36426ms abort answered true
+ 36427ms EVENT session.error {"name":"MessageAbortedError","data":{"message":"Aborted"}}
+ 36428ms --- TURN turn3-abort IDLE SEEN after 2544ms; reply=""; server pid=62744 alive=true
+ 45461ms --- TURN turn4-after-abort IDLE SEEN after 9033ms; reply="FOUR"; server pid=62744 alive=true
+ 45461ms --- closing stdin, waiting 4s
+ 49466ms after stdin close: alive=true
+ 49795ms tree-killed with taskkill /T /F
+ 49935ms EXIT code=1 signal=null
```

What the adapter needs from this:

- **One server process serves every turn.** M2-11's approach holds on the real binary.
- **The first turn is slow** (28.2 s on a free model, cold). Later turns took 1.9 s and 9.0 s.
- **An abort answers `true`,** the session reports `MessageAbortedError` and then goes idle. An idle that
  follows the adapter's own abort is INTERRUPTED.
- **serve ignores stdin EOF,** so there is no graceful stop over stdin. The host's close is a tree kill, and
  the exit 1 that follows is the host's own doing, not a crash.
- **A boot can fail transiently** (run 2). A failed launch must fail that turn with the stderr tail and leave
  the next prompt free to launch again. It must never loop.
- **Each failed turn reports `session.error` twice** (`UnknownError`, then
  `ProviderModelNotFoundError`), so the adapter must not count errors.

## Harnesses with no persistent mode

- **Descriptor harnesses** (`~/.infantry/harnesses/*`): none are installed here, and `~/.infantry/harnesses`
  does not exist. A descriptor is a one-shot argv template by construction, so there is nothing to probe,
  and no persistent mode is faked. They get the one-shot adapter.
- **Stub provider** (`StubProvider` / `ScriptedRunner`): it has no OS process at all, and gets the one-shot
  adapter.
- **Not probed:** codex (`codex.ps1`) and aider (`~/.local/bin/aider.exe`) are installed, but neither is an
  Infantry harness: no provider, no plugin, no descriptor.

## Not measured

- **Windows console windows.** The probe spawned through Node with `windowsHide: true`, which says nothing
  about how the daemon's Java `ProcessBuilder` spawns. Measuring it needs the real daemon, so it is a manual
  check in BUG-M2-11c (see the plan, section 9).
- **opencode resume in a NEW serve process,** i.e. the restart-once path on the real binary. Today only
  M2-11's fake-backed `OpenCodeServeSessionTest` covers it.
- **claude's `set_model` control request** (changing the model on a live process). Because it was not
  probed, the design restarts and resumes the process when the model changes.

## Re-running it

These runs spend model tokens: claude haiku for 4 + 1 turns, while pi and opencode use free models. Use an
empty scratch directory per harness, never the repository.

```powershell
$env:PROBE_CWD  = "<an empty scratch directory>"
$env:PROBE_RAW  = ""                          # optional: a file path to append every raw stdout line to
$env:CLAUDE_EXE = "$env:APPDATA\npm\node_modules\@anthropic-ai\claude-code\bin\claude.exe"
node docs/session-host-probe/stdio-probe.mjs docs/session-host-probe/claude.json
$env:CLAUDE_SESSION = "<the sid the first run printed>"
node docs/session-host-probe/stdio-probe.mjs docs/session-host-probe/claude-resume.json

$env:PI_CLI = "$env:APPDATA\npm\node_modules\@earendil-works\pi-coding-agent\dist\cli.js"
node docs/session-host-probe/stdio-probe.mjs docs/session-host-probe/pi.json
$env:PI_SESSION = "<the sid get_state printed>"
node docs/session-host-probe/stdio-probe.mjs docs/session-host-probe/pi-resume.json

$env:OPENCODE_EXE = "$env:APPDATA\npm\node_modules\opencode-ai\node_modules\opencode-windows-x64\bin\opencode.exe"
node docs/session-host-probe/oc-probe.mjs "<an empty scratch directory>" deepseek-v4.1-flash-free
```

The paths are relative to `superiorSoftLibrary/infantry`.
