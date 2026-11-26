# Moving to the Rust app (RUST-MIGRATE-1)

How to try the Rust daemon and Tauri app on your real sessions and projects without risking the Kotlin app, and how
to switch over for good later. The tool is `infantry-migrate` (`rust/crates/infantry-migrate`).

There are three steps: **snapshot**, **verify**, **use the Rust app**. The Kotlin app keeps running the whole time.
Its state dir is only ever read.

| | Path |
|---|---|
| Kotlin state dir (the source, never written) | `%LOCALAPPDATA%\SuperiorSoft\InfantryAI` |
| Rust state dir (the copy) | `%LOCALAPPDATA%\SuperiorSoft\InfantryAI-Rust` (`--to` changes it) |
| The previous copy | `<copy>.prev` |

## 1. Snapshot

```
cd rust
cargo build --release -p infantry-migrate
target\release\infantry-migrate snapshot
```

This copies every file in the Kotlin state dir into the copy:

- **SQLite databases** (`infantry.db`, `usage-meter.db`, ...) go through SQLite's online backup API, never a raw file
  copy. A database the Kotlin daemon has open (it has a `-shm`) is opened read-only, and the backup runs in one read
  transaction. The copy is the database at one commit, including what is still in the WAL. A closed database is
  first copied into a scratch folder with its sidecars and backed up from there. That way, opening it can never
  create a `-wal` or `-shm` file in the source.
- **JSON stores** (`sessions.json`, `mailbox.json`, ...) are re-read until they parse, because the Kotlin daemon
  replaces them while it writes. Each file is written to the copy and renamed into place.
- **Secrets** (`keystore.json`, `keystore.key.dpapi`, `owner.token`, `remote.token`, the runner link files, `mcp/`,
  ...) are copied byte for byte. When the source file has a protected, owner-only access list, the copy gets the
  same one. The tool never prints a file's content.
- **Runtime files are skipped**: `daemon.lock`, `daemon.pid`, `daemon.port`, `state.lock`, `*.tmp`, SQLite sidecars,
  `*.db.holders/` and symlinks. The manifest lists each one with the reason.

The new copy is built in `<copy>.snapshot` and swapped in only when it is complete. The previous copy becomes
`<copy>.prev`, and the copy before that is deleted. The tool refuses to write when:

- a daemon is serving the copy (stop the Rust app first);
- the destination exists but holds no `migration.json` (it is not a snapshot; `--force` replaces it);
- the destination is the source, or one is inside the other.

The copy's `migration.json` records when it was taken, from where, the counts, and each file's size and hash.

## 2. Verify

```
target\release\infantry-migrate verify
target\release\infantry-migrate status
```

`verify` prints counts and paths only. It **fails** when:

- a database fails `PRAGMA integrity_check`, or a table cannot be read;
- a file from the manifest is missing from the copy;
- a session record does not load as the Rust daemon's `SessionRecord`;
- a protected access list differs from the source's.

It also compares the copy with the source (sessions, projects, agents, roles, per-table row counts). A difference
there is reported but does not fail: the Kotlin app keeps working after the snapshot, so the source moves on.

`status` says when the snapshot was taken, what it holds, whether a daemon is serving it, and whether a `.prev`
exists.

## 3. Use the Rust app on the copy

Point the Tauri app's sidecar daemon at the copy. Set it in `ui.json` (in the Tauri app's data dir):

```
{ "runner": "daemon", "daemonStateDir": "C:\\Users\\<you>\\AppData\\Local\\SuperiorSoft\\InfantryAI-Rust" }
```

or for one run, set `INFANTRY_UI_DAEMON_STATE_DIR` to the copy. The daemon on its own works the same way:
`infantry-daemon --state-dir <copy> --port 18901` (or `INFANTRY_STATE_DIR`).

What you get:

- **Every migrated session** shows in the sidebar with its name (from the agent profile), role, project and status.
- **Projects and agent profiles** come from the copy's `infantry.db`, read-only (`project_list`, `project_get`,
  `agent_list`). Saving a project stays with the Kotlin app until the board is ported.
- **Resume**: a message into a migrated session runs `claude --resume <providerSessionId>` in the session's folder.
- **Never in both apps.** The copy's `migration.json` names the Kotlin state dir. Every 2 s the Rust daemon reads the
  Kotlin `sessions.json` (read-only). A session live there (busy, or its child process still running) is listed with
  `heldBy: "Infantry (Kotlin)"`. The card says "live in Infantry (Kotlin)", the composer is locked, and the daemon
  refuses `prompt`, `queue_add` and `queue_force` into it. When the Kotlin app lets the session go, the hold clears
  on the next read. `heldBy` is never saved.

To pick up what happened in the Kotlin app since, stop the Rust app, run `snapshot` again, then `verify`. The copy
you had becomes `<copy>.prev`. Anything the Rust app changed in it stays there and is not merged.

## 4. The cut-over (`infantry-migrate cutover`)

The owner runs these steps; an agent never applies them to the real state dir.

1. **Plan** (changes nothing in the source): `infantry-migrate cutover`. It takes a fresh snapshot into `--to`
   (default `%LOCALAPPDATA%\SuperiorSoft\InfantryAI-Rust`; the previous copy becomes `.prev`), lists every session
   that can be resumed (a provider conversation id is recorded and the session has not ended) and writes them to
   `<to>/cutover-plan.json`. Run it with the Kotlin app still open to preview; take the final one after quitting it.
2. **Quit the Kotlin app** and its daemon. Then run `infantry-migrate cutover` once more, so the copy is current.
3. **Start the Rust app** (or `infantry-daemon --state-dir <copy>`) on the copy.
4. **Apply**: `infantry-migrate cutover --apply --all`, or `--sessions ID,ID` (8-character prefixes work). It refuses
   while the Kotlin daemon answers, or while no Rust daemon serves the copy. Each chosen session gets one message
   (`--message TEXT`, or a short default) through the Rust daemon's MCP bridge (`infantry mcp serve`, found beside
   `infantry-migrate` or given with `--infantry PATH`). The daemon resumes the SAME conversation:
   `claude --resume <providerSessionId>` in the session's folder, with its role and model, and its MCP config pointing
   at the Rust bridge, so its `infantry_*` tools talk to the Rust daemon. The result is written to `<copy>/cutover.json`.
5. **Rollback**: `infantry-migrate cutover --rollback` stops the applied sessions in the Rust daemon and marks
   `cutover.json` rolled back. Then stop the Rust app and start the Kotlin app: its state dir was never written, so
   it carries on from where it was (work done in the Rust app stays in the copy and is not merged back).

Never run both daemons on the same state dir, and never resume one conversation in both apps at once.

## Commands

```
infantry-migrate snapshot [--from DIR] [--to DIR] [--label TEXT] [--force]
infantry-migrate verify   [--to DIR] [--from DIR]
infantry-migrate status   [--to DIR]
infantry-migrate cutover  [--from DIR] [--to DIR] [--force] [--apply (--all | --sessions ID,ID) [--message TEXT] [--infantry PATH]] [--rollback]
```

- `--from` defaults to `%LOCALAPPDATA%\SuperiorSoft\InfantryAI`, and `--to` to `...\InfantryAI-Rust`.
- `--label` is how the Rust app names the source app ("Infantry (Kotlin)").
- `verify --from` compares against a different source than the one the manifest names.
- Exit codes: 0 ok, 1 failed, 2 unknown command. Progress goes to stderr and the result to stdout.
