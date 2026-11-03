# Build speed (BUILD-SPEED-1)

Measurement only. No build file was changed. Build target: `:superiorSoftLibrary:infantryUi:createDistributable`.
Source: detached worktree at `a8ce8a21` (dev tip at measurement time), `-Pihq.devSign=true`, `-PallowAnyBranch=true`
(the packaging guard refuses a detached HEAD that is not `feature-agenthq-mvp` or a `v*` tag; the sha is on
`feature-agenthq-mvp`). Host: 16 logical cores, 32 GB RAM, about 8.9 GB free at start (see the RAM note below).

## Results

| Run | Setup | Wall time | Cache hits |
|---|---|---|---|
| 1. cold | `--no-build-cache`, fresh worktree | **629 s (10 min 29 s)** | none |
| 2. populate | `--build-cache`, fresh worktree, fills the local cache | 619 s | 15 |
| 3. warm | `--build-cache`, second fresh worktree, same local cache | **298 s (4 min 58 s)** | 75 tasks FROM-CACHE; 273 tasks still executed |

Warm versus cold: **-331 s (about 5.5 min, -53%)**.

Run 1 is a real cold build (no cache hits, all tasks executed). Run 2 is the first build with the cache enabled and
behaves like a cold build with cache writes. Run 3 is the warm case: a new worktree, the same `GRADLE_USER_HOME`
local cache, and no daemon reuse (`--no-daemon`, as the packaging script uses).

### Where the time goes (cold run 1)

- Gradle configuration of all projects: about 8 s. Startup and settings about 5 s.
- `infantryWeb` (wasm web module, a dependency of the desktop dist, see below): `compileKotlinWasmJs` about 35 s
  (profile figure), `assembleWeb` itself about 0.3 s when its input is already compiled.
- The longest single tasks in the profile are Kotlin compiles of plugins and of the UI: `infantryUi:compileKotlin`
  about 57 s; the plugin `compileKotlinJvm` and `compileKotlinWasmJs` tasks each about 45-56 s.

Caveat: the profile's per-task durations overlap and do not add up. Their sum is about 3,115 s against a 629 s wall
clock, so the ranking above is indicative. Use the wall-clock totals in the table for decisions.

### What is not cached

In the warm run, 273 tasks still executed. They include the `jar` / `jvmJar` tasks, `createRuntimeImage` (jlink), the
`kmpPartiallyResolvedDependenciesChecker` tasks, resource generation (`generate*ResourceCollectors`,
`generateComposeResClass`), and the `processResources` tasks. The Compose `createDistributable` / jpackage step itself is
not a cacheable task. Those are the floor of a warm build (about 5 min here).

## The wasm web module is part of the desktop dist

`superiorSoftLibrary/infantryUi/build.gradle.kts` (WEB-1, around lines 470-474) makes `prepareAppResources` depend on
`:superiorSoftLibrary:infantryWeb:assembleWeb` and copies its output into the app's `web/` resource folder. The installed
app's in-process daemon serves that browser client at `/`. So the desktop dist needs the wasm module.

**Skipping wasm for the desktop dist is not a safe speed-up.** It would drop the browser client. It has not been
proven either way: the app image was not run without it (not done here, out of scope for a measurement ticket).

## Speed-ups, ranked by gain (estimates)

| # | Setting / change | Measured or estimated saving | Risk | How to turn it on |
|---|---|---|---|---|
| 1 | Shared local build cache across worktrees | **Measured: -331 s (-53%)** on a second worktree | Low | Already on in `gradle.properties` (`org.gradle.caching=true`); the packaging script does not pass `--no-build-cache`. The cache lives in `GRADLE_USER_HOME`, so every worktree on the same machine shares it. Keep the same `GRADLE_USER_HOME` for all builds. Nothing to add to the repo. |
| 2 | Keep the Gradle daemon warm (drop `--no-daemon`) | Estimated: the JVM start and Gradle startup, roughly 20-40 s per build | Medium: the packaging script forks a single-use daemon because `-Xmx3g` is passed as a JVM arg; a warm daemon has to match those args | Not measured. Needs the same JVM args in a long-lived daemon; memory cost stays with the daemon while idle. |
| 3 | Parallel build (drop `--no-parallel`), `org.gradle.parallel=true` is already the default in `gradle.properties` | Not measured. 16 cores would help the independent plugin compiles | **High on this host**: about 8.9 GB free RAM at start, and the Gradle test JVMs have crashed from RAM exhaustion before (see the knowledge base). Keep `--no-parallel` unless the host has more free RAM | Pass `--parallel` per run; do not change the script default without a RAM check first. |
| 4 | Kotlin daemon memory (`kotlin.daemon.jvmargs=-Xmx3072M`) | Not measured. The compile tasks dominate the cold run, so more heap may help GC pressure | Medium: the sum of Gradle, Kotlin and test JVMs competes for the same RAM | `kotlin.daemon.jvmargs` in `gradle.properties`, sized to free RAM. Measure first. |
| 5 | Configuration cache (`org.gradle.configuration-cache=false` today) | About 8 s (configuration of all projects) | Medium: the build has many plugin modules and custom tasks that may not be compatible | `org.gradle.configuration-cache=true` in `gradle.properties`, then check the build reports. Low gain, so last. |
| 6 | Skip the wasm web module for the desktop dist | Estimated: about 35 s of wasm compile plus the wasm plugin compiles that the web client does not need | **High: removes the browser client from the desktop app** | Not recommended until the app image is run without `web/` and the owner accepts that. |
| 7 | Warm scoped-test worktree | Not measured: the ticket asked for one scoped test class timing, but the rules for this ticket say no tests are run | Low | Not measured. |

## Commands (reproduce)

```powershell
# cold: fresh detached worktree at the sha, no build cache
git worktree add --detach $env:TEMP\ihq-speed-cold <sha>
Set-Location $env:TEMP\ihq-speed-cold
.\gradlew.bat ':superiorSoftLibrary:infantryUi:createDistributable' '--no-parallel' '--no-daemon' '--no-build-cache' '--profile' '--console=plain' '-Pihq.devSign=true' '-PallowAnyBranch=true' '-Dorg.gradle.jvmargs=-XX:MaxMetaspaceSize=2g -Xmx3g'
# warm: a second fresh worktree with the build cache on (the cache from the first run is reused)
git worktree add --detach $env:TEMP\ihq-speed-warm <sha>
Set-Location $env:TEMP\ihq-speed-warm
.\gradlew.bat ':superiorSoftLibrary:infantryUi:createDistributable' '--no-parallel' '--no-daemon' '--build-cache' '--profile' '--console=plain' '-Pihq.devSign=true' '-PallowAnyBranch=true' '-Dorg.gradle.jvmargs=-XX:MaxMetaspaceSize=2g -Xmx3g'
# profile report: build/reports/profile/profile-*.html in the worktree
```

Remove the temporary worktrees after use: `git worktree remove --force <path>`.

## Not measured, and why

- Scoped test-class timing (cold and warm): the ticket's rules for this measurement say no tests run beyond what the build
  itself runs, so it was skipped.
- Build scan: not generated; no upload was done.
- Owner state directory and ports 8797/8798/8787: not touched. Nothing was run against them.
- Running the app image without `web/`: not done (see the wasm section).

## RAM note

About 8.9 GB free RAM was observed at the start of the cold run, out of 32 GB. A Gradle/Kotlin-family java process from
another session was already running (left alone). The packaging script's `-Xmx3g` plus the Kotlin daemon's `-Xmx3072M`
leave little headroom, so parallel builds and larger heaps should wait for more free RAM.
