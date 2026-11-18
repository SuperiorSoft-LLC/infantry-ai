<!-- SPDX-License-Identifier: MPL-2.0 -->
# The native `infantry mcp serve` (RUST-MCP-1)

Status: built and conformance-tested; **not switched on**. The owner decides the rollout. Kotlin is unchanged.

Every agent session the daemon spawns starts its own `infantry mcp serve`, a stdio MCP bridge to the daemon. Today that
is a JVM per session. `rust/crates/infantry-cli` builds a native `infantry` binary that serves the same command, byte
for byte, against the current daemon. Plan item 2.3 of [PLAN.md](PLAN.md).

## What it is

- `infantry mcp serve` with the Kotlin command's flags (`--host`, `--port`, `--url`, `--endpoint`, `--endpoints`,
  `--token-file`, `--project`, `--mcp-depth`, `--max-depth`, `--max-spawns`, `--allow-kill-any`), environment
  (`INFANTRY_*` with the `AGENTHQ_*` fallbacks, the `INFANTRY_LIMIT_MCP_*` limits, `INFANTRY_CLI_ASCII`,
  `INFANTRY_MCP_PID_FILE`, `INFANTRY_MCP_DEPTH`), `.infantry/mcp.json`, stdout, stderr lines and exit codes.
- The same daemon frames: `plugin_tools`, `plugin_tool_call` / `plugin_tool_result`, `project_list`. Plugin tools come
  from the daemon, as today; the one tool the bridge serves itself is `infantry_watch_file`.
- Any other command (`infantry board`, `infantry mcp list`, a flag it does not parse) is handed unchanged to the JVM
  launcher (`INFANTRY_BIN`'s `infantry.cmd` / `infantry.bat`, else the one next to the binary, else `infantry` on the
  PATH), with the same stdin, stdout, stderr and exit code. So the binary can stand wherever the JVM launcher stands.

Build (needs a Rust toolchain; Windows MSVC is what was tested):

```
cd rust
cargo build --release          # rust/target/release/infantry.exe (and mcp-conformance.exe)
cargo test --release           # unit tests: JSON fidelity, capping, config, args, line reading
```

## Pointing a session at it

The knob that exists today needs no code change. It works for **Claude sessions**: the daemon passes each
`INFANTRY_CLAUDE_MCP_CONFIG` value (a file path or inline JSON) to Claude as `--mcp-config`. When one of them already
defines a server named `infantry`, the daemon keeps that one and does not add its own entry (`InfantryMcp.claudeMcpConfigs`,
"MERGED, NEVER OVERWRITTEN"). The session identity (`INFANTRY_SESSION_ID`, `INFANTRY_PROJECT_ID`, `INFANTRY_ROLE`,
`INFANTRY_URL`, `INFANTRY_BIN`, the token) reaches the bridge the way it reaches the JVM one: the harness inherits the
session env.

1. Put the binary somewhere stable, e.g. `C:\Program Files\InfantryAI\native\infantry.exe`.
2. Write a config file, e.g. `infantry-native-mcp.json`:

   ```
   {"mcpServers":{"infantry":{"type":"stdio","command":"C:\\Program Files\\InfantryAI\\native\\infantry.exe","args":["mcp","serve"]}}}
   ```

   A `.exe` is spawned directly (no `cmd /c` as the `.cmd` launcher needs).
3. Start the daemon with `INFANTRY_CLAUDE_MCP_CONFIG=<path of that file>`. Sessions started after that use the native
   bridge. Removing the variable (and restarting the daemon) goes back to the JVM bridge.

To try it on one test daemon only: `scripts/testdaemon.sh` scrubs the environment, so export the variable inside a
copy of its `up` step, or start the daemon by hand with the test daemon's state dir and port.

Not covered by that knob: harnesses that build the entry themselves from `InfantryMcp.launch` (opencode and the other
non-Claude harnesses). A general switch would be a setting (for example `mcp.bridge` = the command, default the JVM
launcher) read in `InfantryMcp.launch`. That is a small Kotlin change, left for the owner's rollout decision, since Kotlin
is frozen for this ticket.

## Conformance

`mcp-conformance` runs the same MCP script against both bridges and compares what they write, byte for byte:

- **Phase A, a test daemon** (port 189xx, temp state dir): initialize (and an unsupported protocol version), ping,
  tools/list, plugin tool calls (with `detail`, `maxChars`, the `agenthq_` alias), an unknown tool, a call with no
  name, `infantry_watch_file` with and without a path, an unknown method, a `null` id, a string id, a batch array, a
  missing or non-string method, an ignored notification, a blank line. It also checks the served tools against
  `core-mcp-tools-before-f5.json`, byte for byte per tool.
- **Phase B, a stub daemon** the driver serves on an ephemeral loopback port: the tool list changes mid-session and
  `notifications/tools/list_changed` must follow. The stub echoes every `plugin_tool_call` payload back, so the two
  bridges' payloads are compared too (detail / maxChars handling, `verbose` for `detail: full`, `bodyChars`,
  `localFiles` with base64, the client block, number fidelity, Unicode and control characters), plus `isError` as a
  string, a malformed answer, a daemon `error` frame, a tool with no `timeoutMs`, and result capping that cuts through
  a surrogate pair.

```
scripts/testdaemon.sh up --name rustmcp                      # prints port and state dir
rust/target/release/mcp-conformance.exe \
  --kotlin superiorSoftLibrary/infantry/build/install/infantry/bin/infantry.bat \
  --rust rust/target/release/infantry.exe \
  --golden superiorSoftLibrary/infantry/src/test/resources/golden/core-mcp-tools-before-f5.json \
  --env INFANTRY_URL=ws://127.0.0.1:<port>/ws --env INFANTRY_STATE_DIR=<state dir> \
  --env INFANTRY_BIN=<repo>/superiorSoftLibrary/infantry/build/install/infantry/bin
scripts/testdaemon.sh down --name rustmcp
```

The driver scrubs every `INFANTRY_*`, `AGENTHQ_*` and `CLAUDE*` variable from the bridges' environment, so a run from
inside an agent session never speaks as that session. Transcripts go to `--out` (default `%TEMP%\infantry-mcp-conformance`).

### Results (2026-11-18, Windows 11, JDK 21, test daemon on port 18924)

```
phase A (test daemon)
  stdout: IDENTICAL (19 lines)
  stderr (own lines): IDENTICAL (4 lines)
  exit code: both 0
  golden: kotlin 6/22 served golden tools byte-identical, rust 6/22
phase B (stub daemon)
  stdout: IDENTICAL (23 lines)
  stderr (own lines): IDENTICAL (3 lines)
  exit code: both 0
  list_changed sent after the flip: yes
CONFORMANCE: IDENTICAL
```

The golden line: all 22 golden tools are served, and the same 6 match the golden file's bytes on both bridges. The other
16 descriptors changed in the daemon after the golden was recorded; the bridge passes them through, so both bridges
differ from it the same way. Against the golden, the native bridge is byte-identical wherever the Kotlin one is.

Memory (working set of the process tree the client starts; the Kotlin entry is `cmd /c infantry.bat`, so `cmd.exe` +
`java.exe`):

| | idle (after initialize + tools/list) | after the phase A calls | after the phase B calls | peak |
|---|---|---|---|---|
| Kotlin | 112.3 MB (java 103.5 + cmd 8.8) | 119.2 MB | 121.3 MB | java 114.5 MB |
| Native | 6.8 MB | 7.2 MB | 6.7 MB | 7.9 MB |

About 16 times less per session, so ten agent sessions go from about 1.2 GB to about 70 MB of bridges.
`infantry_watch_file` still starts one short JVM for the duration of its call (see below).

## Known differences

These are not on the conformance script. None of them changes a successful answer.

| Where | Kotlin | Native |
|---|---|---|
| A line that is not JSON | `-32700`, text from kotlinx (`Parse error: Unexpected JSON token at offset ...`) | `-32700`, text from serde_json (`Parse error: expected value at line 1 column 1`) |
| `.infantry/mcp.json` that is not JSON | same split: the parser's own words after `is not valid JSON (` | serde_json's words |
| A `\uD800`-style lone surrogate escape in a request | accepted | refused as a parse error |
| `infantry_watch_file` | runs `bg watch-file` in-process | runs `infantry watch-file` through the JVM launcher: the same output, plus one short-lived JVM per call (the watcher it starts was a separate JVM either way) |
| `mcp serve` with a flag only other commands use (e.g. `--json`) | parsed and ignored | handed to the JVM launcher, which serves it |
| An invalid `INFANTRY_LIMIT_MCP_MAX_RESULT_CHARS` | the server stops with an exception at the first tool answer | the default (8000) is used |
| The state dir | created / migrated from `~/.agenthq` on first use | only read (the daemon or the JVM CLI does the migration) |
| A reconnect's failure line | names the JVM exception class | always says `ConnectException` |
| A number too large for a Double (`1e400`) in a message to the client (a request id, a daemon tool schema) | the encoder throws mid-write | the message is not written; one `infantry mcp: could not encode a message` line on stderr |

Numbers follow kotlinx, not the literal text: kotlinx's `JsonLiteralSerializer` re-encodes an unquoted literal (a Long
as itself, `-0` as `0`, anything else through Java's `Double.toString`, so `1.50e3` becomes `1500.0`). The native
bridge does the same (`kjson.rs`) on stdout and in the `plugin_tool_call` frame, and a non-finite argument fails the
call with kotlinx's own message. The conformance run covers this.
