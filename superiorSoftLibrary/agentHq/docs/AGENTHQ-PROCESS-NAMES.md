# Recognisable process names for AgentHQ-spawned processes

Status: DESIGN ONLY (ticket-59b793bd). No code in this change. Owner ask, 2026-10-24: in Task Manager every
agent, Gradle and daemon process shows as "OpenJDK Platform binary", so the owner cannot tell which to kill and has
killed some workers by mistake. Wanted: processes we spawn are identifiable by name, in Task Manager and in our own
Background processes list.

## 1. How each process is launched today

| Process | Launched by | Image (`.exe`) | Windows Task Manager "Name" (Processes tab) | Details tab / command line |
|---|---|---|---|---|
| AgentHQ daemon | owner's launcher / installer (jpackage runtime) | `java.exe` (bundled runtime) | "OpenJDK Platform binary" (from the launcher's VERSIONINFO FileDescription) | `java.exe`; args include `-Dagenthq.build=...` (`applicationDefaultJvmArgs`, `build.gradle.kts:46`) |
| Harness: opencode | `ProcessBuilder` in `runner/PipedProcessRunner` via `OpenCodeCommand` (native `opencode.exe`, never the npm shim) | `opencode.exe` | its own FileDescription (readable) | distinct; fine today |
| Harness: claude | `ProcessBuilder` via provider spec | native `claude` binary (verify on owner's box) | its own description (verify) | distinct |
| Harness: pi | npm package, runs under Node | `node.exe` | "Node.js JavaScript Runtime" | `node.exe ... pi ...`; the name `pi` is only in argv |
| Session MCP servers (`agenthq` MCP, `daemon/AgentHqMcp.kt`) | the harness, from its MCP config | `java.exe` | "OpenJDK Platform binary" | argv has the MCP config file |
| Gradle client (`gradlew`) | `agenthq bg run` / worker shells (`session/BackgroundWork.kt` label is the command) | `java.exe` | "OpenJDK Platform binary" | argv `org.gradle.wrapper.GradleWrapperMain` |
| Gradle daemon / Kotlin daemon / test JVMs | spawned by Gradle from the client | `java.exe` | "OpenJDK Platform binary" | argv `GradleDaemon`, `KotlinCompileDaemon`, `Gradle Test Executor` |
| bash / git / gh children | harness shells, `ProcessBuilder("git", ...)` | `bash.exe`, `git.exe`, `conhost.exe` | their own names | distinct, but no session identity |

Facts that drive the options:

- Task Manager's Processes tab shows the **FileDescription** from the executable's version resource, not the file name.
  The Details tab shows the **image name** (`java.exe`). Changing a file name alone does not change the Processes tab.
- Task Manager does not show command lines by default. Process Explorer, `Get-CimInstance Win32_Process`, and our own
  `ProcessHandle.info().commandLine()` (already read by `session/BackgroundWork.kt` `OsProcessSource`) do.
- Every Java process we start shares one description, so the only per-process identity available to the OS is argv,
  the process tree, or a file name we create ourselves.
- The ledger (`session/ProcessWatch.kt`, ROGUE-1, panel from 9427c8e) already attributes a tracked pid to a `sessionId`.
  That attribution is the cheapest route to the owner's real question ("which one is this worker?").

## 2. Options

Legend: cost is for Windows unless stated. "Signing" means effect on the code-signed jpackage runtime / installer.

### (a) Per-role copy of `java.exe` (or `javaw.exe`) with a distinct file name

Copy the bundled `bin/java.exe` to `bin/agenthq-worker.exe`, `bin/agenthq-gate.exe`, and so on, and launch the copy.
The JVM finds `jvm.dll` relative to the launcher's own path, so the copy must stay in the same `bin` directory.

- Names: Details tab and argv `[0]` show the new name. Processes tab still says "OpenJDK Platform binary" unless the
  version resource is also patched (see below).
- Windows: works. Copies are a few hundred KB each. Hard links avoid the disk cost but share one file, so they do not
  change the image name in a useful way on every Windows version; use byte copies.
- Patching the VERSIONINFO FileDescription (to get the Processes-tab name) changes the signed bytes. On a signed
  runtime that breaks the Authenticode signature; SmartScreen and some AV then warn. An unpatched byte copy keeps the
  signature bytes, but the signature still names the original file; verify on the owner's build what `signtool verify` says.
- macOS/Linux: works for Activity Monitor and `ps`/`comm` (the basename). On macOS a renamed copy inside a signed bundle
  needs re-signing with the Developer ID identity; without it, Gatekeeper/notarisation will refuse. That makes (a) a
  Windows-first option.
- AV: unknown renamed copies of `java.exe` are a mild heuristic risk; low, not zero.
- jpackage: copies must be produced at install time or added to the runtime image at build time; the installer must
  ship them. Each upgrade rewrites them. Moderate.
- Effort: small-medium on Windows (copy + launch-name plumbing); medium once signing is involved.

### (b) A tiny named launcher stub

A small native exe that we build and sign ourselves, named and described per purpose (for example "AgentHQ worker"),
which starts `java` as a child and waits for it, forwarding exit codes and kill.

- Names: the stub's own FileDescription is ours to set, so the Processes tab shows "AgentHQ worker" without touching
  the JVM's signature. One stub per purpose, not per ticket: the description is static.
- Windows: works. Adds one process per launch and one level of tree depth. The stub must stay alive as parent; if it
  exits early, the JVM is orphaned and `BackgroundWorkScanner` / `ProcessLedger` ancestry breaks. Needs careful
  handling of console inheritance (see I2, the console-popup issue).
- macOS/Linux: a stub is also possible but duplicates work that `exec` already does; not needed there.
- Signing: the stub is new code, signed with the same certificate as the installer. No patching of Oracle/Temurin bytes.
- AV: a new unsigned-by-reputation exe is a larger heuristic risk than (a) until it has signing history.
- Build: needs a native toolchain (C/Rust/Go) the Gradle build does not have today. Medium-high effort.

### (c) Console / window title

`title <text>` or `SetConsoleTitle` changes the console window caption only.

- Names: not visible in Task Manager, not in the process list. Only useful for a visible console window.
- Cost: trivial. But the daemon has no console (open issue I2), so the owner's popups are not solved by titles.
- Verdict: cosmetic; do not build it for this ask.

### (d) Identity in the command line and in our own process list

Put `-Dagenthq.session=<id>`, `-Dagenthq.ticket=<id>`, `-Dagenthq.role=<role>` on every JVM we start, and add
`--agenthq-session=<id>` style markers where a harness tolerates extra argv (verify per harness; do not add flags a
harness rejects). In the ledger, show owner session, ticket and role per row in the existing Background processes view
(`agenthq processes`, `agenthq_processes`, and the processes panel from 9427c8e).

- Names: visible to Process Explorer, `Get-CimInstance`, and our own list. Not in Task Manager's Processes tab.
- Windows / macOS / Linux: identical. Cross-platform, no binary change, no signing, no installer change.
- Reads: `ProcessHandle.info().commandLine()` already exists in `OsProcessSource`; no new privilege.
- Limits: the command line is only what we pass at spawn. Node/pi processes that do not take our flags stay
  unidentified by argv; the ledger's own pid→session map still covers them.
- Effort: small. Mostly the ledger row and the panel, plus flags on JVMs we control.
- Reversibility: cheap. A flag can be renamed; the panel is a view.

### (e) Named Job Objects (Windows)

Create a Job Object per session (`CreateJobObject` with a name, `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`), assign the
harness tree to it.

- Names: a job's name shows in Process Explorer's Job column and handle table, not in Task Manager's Processes tab.
- Strong property: kills the whole tree when the job closes, which helps the "killed the wrong one / orphaned children"
  problem more than naming does. Also gives per-job CPU/memory accounting.
- Windows only. Java has no job-object API; needs JNA or the FFM API (preview on the project's Java 21 unless the
  toolchain moves). Nested jobs need Windows 8+.
- Effort: medium-high, with risk on the kill path. Worth its own ticket later, not for the naming ask.

## 3. Recommendation and slices

Recommendation: do (d) now, cross-platform and without touching any binary. It answers the owner's actual mistake
(killing the wrong process) because the owner can see the ticket and role next to each pid in our own list and reap
from there. Treat (a) as the optional Windows-only step for Task Manager names, after the signing question is answered.
Do not do (b), (c) or (e) for this ask.

Slices:

1. **S1, no JVM touch (all OS):** add ticket and role to the ledger rows, derived from the existing `sessionId`
   attribution; show owner session, ticket, role, and the spawn argv per row in the Background processes view and
   `agenthq processes`. Add the three `-Dagenthq.*` properties to the daemon's own JVM args (`build.gradle.kts`
   `applicationDefaultJvmArgs`) so the daemon row reads "agenthq daemon" in our list.
2. **S2, identity on JVMs we start:** pass the `-Dagenthq.*` properties on the Gradle gate and test JVMs we launch
   (through `agenthq bg run` and gradle args), and on the session MCP JVM. Verify each harness accepts any extra argv
   before adding it.
3. **S3 (owner decision, Windows only):** per-role copies of the bundled `java.exe` (option a) for Task Manager Details
   names, with the signing check first. Decision needed on whether the Processes-tab description may be patched,
   which we do not recommend while the runtime is signed.
4. **Later, separate ticket:** Job Objects (option e) for kill-tree safety.

What can be done without touching the JVM binary: S1 and S2 entirely.

Expensive-to-reverse decisions:

- Patching the VERSIONINFO of a signed runtime (S3 variant): breaks the signature and affects SmartScreen reputation
  for every install. Hard to undo once shipped to the owner's installs.
- Renaming copies inside the jpackage image: changes what the installer must ship and how upgrades overwrite files.
- Stub launcher (b): adds a native build target and a signing identity to maintain forever.

Cheap to reverse: the `-Dagenthq.*` names, the ledger columns, and the panel. Choose these names once, now, since
argv is what other tools read.

## 4. Open items to verify before S3

- Whether the shipped runtime's `java.exe` is Authenticode-signed, and what `signtool verify` reports on a byte copy.
- Task Manager's behaviour per Windows version for the Details tab image name versus Processes tab description.
- Whether `claude` and `opencode` native binaries carry their own descriptions on the owner's machine.
- macOS: whether the jpackage bundle is notarised; if so, S3 is Windows-only by necessity.

## 5. Out of scope

No code, no tests. Harness-specific argv flags are not decided here; each needs a check against that harness.
