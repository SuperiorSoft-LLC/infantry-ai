# RUNNER-UNIFY-1: local runner vs remote runner (architect review, 2026-11-12)

Design record (dated). Ticket ee3638c7. Read at dev head 15c16c51. Paths are relative to
`superiorSoftLibrary/infantry/src/main/kotlin/com/superiorsoftllc/infantry/` unless they start with `plugins/` or `docker/`.

## Verdict

**Same core, with an extra layer bolted on.**

- **The runner itself is the same thing.** The container runs the same daemon binary in runner mode (`docker/entrypoint.sh:104`
  `exec java ... DaemonMainKt`, `INFANTRY_MODE=runner`, `DeploymentMode.kt:19-28`). A remote start lands in
  `runners/RunnerLinkServer.kt:472`, then goes through the same `LocalRunnerLink` and `LocalRunner.services` (`daemon/LocalRunner.kt:57`)
  to the same `SessionManager`, the same harnesses and the same stream sequencer. Nothing on the runner side was re-implemented.
- **The orchestrator side forked.** The `RunnerLink` interface (`infantryDaemonApi/.../orchestrator/OrchestratorLink.kt:127`) was
  meant to be the one seam. In practice the local path uses it only for start and mail delivery. Every other command, and the
  whole output path, calls `SessionManager` directly in `DaemonMain.kt`. Remote sessions had to get a shadow stack (a mirror, a
  polling pump, a prompt relay, an event outbox), plus `if remote` branches in the callers. About 15 of the ~45 runner tickets
  were remote-only patches that rebuilt, over the link, something local sessions get for free.

So the duplication is not in the engine. It is in how the orchestrator **reads** sessions: one way for local (in-process push),
another for remote (request/response polling).

## 1. Call paths

| Operation | Local session (on this daemon) | Remote session (`infantry-runner`) |
|---|---|---|
| Start | `LocalRunner.start` -> `pool.link(LOCAL).startSession` -> `LocalRunnerLink` -> `manager.startNew` / `bindSessionOnSpawn` (`LocalRunner.kt:231-237`, `57-85`). Board: `LocalBoardHost` direct | `LocalRunner.startRemote` (`:247`) / `LocalBoardHost.kt:131-158` -> `RemoteRunnerLink.startSession` (`plugins/.../RemoteRunnerLink.kt:45`) -> wss `runner_link_start` -> `RunnerLinkServer.start` (`:423`, clone via `RemoteStarts`) -> the runner's own `LocalRunnerLink` -> `manager.startNew`. Then `RunnerHub.placed` + `RemoteSessionMirror.placed` |
| Send a prompt (app) | `manager.resume` (`DaemonMain.kt:3602`, `3707`, `4238`) | `if manager.get(id)==null && runnerOf(id)!=LOCAL` -> `LocalRunner.deliverVia` -> `deliverMail` (`DaemonMain.kt:3590-3595`). The `resume` frame (`:4238`), interrupt (`:4278`), queue add/remove/force and attach have **no** remote branch: they fail on a remote session |
| Mail turn | `MailRouter` -> `deliverVia(pool)` -> `LocalRunnerLink.deliver` -> `manager.resume` (`LocalRunner.kt:296`). Shared | same function, over the link. **Shared** |
| Stream output to chat | `SessionManager` event -> `broadcast` -> `streamSequencer.publish` (push, in process; `DaemonMain.kt:1680`, `2602-2740`, `runActivity` fed per line `:2650`) | The runner's own sequencer holds it. The orchestrator **polls**: `RemoteTranscriptPump.pull` (`runners/RemoteTranscriptPump.kt:49`) from three triggers: the output ticker every `limits.runners.outputPollMs` while BUSY (`DaemonMain.kt:2238-2245`), each runner event (`:2195`), each replay (`:1916`, `:3532`). Then it re-publishes into its own sequencer |
| Replay on select | `streamSequencer.replayAfter` | `pull` first, then the same replay (`DaemonMain.kt:3527-3536`, `:1914-1917`) |
| Approval card | the harness prompt -> the local prompt store -> card | the runner's prompt -> an outbox event -> `RunnerWatch` poll -> `RunnerPromptRelay.apply` (`DaemonMain.kt:2165`) -> card; answer -> `answerPrompt` over the link, with `retry()` on the heartbeat ticker (`:2229`) |
| Session state / list | `manager.list()` | `RemoteSessionMirror.list()` built from outbox STATUS events (`DaemonMain.kt:2166`, `2187-2190`); every list site does `manager.list() + remoteMirror.list()` |
| Stop / kill | `manager.kill` (`DaemonMain.kt:4308`) | `if remote` -> `link.stopSession`, then a synthetic EXITED (`:4296-4305`) |
| Delete | `manager.delete` (`:4473`) | `if remote` -> stopSession + `RunnerHub.forget` + mirror forget + pump forget (`:4474-4481`) |
| Survive a daemon restart | `sessions.json` / db + `chat-history` | orchestrator: `runner-sessions.json` (the mirror's `Kept`, `RemoteSessionMirror.kt:32-37`) + taken sequence; runner: its own db + `runner-outbox.json` (`DaemonMain.kt:1057`) |
| Turn end -> board | `SessionManager` exit -> board | an outbox FINISHED/IDLE event -> fetch the runner's pushed branch -> `coreHost.publishRemoteExit` (`DaemonMain.kt:2197-2208`) |

## 2. Inside the container

It runs the same binary and the same session manager. The board is still built in runner mode (`BoardWiring.buildTicketServices`
runs unconditionally; `DeploymentMode.hasBoard` is never read), but it is unreachable: `DeploymentMode.refusal`
(`DaemonMain.kt:3506`) refuses those frames. The runner-only additions are necessary adapters: `RunnerLinkServer` (595 lines:
auth, allow-lists, grants), `RemoteStarts` (326: clone and push, because there is no shared disk), bearer and pairing.

## 3. Duplication

| Remote piece | Lines | What it duplicates | Kind |
|---|---|---|---|
| `runners/RemoteSessionMirror.kt` | 173 | the session record store: it synthesises `SessionRecord`s from events with guessed fields (`provider="remote"` fallback, `providerSessionId="remote"`, `runCount=1`, `workingDir = repoRoot` at `:157`) | **true duplicate** (a second session store) |
| `runners/RemoteTranscriptPump.kt` | 139 | the live stream: it polls frames and re-publishes them. The line-parsing fallback (`:65-88`, `kindOf` guesses a frame kind from text) is for older runners | **duplicate + fallback** |
| `runner-sessions.json` | (in the mirror) | session persistence | duplicate |
| Outbox events (`RemoteStarts` events, `RunnerWatch` 130) | ~200 | the `session` / `status` / `exit` frames the stream already carries; it is a second, parallel event channel | duplicate channel |
| `runners/RunnerPromptRelay.kt` | 112 | the prompt store's open/answer, over events; retry loop | adapter, but on the wrong channel |
| GREEN-GAP-1 failure scrape (`DaemonMain.kt:2169-2177`) | 9 | the `error` frame: it greps the transcript for "turn failed" | hack |
| `runnerFrames` filter dropping STATUS (`DaemonMain.kt:1490`) and `RELAYED` without STATUS (`RemoteTranscriptPump.kt:103`) | 2 | `runActivity` (tool-call counts) never crosses | **gap** |
| `StartedSession.Remote` + `startRemote` | ~45 | the start result | adapter (could fold into one `SessionRef`) |
| `if remote` branches in callers | 16 `runnerOf(` in `DaemonMain.kt`, 41 `remoteMirror` / `remoteSessions()` / `remoteStatus` / `remoteForget` / `remoteTranscripts` references, plus `LocalBoardHost` (2), `LocalSpawns` (1), `remoteSession` / `remoteLines` in `DaemonPluginHost` wiring (`DaemonMain.kt:1792-1797`) | the seam itself | **the root cause** |

What stays as a real transport adapter: `RemoteRunnerLink` (144), `RunnerLinkServer`, `RunnerLinkService` (transport, keystore,
rotation), `RemoteStarts` clone and push, `CredentialLender`, pairing and bearer. These are not duplicates.

## 4. The symptoms, explained by the design

- **Output never reached the chat:** the orchestrator only reads in-process frames. Remote output had no path until
  RUNNER-OUTPUT-1 added one by polling. That fixed the symptom, not the shape.
- **`C:\` working dir on a runner session:** `RemoteSessionMirror.kt:157` sets `workingDir = p?.repoRoot`, the orchestrator's
  own checkout, because the mirror invents the record instead of receiving the runner's own `session` frame (which knows `/work/...`).
- **"working (0 tool calls)" while an approval card waits:** `runActivity` is computed only from local manager events
  (`DaemonMain.kt:2650`), and the runner's STATUS frames (which carry `runActivity`) are filtered out (`:1490`, `RELAYED`).
  The mirror knows only BUSY.
- **Output lag:** at best `outputPollMs`, plus an event poll, plus one wss round trip per session per tick. The heartbeat is the
  fallback. Push would be immediate.
- **Bearer stale in docker logs / endpoint 172.17.0.2:** the entrypoint prints `runner bearer` once in the background, and the
  runner computes "its own address" from inside the Docker bridge (`cli/RunnerCli.kt:124-134`). The container cannot know the
  host-mapped address; that has to come in from `docker run` (env), not be guessed. (Ticket c3d46a97.)

## 5. Target design

One rule: **the orchestrator never touches `SessionManager`. It only touches `RunnerPool.linkFor(sessionId)`.** Local is the
in-process binding; remote is the wss binding. No caller asks which one it has.

1. **A complete command contract.** Add `interrupt`, `queue(add|remove|force)`, `attach` and `delete` to `RunnerLink`, answered
   by `LocalRunnerLink` via the manager. Every `DaemonMain` handler calls `pool.linkFor(id).x(...)`; the `if remote` branches go.
2. **Push, not poll: one stream.** Add `RunnerLink.subscribe(resumeFrom: Map<sessionId, seq>, sink: (Envelope) -> Unit)`. The local
   binding hands the manager's broadcast frames straight to the sink (zero copies, same as today). The remote binding pushes
   sequenced frames over the already-open wss (the runner sends unsolicited `runner_link_frame` messages; the link already
   long-polls in the reverse direction for `FrameForwarder`). On reconnect it resumes from the last sequence per session; the
   existing `readFrames` stays only as that catch-up read. **All** frame types cross, including `session`, `status` (with
   `runActivity`), prompts and `error`.
3. **One session directory.** The orchestrator's session list is the union of what each runner's `session` frames say, tagged
   with the runner's name. `RemoteSessionMirror`'s synthesised records go; what is left is the placement index
   (sessionId -> runner, last sequence), persisted in `runner-sessions.json`. This fixes workingDir, provider and state.
4. **Prompts and failures as frames.** Approval cards open from the relayed prompt frame, through the same prompt store as local;
   the outbox prompt events and the transcript scrape go. Outbox events remain only for what is not a stream frame (git branch
   pushed).

Deleted: the line-parsing half of `RemoteTranscriptPump` (all of it once frames are pushed), the output-poll ticker, the replay
`pull` branches, the mirror's record synthesis, the prompt-relay event path, the failure scrape, 16+41 caller branches.
Kept: the transport, pairing, clone/push, credentials, placement.

Expensive to reverse: the wire change in step 2 (contract major bump). Pre-stable, the old-runner fallback is dropped rather than
kept: the image is rebuilt with the orchestrator (one build, one image).

## 6. Migration tickets (smallest safe first)

Each ends with the in-app ping/pong proof: the gradle-run app plus `infantry-runner`, a prompt answered in the chat, and a screenshot.

1. **RUNNER-UNIFY-2: every session command through `linkFor`.** Add interrupt, queue, attach and delete to `RunnerLink` and
   `RunnerLinkWire`; `LocalRunnerLink` answers them; replace the handlers at `DaemonMain.kt:3590`, `4238`, `4278`, `4296`, `4474`.
   Fixes interrupt and queue on remote sessions. Risk: low-medium. Weight: medium.
2. **RUNNER-UNIFY-3: pushed session frames.** `RunnerLink.subscribe`; the runner pushes over wss; the orchestrator publishes with
   per-session resume. Delete the output ticker and `RemoteTranscriptPump` (keep the catch-up `readFrames`). Relay STATUS with
   `runActivity`. Files: `OrchestratorLink.kt`, `RunnerLinkWire.kt`, `RemoteRunnerLink.kt`, `RunnerLinkServer.kt`,
   `RunnerLinkService.kt`, `DaemonMain.kt` (1482-1494, 2195, 2238-2245, 3527-3536). Risk: high (wire). Weight: heavy (Opus).
3. **RUNNER-UNIFY-4: one session directory.** Records come from the runner's `session` frames; the mirror shrinks to a placement
   index; `remoteMirror.list()` concatenations go. Fixes the `C:\` working dir. Risk: medium. Weight: medium-heavy.
4. **RUNNER-UNIFY-5: prompts and failures on the stream.** Approval cards from relayed prompt frames through the same prompt
   store; drop the `RunnerPromptRelay` event path and the GREEN-GAP-1 scrape. Risk: medium. Weight: medium.
5. **RUNNER-ENDPOINT-1 (or fold into c3d46a97):** the advertised endpoint comes from the `docker run` env (the host and port the
   PC dials); `docker logs` prints the bearer from the file at every start. Risk: low. Weight: light.
6. **RUNNER-UNIFY-GUARD-1:** a guard test that no orchestrator caller compares `runnerOf(...)` with `RunnerPool.LOCAL` or calls
   `manager.kill|resume|interrupt|delete` outside `LocalRunner` (allow-list with reasons). Land it last, locking the seam.
   Risk: low. Weight: light.
