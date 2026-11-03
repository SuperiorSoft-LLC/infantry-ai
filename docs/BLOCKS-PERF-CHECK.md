# BLOCKS perf check (QA ticket 98c61dd1)

Is "same performance after the block splits" true? This puts numbers on it. Measurement only: no app code changed.

## What was compared

| side | commit | what it is |
|---|---|---|
| base | `9b7c2ce5` | first parent of `af67ce03` (the BLOCKS-LAYOUT L1 merge): the last dev commit before the block splits |
| dev  | `5aa26850` | dev head (feature-agenthq-mvp) when this ran |

Both ran on the same machine (AMD Ryzen 7 5700G, Windows, about 9 GB RAM free), one after the other, one Gradle job at a time, `--max-workers=2`.

## How

- The harness is `docs/blocks-perf/PerfMain.kt.txt`. It is a copy of the TabShotsMain setup: an in-process stub daemon with temp state, the repo's `plugins/` root, a 1400x860 headless `runDesktopComposeUiTest`, and `infantry.ui.blur=off`. On dev it also uses the fixed 8 GB MemorySource and the pinned shot prefs. Neither exists at base, where `//DEV:` turns those lines off. Base also cannot draw a low-memory row, because its daemon has no memory hook.
- It ran through `runTabShots`, with `docs/blocks-perf/perf.init.gradle` swapping in the main class. That gives the same classpath, JVM caps (`-Xmx512m`), plugins root and env. The build files were not touched.
- Each step's time is wall-clock from the action (click / `setContent`) until its target tag appears **and** `waitForIdle()` returns. That covers composition, layout and the frame. Capturing the drawn window to an image afterwards cost about 1.5–2 ms on both sides, so it is not a factor.
- Each JVM ran: first draw, connect, spawn one stub agent, then 6 passes of chat→Git→chat, board open/close, New agent open/Cancel. Pass 1 is "cold" and passes 2–6 are "warm" (median per JVM). Then Configuration → Agents → Manage roles, once per JVM.
- Each side got 1 discarded warm-up JVM and then **5 measured JVMs**. The table shows the median of the 5 per-JVM values, with min–max as the spread.
- Node counts are taken after each step: semantics nodes (unmerged) / layout nodes (LayoutNode tree, read by reflection).
- Scripts are in `docs/blocks-perf/` (`run.sh`, `agg.py`) and raw output in `docs/blocks-perf/raw/`.

## Results (ms)

| step | base median (min–max) | dev median (min–max) | delta | nodes sem/layout base → dev |
|---|---|---|---|---|
| plugin load (`DesktopPlugins.load`, not UI) | 226 (220–288) | 436 (426–552) | **+93%** | – |
| first draw | 1471 (1433–1550) | 1512 (1484–1524) | +2.8% | 136/228 → 137/229 |
| cold chat→Git | 135 (128–146) | 138 (135–144) | +2.0% | 172/299 → 172/299 |
| cold Git→chat | 85 (77–96) | 94 (90–107) | +10.4% | 197/345 → 197/345 |
| cold board open | 117 (108–129) | 125 (122–156) | +6.8% | 241/410 → 241/410 |
| cold board close | 25.6 (25–32) | 29.2 (28–37) | +14.1% (3.6 ms) | 197/345 → 197/345 |
| cold New agent open | 270 (256–278) | 287 (277–327) | +6.6% | 295/488 → 281/474 |
| cold New agent close | 34.6 (32–39) | 35.2 (32–42) | +1.7% | 197/345 → 197/345 |
| warm chat→Git | 51.5 (48–61) | 61.7 (52–67) | **+19.8%** (10 ms) | 172/299 → 172/299 |
| warm Git→chat | 64.1 (61–67) | 69.0 (62–73) | +7.6% | 197/345 → 197/345 |
| warm board open | 76.9 (71–81) | 71.1 (66–84) | −7.5% | 241/410 → 241/410 |
| warm board close | 22.1 (22–23) | 20.5 (20–24) | −7.2% | 197/345 → 197/345 |
| warm New agent open | 131.7 (121–135) | 125.1 (120–143) | −5.0% | 295/488 → 281/474 |
| warm New agent close | 23.5 (22–26) | 24.2 (22–27) | +3.0% | 197/345 → 197/345 |
| Configuration open | 171.5 (157–186) | 194.7 (165–203) | +13.5% | 269/466 → 273/474 |
| Config → Agents tab | 149.0 (137–170) | 154.5 (140–180) | +3.7% | 310/553 → 313/560 |
| **Manage roles open** | 167.5 (157–187) | 215.9 (205–234) | **+28.9%** | 440/707 → 444/716 |

The node counts are essentially unchanged: the splits add at most 9 layout nodes on screen, and the New agent dialog has 14 fewer. Deep layering did **not** grow the UI tree.

## Verdict

- **Holds (within 10% or within noise):** first draw (+2.8%), tab switches as a whole, board open/close, New agent dialog (cold +6.6%, warm −5%), Config → Agents.
- **Regressed more than 10%, real (the ranges do not overlap):**
  - **Manage roles open +29% (+48 ms).** Likely layer: BLOCKS-LAYOUT **L12, the role-manager split** (`9efb09a9`, ticket 9dfbf408). The role manager moved into the `Infantry_Roles` module (`plugins/core/Infantry_Roles/RoleManager.kt`). S4c "roles migrated to ShellContext" (`0a4e8a12`) also sits on this path. The node count barely moved (+9), so the cost is more likely per-open work (resolving the block/override chain, ShellContext reads or a first-use class load) than more UI.
  - **Plugin load +93% (+210 ms, startup, before the first frame).** This is not a layering cost per frame: dev loads 19 core modules against 17 at base (`Infantry_AppUi` and `Infantry_Board` are new, from S4a/S4d and NS2). It is wall time before the window draws, so app startup is about 0.2 s slower overall.
- **Over 10% but borderline (the ranges overlap; may be noise at n=5):** warm chat→Git +20% (+10 ms, with the same node count). Likely layer: the **L14 Git pane split** (`3f204cf7`). Also Configuration open +13.5% (S3 ConfigDialog split `2e644336` / P1 config groups as tabs `cbaf1eee`), cold board close +14% (3.6 ms) and cold Git→chat +10.4%.

So the "costs ~nothing" claim holds for first draw, the board and the New agent dialog. It does **not** hold for the role manager (+29%) or startup plugin loading (+93%). Git tab switch and Config open are probably a little slower (10–20%).

## Not covered / caveats

- These are headless, CPU-rendered test frames (Skia software), not the GPU window the owner sees. The absolute ms are higher than in the real app; the relative deltas are what matter.
- n=5 JVMs per side. Swings of ±10% on the 20–60 ms steps are within what this host shows run to run.
- There is no per-layer bisection: the "likely layer" names come from the commit history and the code path, not from a measurement per commit. The next step would be to time roleManagerOpen at `9efb09a9^1` against `9efb09a9`.
- The board here is the empty-backlog board (no cards), and the role manager is its list view only.
