# Offline demo: canned-data web client with no daemon (design)

Status: design only (ticket-2e6b444e). Owner 2026-10-24: keep BOTH demos: live (wasm client + headless daemon, opencode
free models) and offline (this doc).

## 1. What exists today

`infantryWeb` already has a seed of this: the token gate's "Try the offline demo" button sets `WebMode.Demo`, which builds
`Controller(scope, FakeDaemonClient(scope))`. `FakeDaemonClient` subclasses the `DaemonClient` seam in infantryUiCore, emits
`ready`, `sessions`, a few `turn`/`text_delta` frames, and answers a `prompt` with one canned reply. Fixtures are 3
sessions in `DemoFixtures.kt`. So the seam is right; the gaps are content and labelling:

- No board (`tickets`), no question panel (`questions`/`question`), no `reasoning`, no tool/diff blocks, no mail.
- Every other request (`ticket_list`, `ticket_move`, `question_answer`, `new`, `kill`...) is swallowed: `send` returns true
  and nothing changes, so buttons look dead.
- No visible DEMO marker inside the app (only a detail string "demo data, no daemon" on the connection chip).
- Reachable only by clicking through the token gate; no URL entry point.

## 2. Requirements

1. Same normalized event shape as the real daemon (`Envelope` types from `WireProtocol.kt`); UI code untouched.
2. Sample sessions, board, question panel, reasoning, tool output.
3. Unmistakable DEMO label, always visible, cannot be mistaken for a live daemon.
4. No daemon, no socket, no token, no SQLite (web client keeps only the token in localStorage; demo keeps nothing).
5. Cheap to keep in sync with contract changes.

## 3. Options

### A. Static fixtures replayed once (today's model, extended)
Hand-written JSON per frame type, emitted on connect; `send` mostly ignored.
+ Smallest. - Dead buttons; fixtures rot silently when a view type gains a required field.

### B. Scripted in-memory fake daemon (recommended)
`FakeDaemonClient` keeps a small mutable model (sessions, tickets, questions) and implements a **whitelist** of request
types, answering with the same push frames the daemon would: `ticket_list`->`tickets`, `ticket_move`->`ticket` push,
`question_answer`->`question` push (state `answered`), `prompt`->BUSY, `reasoning` deltas, `text_delta`, tool block, IDLE.
Plus a short **autoplay script** (timed frames after connect: a developer session streams reasoning then finishes, a new
question appears) so the page is alive without input. Unsupported requests get a visible toast
("Not available in the demo") via an `error` frame, not silence.
+ Whole UI explorable, still deterministic, no network. - Maintains a mini-daemon (about 300 lines); must track contract.

### C. Record/replay a real session
Capture frames from a live daemon to a JSON file, bundle it, replay with timing.
+ Most realistic, auto-covers shapes. - Real data can leak (paths, names, tokens): needs scrubbing; frames are recorded,
not interactive; a recorder tool is more code than B. Good later as a *source of fixtures* for B, not as the engine.

### D. Run the real daemon logic compiled to wasm
Rejected: daemon is JVM, SQLite, process spawning. All state lives there.

### Where to hold the fixtures
- Kotlin objects (as now): type-checked against `@Serializable` view classes if built with the real serializers
  (`wireJson.encodeToString(TicketView(...))`) instead of raw strings. **Do this**: a contract change then breaks the
  build, not the demo at runtime.
- JSON resource files: editable by non-Kotlin people but no compile-time check. Not recommended.

## 4. Entry point

| Option | How | Notes |
|---|---|---|
| URL flag | `index.html#demo` or `?demo=1` selects `WebMode.Demo` at startup | Zero extra build, shareable link, works when the same bundle is hosted anywhere static. **Recommended, plus the existing gate button.** |
| Separate build | Own Gradle dist with demo-only `main()` and no `fetch('/attention')` | Guarantees a live daemon can never be reached from the demo page, and can be dropped on GitHub Pages / a CDN. Costs a second dist target. |

Recommendation: ship ONE bundle; `#demo` flag (fragment, never sent to a server) + gate button. The demo never calls
`fetch`/WebSocket, so nothing to isolate. If we later publish a public demo page, add `assembleDemoSite` that just copies
the same bundle with a generated `index.html` that sets the flag (a few lines of Gradle, not a second compile).

## 5. DEMO label

- Persistent banner/ribbon above the shell (not dismissible): "DEMO: canned data, no daemon. Nothing you do here is saved
  or sent." Colored distinct from any state chip. Rendered by `WebRoot` around `InfantryShell`, so core UI is untouched.
- Connection chip detail reads `demo` (already does); browser tab title prefixed "[DEMO]".
- Session ids prefixed `demo-`, workingDir `/demo/...`, provider `demo`; sample names are obviously fictional.
- Reply text and ticket titles are plain fiction; no real project, person, token or path appears in fixtures.

## 6. Read-only vs scripted

Recommendation: **scripted-interactive, never persistent.** Reads and a safe subset of writes work against the in-memory
model (move a ticket, answer a question, send a prompt, open a session); everything else is refused with the visible
toast. A "Reset demo" action (reload) restores the start state. Pure read-only is simpler but makes the board and question
panel look broken, which defeats the point of a demo.

Whitelist (first version): `ticket_list`, `ticket_move`, `ticket_save`, `question_list`, `question_answer`, `prompt`,
`interrupt`, `new` (spawns a canned session). Refuse: `kill`, delete, worktree, mail send, commands, anything touching
files/processes.

## 7. Slices and effort

| # | Slice | Effort |
|---|---|---|
| 1 | Entry flag `#demo` + demo banner + tab title (Main.kt only) | 0.5 d |
| 2 | Fixtures built from real `@Serializable` views: 5 sessions, 8 tickets across 4 columns, 2 questions, reasoning + tool block history | 1 d |
| 3 | `FakeDaemonClient` request whitelist + in-memory model + "not available" toast | 1 d |
| 4 | Autoplay script (timed streaming, a late question) | 0.5 d |
| 5 | Browser smoke check (page loads in `#demo`, banner visible, board has cards, answering a question changes state), docs/README | 0.5 d |
| 6 (optional) | Frame recorder to seed fixtures; static-site assemble task | 1-2 d |

Total core: about 3.5 days. Slices 1-3 are independent of 4; 1 can ship alone and already improves today's demo.

## 8. Decisions and risks

- Cheap to reverse: entry flag, label wording, fixture content.
- Expensive-ish: demo depends on the `DaemonClient` seam staying `open` and the wire view classes staying public in
  infantryUiCore; keep the demo on the typed views so contract bumps fail the build.
- Risk: demo drifts from the real UI states (e.g. new required push). Mitigation: typed fixtures and one smoke test.
- No SQLite, no storage, no network on wasm: the demo adds no persistence beyond the existing token key (unused in demo).
- The live demo (headless daemon + opencode free models) is separate and unaffected.
