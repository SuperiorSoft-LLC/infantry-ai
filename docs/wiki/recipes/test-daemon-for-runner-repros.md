[← Recipes](README.md)

# Start a test daemon (and a second runner daemon) for live repros

There is no `infantry daemon` command: the CLI only talks to a daemon. The daemon is the main class
`com.superiorsoftllc.infantry.daemon.DaemonMainKt` (`--mode both|runner|orchestrator`, also `INFANTRY_MODE`).
`scripts/testdaemon.sh` starts it from a built dist the safe way. Nothing is proven until it ran live on a test daemon.

## Build once

```
./gradlew :superiorSoftLibrary:infantry:installDist --console=plain
```

The script reads `superiorSoftLibrary/infantry/build/install/infantry` by default; `--dist <dir>` takes any
`installDist` folder (with a lib folder) or packaged dist (with app and runtime folders).

## Up, use, down

```
scripts/testdaemon.sh up  --name pc --mode both
scripts/testdaemon.sh up  --name rn --mode runner
scripts/testdaemon.sh cli --name rn -- runner pair --name rn1 --host 127.0.0.1
scripts/testdaemon.sh cli --name pc -- runner add 'infantry://runner?v=1&...'   # the whole link, quoted
scripts/testdaemon.sh cli --name pc -- runner list
scripts/testdaemon.sh down --name pc ; scripts/testdaemon.sh down --name rn
```

What `up` does for you (the traps from the owner's test-daemon notes):

- Scrubs every `INFANTRY_*`, `AGENTHQ_*` and `CLAUDE*` variable, then pins `INFANTRY_STATE_DIR`, `INFANTRY_HOME`,
  `INFANTRY_PORT` and `INFANTRY_PORT_STRICT=true`; a fresh short temp state dir (`td-XXXXXX`), never the owner's.
- Picks free ports in 18900-18999, skipping ports another test daemon holds. 8787, 8788, 8797, 8798 are refused.
- Pins the runner link port (`limits.runners.linkPort`, default 8790) through `INFANTRY_LIMIT_RUNNERS_LINK_PORT`, else a
  runner would listen on the owner's runner port.
- Sets `-Dinfantry.plugins.devTrust=true` (unsigned jars load).
- Waits on `/health` up to `--timeout` seconds (default 90). A dead process or a timeout prints the last log lines and
  exits 2: it never waits silently.
- Prints port, link port, pid, state dir and the owner token file (`<state>/owner.token`).

`cli` pins `INFANTRY_URL` to that daemon's port and the state dir, so the CLI presents that daemon's owner token.
`down` kills by PID only (`taskkill /PID`), never by image name: the owner's app shares the exe name.

## Known quirk

`runner add` prints `runner 'rn1' paired ...` and the pairing is stored, but the CLI process may not exit afterwards
(seen on Windows with the dist above). Check with `cli ... runner list`, and run `add` in the background.

## Live output (2026-11-08, installDist, Windows)

```
up name=pc mode=both port=18904 link_port=18905 pid=45048 state=C:/Users/.../Temp/td-V3XNSY
up name=rn mode=runner port=18906 link_port=18907 pid=31184 state=C:/Users/.../Temp/td-QH7MJM
runners.enabled was off; turned on
runner 'rn1' paired (wss://127.0.0.1:18907); runner id inf-tqmZjnTMs0ZY
this machine: inf-GgPeAoR-RPeC
runner rn1 | wss://127.0.0.1:18907 | inf-tqmZjnTMs0ZY | credential issued 2026-11-08 13:49 -05:00
down name=pc pid=45048 port=18904
down name=rn pid=31184 port=18906
```
