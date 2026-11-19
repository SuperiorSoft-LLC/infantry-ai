# Variant builds (feature branches such as feature-calendar)

> **legacy-java:** this section describes the Kotlin app. It lives on the `legacy-java` branch. The Rust framework on main is described in [Architecture](Architecture.md).

## Legacy (Kotlin) reference


A variant build packages a feature branch for the owner to try. It never touches the owner's app, daemon or database,
and it never goes out through the nightly (never push a variant branch to origin: a push triggers the nightly).

## Package it

```
powershell -File scripts/package-dist.ps1 -Sha <sha> -ReleaseBranch feature-calendar -Label calendar -DryRun
powershell -File scripts/package-dist.ps1 -Sha <sha> -ReleaseBranch feature-calendar -Label calendar
```

`-ReleaseBranch` names the branch the sha must be on. `-Label` (1-24 letters, digits, `-`) does two things: the folder is
`builds\<stamp>-<sha8>-<label>` under the same fixed `build.root`, and the build version becomes `<version>-<label>`
(a SemVer pre-release, for example `1.6.0-calendar+20261116.2300.ab12cd3`), so About, the daemon's `ready` build= and
BUILD-INFO.txt (`label=`) show it. Without `-Label` nothing changes.

## Run it as its own test instance

Never start it against the owner's state dir or ports 8787, 8788, 8797 or 8798.

1. Pick a free port in the 189xx range and a scratch state dir.
2. Start the exe with a clean environment: unset `INFANTRY_URL`, `INFANTRY_TOKEN`, `INFANTRY_HOME` and every `CLAUDE*`
   variable, then set `INFANTRY_STATE_DIR=<scratch dir>` and `INFANTRY_PORT=189xx`, and `INFANTRY_URL=http://127.0.0.1:189xx`
   for any CLI call against it.
3. Auto-update off: the variant version carries a pre-release and the instance has its own state dir, so it has no update
   source from the owner. Leave the updater source unset (or disable the updater setting) in the scratch state.
4. When done, close it by PID (never by image name: the owner's app shares the exe name) and delete the scratch dir.

To try it with the owner's data, copy the state dir aside (read-only copy, `package-dist.ps1 -BackupState`) and point
`INFANTRY_STATE_DIR` at the copy, never at the original.
