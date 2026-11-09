[← Wiki index](index.md)

# The `infantry` command from any terminal

<!-- shared:pitch -->Infantry AI is a command post for coding agents: a ticket board, agents on their own git branches, mail, shared project knowledge, Lua scripts that automate your process, and plugins that build the app itself.<!-- /shared:pitch -->

CLI-PATH-1. After a normal install, typing `infantry` in a **new** terminal works from any folder: no `cd` into the
app folder, no full path to a `.bat` or `.sh`. This page says what puts it there, on each platform, and how to take it
off again.

## The two halves

1. **The launcher ships with the install.** `bin/infantry` + `bin/infantry.bat` from `installDist` and the headless
   server archive, and `<install>\app\resources\infantry.cmd` (plus an `infantry` sh shim for Git Bash, and
   `infantry.ps1` where PowerShell may run it) in the desktop app. Every one of them runs
   `com.superiorsoftllc.infantry.cli.CliMainKt` on that install's own classpath. `agenthq` is the deprecated alias of
   the same launcher and ships beside it.
2. **That folder goes on the user's PATH.** `install-cli` / `uninstall-cli` do it by hand, and the installed desktop
   app does it for you at every start (`paths.addCli`, on by default).

## Commands

```
infantry install-cli            # put this launcher's folder on YOUR user PATH (idempotent)
infantry install-cli --dry-run  # show what it would write, write nothing
infantry install-cli --dir <folder>     # a launcher folder other than this CLI's own
infantry uninstall-cli          # take every Infantry AI entry off again
infantry health                 # the "CLI:" line: on the PATH, where, and whether it still resolves
```

Both commands work with **no daemon running**: they edit this machine's environment and nothing else. Each prints a
status line and, when it wrote something, the note `Open a NEW terminal: a terminal that is already open keeps the PATH
it started with.`

## What it writes, per platform

| Platform | Where the entry goes | How it is removed |
|---|---|---|
| Windows | the **user** environment: `HKCU\Environment`, value `Path` (never the machine PATH, so no administrator), then a `WM_SETTINGCHANGE` broadcast so new shells and Explorer re-read it. The value's type is kept (`REG_EXPAND_SZ` stays expandable, so another tool's `%USERPROFILE%` keeps working). | `uninstall-cli`. The app also removes it at its next start when `paths.addCli` is off. |
| macOS / Linux | one marked block in the user's own shell profile — `~/.zshrc` for zsh (the macOS default), `~/.bashrc` for bash on Linux, `~/.bash_profile` for bash on macOS, else `~/.profile`: `# >>> Infantry AI CLI (infantry install-cli) >>>` / `export PATH="<launcher dir>:$PATH"` / `# <<< Infantry AI CLI <<<`. Every other line of the file is left as it was. | `uninstall-cli` deletes the block. |
| A container, CI, or anything whose PATH is set some other way | nothing, unless you ask: `INFANTRY_CLI_PATH_FILE=<file>` writes the entry into that file instead. The Docker image already has `PATH=/opt/infantry/bin:$PATH`, so `infantry` is there without any of this. | delete the file, or the variable. |

Never written: the system (machine) PATH, `/etc/*`, or any file another tool owns.

## Ownership: which entries are ours

An entry is Infantry AI's when **the folder holds one of its launcher scripts** (a launcher file that starts
`CliMainKt`), or when an earlier write recorded it (on Windows: a second value beside `Path` in `HKCU\Environment`;
for the `INFANTRY_CLI_PATH_FILE` store: a sibling `.ours` file). That is what makes the two commands safe and repeatable:

- running `install-cli` twice changes nothing the second time;
- an entry that pointed at the folder an **update moved** is replaced, not duplicated (the record still names the old
  folder after it is gone, so a dead entry is recognised and removed);
- a folder that only looks like a launcher folder (a file named `infantry.cmd` that does not start this app) and
  another product's stale entry are never touched.

## The setting

`paths.addCli` (app scope, default `true`) is the installed app's promise: at every start it makes sure the user PATH
holds its launcher folder, which is also how the entry survives an update (an upgrade uninstalls the old version first).

```
infantry settings get paths.addCli
infantry settings set paths.addCli false   # the next start removes the entry instead of adding it
```

## What could not be verified without a real installer run

The Windows MSI is built by jpackage through Compose Desktop, and Compose gives the build no way to add a WiX source
(`--resource-dir` is its own temporary folder, cleared inside the packaging task; jpackage only lets a resource dir
override `main.wxs`/`overrides.wxi`, and `overrides.wxi` is preprocessor variables only). So the PATH entry is **not** an
MSI `Environment` row: it comes from the app's own first start. What that means for a real install:

- install the MSI, start Infantry AI once, open a **new** terminal, type `infantry` — it should answer;
- uninstall without starting the app again: the entry stays until `infantry uninstall-cli` (or the next install) — it
  points at a folder that no longer exists, which Windows and POSIX shells skip.

## Related

- [Settings](Settings.md): scopes and what each setting costs.
- [Health](Health.md): `infantry health`, including the `CLI:` line.
