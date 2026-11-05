# Kotlin notes for the UI core

This page is for an agent that edits Kotlin code in `superiorSoftLibrary/infantryUiCore` or the build files. The
toolchain is newer than most model training data, so check the versions here and the catalog before you use an API
from memory. Where this page and the code disagree, the code wins.

## 1. Versions

`gradle/libs.versions.toml` is the source of truth. This table copies the main pins at the time of writing; if they
differ, edit the catalog and this table together.

| Tool | Version | Where it is pinned |
|---|---|---|
| Kotlin (and the Compose compiler plugin) | 2.3.10 | `kotlin` in `[versions]` |
| Compose Multiplatform | 1.10.3 | `composeMultiplatform` |
| Android Gradle Plugin | 9.0.1 | `agp` |
| kotlinx-datetime | 0.7.1 | `kotlinx-datetime` |
| kotlinx.serialization | 1.10.0 | `serialization` |
| kotlinx-coroutines | 1.10.2 | `kotlinx-coroutines` |
| Ktor (daemon build) | 3.4.1 | `ktor` and `ktorClientCore` |
| Ktor (catalog) | 3.4.1 | `ktor`, one version for every `io.ktor` module |
| SQLDelight | 2.2.1 | `sqldelight` |
| JDK | 21 | the Gradle toolchain resolves it |

## 2. Source sets in `infantryUiCore`

`infantryUiCore` is Kotlin Multiplatform. Its source sets live under `superiorSoftLibrary/infantryUiCore/src/`:

- `commonMain`: everything that is the same on every client. Compose UI, `Controller`, `DaemonClient`, `UiPlugin`,
  `UiRegistrar`, and the `expect` declarations that each platform must fill in.
- `jvmMain`: the desktop actuals (Compose Desktop). Also the JVM-only libraries, such as the desktop sound player.
- `androidMain`: the Android actuals. Android-only APIs (`android.view.PointerIcon`, camera and barcode scanning) go here.
- `wasmJsMain`: the browser actuals. Nothing that needs a JVM or Android class goes here.
- `commonTest` and `jvmTest`: the shared and desktop unit tests.

The rule: write the declaration once in `commonMain` as `expect`, then write one `actual` per platform that the build
compiles. If you add an `expect` you must add an `actual` in every source set that is compiled, or the build fails.

### Example: the platform name

`commonMain` declares the `expect` in `PlatformName.kt`:

```kotlin
expect fun platformName(): String
```

Each platform gives its own `actual`. The desktop one is in `jvmMain/.../PlatformName.jvm.kt`:

```kotlin
actual fun platformName(): String = "${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")}), desktop"
```

The browser one is in `wasmJsMain/.../PlatformName.wasmJs.kt` and returns `"Web browser"`. The Android actual is in
`androidMain/.../PlatformName.android.kt`. A second pair, `GrabIcon` in `Clickables.kt`, shows the same pattern with a
Compose type: the Android actual uses `android.view.PointerIcon.TYPE_GRAB`, the browser actual uses `PointerIcon.Hand`
because Compose has no grab cursor on the web.

## 3. Compose Multiplatform pitfalls in this repo

- **Browser has no grab cursor.** `GrabIcon` and `GrabbingIcon` fall back to the hand on wasm. Do not add a new cursor
  name in `commonMain` without an actual for every target.
- **Android-only and JVM-only APIs stay in their source set.** `android.view.*` and the desktop sound library must not
  leak into `commonMain`. The build will not compile the wasm target if they do.
- **Plugins compile in on wasm and Android.** The browser and Android clients cannot load a plugin at run time
  (decisions D6 and D9); their plugins are compiled into the build. Desktop loads them at run time (D5). Do not add a
  runtime class-loading path to a shared file.
- **Clickables use the hand-cursor wrappers.** Clickable elements in the UI use the wrappers in `Clickables.kt`
  (`handCursor`, `dragHandleCursor`, and the clickable and button helpers there), not a bare `Modifier.clickable` with no
  cursor. `HandCursorGuardTest` fails the build if a clickable skips them.
- **Resize handles use their own icon.** `ResizeHandle.kt` declares `HorizontalResizeIcon` as an `expect`; keep the
  same pattern for any new platform cursor.

## 4. Why incremental compilation is off

`gradle.properties` sets:

- `kotlin.incremental=false`, plus `kotlin.incremental.js=false`, `kotlin.incremental.wasmjs=false`,
  `kotlin.js.incremental=false` and `kotlin.js.ir.incremental=false`.
- `android.builtInKotlin=false`.

**No reason is recorded for either setting.** The repository history was rewritten into one initial commit, so
`git log -S` finds only that commit and cannot name the change that set them. The audit that raised this
(`docs/AI-FRIENDLY-AUDIT.md`, fix 5) notes the same gap. Until someone records the reason, treat these flags as
deliberate: do not flip them to speed up a build without a measured reason and a passing full gate. The
`kotlin.incremental.wasm=true` line is separate and is set to true.

## 5. The Ktor version skew

The catalog now points every `io.ktor` entry at one version, `ktor` (3.4.1), and `ktorClientCio` is gone. The daemon
build still pins every `io.ktor` module to that version as a guard against transitive drift. Check the version the
module actually resolves before you use a Ktor API.

## 6. Where to look next

- Versions and dependencies: `gradle/libs.versions.toml`.
- Build flags: `gradle.properties`.
- Architecture and layers: [Architecture](Architecture.md).
- Build and test commands: `AGENTS.md` section 3.
