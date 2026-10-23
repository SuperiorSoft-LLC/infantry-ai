# Installing AgentHQ (production build)

An installable AgentHQ: desktop window plus its own daemon, in one JVM, isolated from the dev setup.

## Build

```powershell
./gradlew :superiorSoftLibrary:agentHqUi:packageReleaseMsi   # Windows installer (.msi)
./gradlew :superiorSoftLibrary:agentHqUi:packageReleaseExe   # same, as a setup .exe
```

The installer version comes from `-PagenthqVersion` (default `1.0.0`):

```powershell
./gradlew :superiorSoftLibrary:agentHqUi:packageMsi -PagenthqVersion=1.0.1   # -> .../msi/AgentHQ-1.0.1.msi
```

It must be `MAJOR.MINOR.BUILD` (MAJOR 1..255, MINOR 0..255, BUILD 0..65535; the build fails fast otherwise), and
it must go **up** for every build installed over an older one: Windows Installer only upgrades over a lower version,
and refuses to reinstall the same one. Scheme: `1.0.N`, N +1 per build handed to the owner; MINOR/MAJOR for bigger
releases. UPDATE-1 compares these numbers (see [Updates](#updates-update-1)).

`packageMsi` / `packageExe` build the same thing without the `release` build type (ProGuard is off in both,
so the two are equivalent today). The build is slow, a few minutes: jpackage bundles a full Java runtime, so
the target machine needs **no JDK**. Building needs a JDK 21 with `jpackage` (the Gradle toolchain), plus the
WiX toolset, which Compose downloads on its own the first time.

Output:

```
superiorSoftLibrary/agentHqUi/build/compose/binaries/main-release/msi/AgentHQ-1.0.0.msi
superiorSoftLibrary/agentHqUi/build/compose/binaries/main-release/exe/AgentHQ-1.0.0.exe
```

(without `Release`: `.../binaries/main/msi/` and `.../binaries/main/exe/`).

`run` / `runUi` stay the dev window (8787). To try the installed profile before installing, use
`./gradlew :superiorSoftLibrary:agentHqUi:runDistributable` (the packaged app image, jpackage launcher), or pass
`-Dagenthq.installed=true` to any launch of `AppMainKt`.

## Install

Run the `.msi`. It installs **per user** (no admin prompt), lets you choose the directory (default
`%LOCALAPPDATA%\AgentHQ`), and adds a Start-menu entry and a desktop shortcut, both named **AgentHQ**.
Installing a newer `.msi` upgrades the old one in place (fixed `upgradeUuid`); its version must be higher (see
[Build](#build)).

Silent install, e.g. for a scripted test into a throwaway directory:

```powershell
msiexec /i AgentHQ-1.0.1.msi INSTALLDIR=C:\path	o\dir /qn /l*v install.log
```

### Your data survives upgrades and uninstalls (INSTALL-1)

The program and your data live in **different** folders, on purpose. The MSI deletes its whole install folder on
uninstall (jpackage's `RemoveFolderEx`), and an upgrade uninstalls the old version first. Data kept in the install
folder was therefore erased by the first upgrade. So the state dir is `%LOCALAPPDATA%\SuperiorSoft\AgentHQ`, never
the install dir, and the app refuses a state dir inside its own install folder (an `AGENTHQ_INSTALL_STATE_DIR`
pointing there falls back to the default, with a warning in the log). If you choose `%LOCALAPPDATA%\SuperiorSoft`
itself as the install folder, the state dir moves to `%APPDATA%\SuperiorSoft\AgentHQ`. The first log line says
both: `[agentHq-install] app=<install dir> state=<state dir> port=<port>`.

## What the installed app uses

| | Installed app | Dev setup (untouched) |
|---|---|---|
| Entry point | `AppMainKt`, installed profile (starts its own daemon) | `runDaemon` + `runUi`, or `runRealDaemon` |
| State dir | `%LOCALAPPDATA%\SuperiorSoft\AgentHQ` | `~/.agenthq` |
| Port | **8797** (a taken port falls back to 8798..8807, then any free port) | 8787 daemon, 8788 preview |
| Database | `%LOCALAPPDATA%\SuperiorSoft\AgentHQ\agenthq.db` | `~/.agenthq/agenthq.db` |
| UI data (avatars) | `%LOCALAPPDATA%\SuperiorSoft\AgentHQ\ui` | `~/.agenthq-ui` |
| Logs | `%LOCALAPPDATA%\SuperiorSoft\AgentHQ\logs\agenthq.log` (see [Logs](#logs)) | the console, no file |

Overrides, read by the installed app only:

- `AGENTHQ_INSTALL_STATE_DIR`: a different state dir. It is ignored if it names `~/.agenthq`.
- `AGENTHQ_INSTALL_PORT`: a different port. It is ignored if it is 8787 or 8788.

The dev variables (`AGENTHQ_STATE_DIR`, `AGENTHQ_PORT`, `AGENTHQ_HOME`, `AGENTHQ_DB`, `AGENTHQ_UI_DATA_DIR`) are
**overridden** by the installed app, so a variable exported for the dev daemon cannot pull it onto dev state.
Other `AGENTHQ_*` variables (`AGENTHQ_PROVIDER`, `AGENTHQ_TOKEN`, executable paths) are read as usual.

The daemon's `StateDirLock` guards the installed state dir: a second copy of the installed app refuses to start
on the same directory, the same way the dev daemon does.

### The `agenthq` CLI in sessions (CLIPATH-2)

The installer ships its own `agenthq` launcher in `<install>\app\resources` (from `agentHqUi/appResources/`):
`agenthq.cmd`, plus an `agenthq` sh shim for Git Bash (an `agenthq` sh script on macOS/Linux). It runs
`com.superiorsoftllc.agenthq.cli.CliMainKt` on the app's own jars. The installed profile pins that directory as
`AGENTHQ_BIN`, so every session the installed daemon spawns gets it **first on PATH** and exported as
`AGENTHQ_BIN`. An `AGENTHQ_BIN` in the environment does not override it, the same rule as the dev variables
above. Dev is unchanged: it still uses the `installDist` bin.

The launcher picks `java` in this order: `AGENTHQ_JAVA`, the bundled runtime's `java`, `JAVA_HOME`, then `java` on PATH.

The bundled runtime ships its own `bin\java.exe` (PKG-4), so on an install the bundled branch is the one used and
`agenthq` runs on a machine with no JDK. Compose 1.10.3 always passes `--strip-native-commands` to jlink and has no
DSL switch for it (the flag is an `internal` task property, and `freeArgs` can only add arguments), so
`agentHqUi/build.gradle.kts` adds a `keepJavaLauncher` step to `createRuntimeImage`: right after jlink, it copies
`bin/java(.exe)` from the same JDK jlink ran from, and fails the build if the image lacks the `jli` library java
needs. `./gradlew :superiorSoftLibrary:agentHqUi:verifyRuntimeKeepsJava` (also part of `check`) proves the step is
wired without running jlink. To confirm on a built package, look for `runtime\bin\java.exe` under
`build\compose\binaries\main\app\AgentHQ\` or in the install dir.

## Updates (UPDATE-1)

The installed app checks GitHub Releases for a newer version **at every start** and from **Configuration >
Updates > Check for updates**. It calls `GET /repos/{owner}/{repo}/releases/latest` and compares the release tag
(`v1.0.4` or `1.0.4`, semver) with its own version, which `-PagenthqVersion` bakes into the launcher as `-Dagenthq.version`.
When the release is newer, a dialog shows the version and the release notes, with **Update** and **Later**.

**Update** downloads the release's `AgentHQ-<version>.msi` asset (the [Build](#build) artifact name) into a temp folder
and verifies it. The byte count must equal the asset size. If the release also carries `AgentHQ-<version>.msi.sha256`
(or a `SHA256SUMS` / `checksums.txt` that names the msi), the SHA-256 must match too. Anything else deletes the file and
is refused. The app then writes `apply.cmd` next to the installer, starts it detached, and exits. The script waits for
the app to exit, runs `msiexec /i ... INSTALLDIR=<the current install dir> /passive`, and starts AgentHQ again. Your
data survives because it lives in the state dir, not the install dir (see above). `msiexec.log` and `apply.result` stay
in the temp folder.

A check that fails (offline, no release, a 401/403, a rate limit, no msi in the release, a bad download) shows a short
dismissable message in the corner and never blocks the window. A start-up check that finds nothing newer stays silent.

Configuration (installed app only; **a dev build never updates itself**):

- `AGENTHQ_UPDATE_REPO=owner/repo` (env var or `-D`), or `repo=owner/repo` in `<state dir>\update.properties`.
  Without it, updates are off, and Check for updates says so.
- Private repo token: `AGENTHQ_UPDATE_TOKEN` (env var only). Otherwise the app asks the OS credential store through
  `git credential fill` (Git Credential Manager keeps the GitHub login in the Windows Credential Manager),
  non-interactively, and only when the API is `https://api.github.com`. The token is **never** read from a file
  (a `token=` line in `update.properties` is ignored), never logged, and never sent past a redirect to another host
  (asset downloads redirect to GitHub's storage).
- `AGENTHQ_UPDATE_API`: a different API base. Only tests and the smoke run use it, to point at a local fake server.

### Publishing a release (RELEASE-ASSETS-1)

One task builds everything a release needs; **publishing stays your manual step** (the task never pushes, tags or
calls GitHub):

```powershell
./gradlew :superiorSoftLibrary:agentHqUi:releaseAssets -PagenthqVersion=1.0.N
```

It runs `packageReleaseMsi` (a few minutes) and writes `superiorSoftLibrary/agentHqUi/build/release/1.0.N/`:

- `AgentHQ-1.0.N.msi`, the installer
- `SHA256SUMS`, one `sha256sum` line (`<64 hex>  AgentHQ-1.0.N.msi`), which the updater checks the download against
- `RELEASE-NOTES.md`, a stub with the version, tag and git short sha. Edit it: the app shows this text in its update dialog.

It refuses a missing `-PagenthqVersion` (no `1.0.0` default for a release) and an invalid one, before jpackage starts.
`N` must be higher than the last published release, or installed apps will not see it as an update.

Then publish to the repo the installed app reads (`AGENTHQ_UPDATE_REPO` / `update.properties`, above), from the repo
root, with the [GitHub CLI](https://cli.github.com) (`gh auth login` once):

```powershell
$v = "1.0.N"; $dir = "superiorSoftLibrary/agentHqUi/build/release/$v"
git tag "v$v"; git push origin "v$v"          # optional: gh creates the tag at the default branch's tip otherwise
gh release create "v$v" "$dir/AgentHQ-$v.msi" "$dir/SHA256SUMS" --repo owner/repo --title "AgentHQ $v" --notes-file "$dir/RELEASE-NOTES.md"
```

Or the web UI: the repo's **Releases > Draft a new release**, tag `v1.0.N` (create it there), title `AgentHQ 1.0.N`,
paste `RELEASE-NOTES.md` as the description, drag in `AgentHQ-1.0.N.msi` and `SHA256SUMS`, and **Publish release**
(not pre-release and not draft: the updater reads `releases/latest`, which skips both). Keep the asset names unchanged;
the updater looks for exactly `AgentHQ-<version>.msi` and `SHA256SUMS`.

## Known gaps

- The installed runtime is the full JDK module set (`includeAllModules`). It is larger than needed, but it
  cannot fail at runtime on a missing module. It can be trimmed later with `suggestRuntimeModules`.

## Logs

A Start-menu launch has no console, so the installed app writes everything the daemon and the window print
(stdout, stderr, and any library logging to them) to a file:

```
%LOCALAPPDATA%\SuperiorSoft\AgentHQ\logs\agenthq.log      <- this launch
%LOCALAPPDATA%\SuperiorSoft\AgentHQ\logs\agenthq.1.log    <- the launch before
...                                                       up to agenthq.4.log
```

(`<AGENTHQ_INSTALL_STATE_DIR>\logs` when that override is set). Each launch starts a fresh `agenthq.log` and shifts
the older ones up by one. At most **5 files of 10 MB each** are kept, and a file that reaches 10 MB mid-run rolls
the same way. A crash (an uncaught exception) writes its full stack trace there, under an `[agentHq-crash]` line.
Each launch's first line says when it started.

In the app: **Configuration** (the footer cog) > **Logs** shows the folder path and has an **Open logs folder**
button, which opens it in Explorer. The dev launches (`run`, `runRealDaemon`) keep printing to their console and
write no log file.

### What to attach to a bug report

1. `agenthq.log`, plus `agenthq.1.log` if you restarted the app after the problem. If unsure, zip the whole
   `logs` folder.
2. What you did, what you expected, what happened, and roughly when.
3. The installed version (Windows Settings > Apps > Installed apps > AgentHQ).

The logs can contain project paths and agent output (prompts and replies). Read them before sharing them outside
the team.

## Uninstall

Windows Settings → Apps → Installed apps → **AgentHQ** → Uninstall (or rerun the `.msi` and choose Remove).
This removes the program but **keeps your data**. To remove that too, delete `%LOCALAPPDATA%\SuperiorSoft\AgentHQ` (or your
`AGENTHQ_INSTALL_STATE_DIR`) after closing the app.
