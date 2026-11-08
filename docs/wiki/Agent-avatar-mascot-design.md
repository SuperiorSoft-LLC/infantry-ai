# Agent avatars from material-mascot: design (MASCOT-1)

Design record, 2026-11-08, ticket 13aed7dc. Nothing here is built yet. Where this page and the code disagree, the code wins.

Owner's request: use the material-mascot characters as agent avatars, maybe as an override for the orchestrator.

## 1. What material-mascot is

Source: github.com/maxmaster55/material-mascot (default branch `master`).

| Question | Answer |
|---|---|
| License | **MIT**, "Copyright (c) 2026 Max Master". The only condition is to keep the notice. MIT is compatible with Apache-2.0 (plugins) and MPL-2.0 (core), so we may bundle it if we keep the notice. |
| Provenance | The README calls it "a faithful Kotlin/Canvas port of bloub, the MIT-licensed SVG recreation of the x.ai Grok avatar". The code license does not cover any trade dress or trademark in the Grok look. This is an open owner decision (section 7). |
| What it ships | Code only, no image assets: one character drawn at runtime on a Compose Canvas (a ball with capsule eyes). It has 26 states (Idle, Listening, Thinking, Working, Responding, Alert, Happy, Confused, Sleepy, Notify, Sad, Greet, Reading, Proud and others), 3 finishes (flat, chrome, outline), 5 eye styles, an optional "cat" ears flag, and energy / motion / size knobs. The 29 SVGs under its docs folder are README previews only. |
| Framework | Kotlin Multiplatform plus Compose Multiplatform. The targets are **Android and JVM desktop only, with no wasm target**. It needs Kotlin 2.2+ and Compose MP 1.9.3+ (we are on 2.3.10 and 1.10.3). |
| Usage | You build a composable with a config: the state, a size in dp and a content description. For live apps a separate status bus maps "begin", "success" and "error" calls to Working, then a short Happy, then Alert, and to Sleepy after 10 s. |
| Distribution | No published artifact: no Maven Central, no releases. The README says to run publishToMavenLocal. The project has 0 stars, 0 forks and 25 commits, all from one author. |

Conclusion: we cannot take it as a Gradle dependency. It is not published, and it has no wasm target, while our web client compiles every UI plugin to wasm. We must **vendor (port) the drawing code** into our own plugin's `commonMain`, with its MIT notice. Users never fetch or install it separately.

## 2. How avatars are drawn today (checked in the code)

- **One composable draws every agent avatar.** It is `AgentAvatar` in
  `superiorSoftLibrary/infantryUiCore/src/commonMain/kotlin/com/superiorsoftllc/infantry/ui/AgentAvatar.kt`:
  the overload that takes a role is at line 61, and the overload that takes an `AgentView` (a live session) is at line 89. Its doc comment says it
  is "THE avatar of an agent, used by every site that shows one".
- **Identity is the role only.** `rolePortraitFor` (`superiorSoftLibrary/infantryUiCore/src/commonMain/kotlin/com/superiorsoftllc/infantry/ui/model/RolePortrait.kt:60`)
  maps a role id to one of 16 bundled PNG portraits (`RolePortrait`, compose resources `drawable/<key>.png`). An unknown role gets the
  custom portrait. The agent's name and id never enter it. While the PNG loads, or if it fails, the avatar is an initials disc on the
  role's catalog colour.
- **No session state reaches the avatar.** The `AgentView` overload passes only `agent.role`, `agent.id` and the catalog colour.
  Status rings and dots are drawn by the callers, around the avatar.
- **Decoded images are cached per portrait** in a process-wide map (`portraitCache`, line 115), so each PNG is decoded once.
- **Call sites.** All of them are in official plugins and call the core composable. None draws an avatar of its own:

| Site | File:line | Overload | Session known? |
|---|---|---|---|
| Sidebar agent list (40 dp) | `plugins/official/Infantry_Agents/src/Sidebar.kt:301` | AgentView | yes |
| Chat header (34 dp) | `plugins/official/Infantry_Chat/src/ChatSurface.kt:258` | AgentView | yes |
| Title-bar strip (22 dp) | `plugins/official/Infantry_Header/src/TitleBar.kt:228` | AgentView | yes |
| Chat speaker rows | `plugins/official/Infantry_ChatFeed/src/ChatFeedSurface.kt:679` | role | id only |
| Message origin chip | `plugins/official/Infantry_ChatFeed/src/OriginRenderer.kt:33` | role | id only |
| Board ticket card (16 dp) | `plugins/official/Infantry_Board/src/Board.kt:758` | role | no |
| New-agent role picker | `plugins/official/Infantry_Chat/src/NewAgentDialog.kt:302` | role | no (a role, not an agent) |
| Profiles | `plugins/official/Infantry_Profiles/src/ProfilesPane.kt:83` | role | no |
| Roles pane | `plugins/official/Infantry_Roles/src/RolesAttention.kt:91`, `:145` | role | no |
| Assistant entry | `superiorSoftLibrary/infantryUiCore/src/commonMain/kotlin/com/superiorsoftllc/infantry/ui/AssistantEntry.kt:44` | role | no |

- **Host-drawn or plugin-owned?** The avatar is **host-drawn**: the drawing and the 16 portraits live in UI core, and the plugins only
  place it. A plugin has no way to replace it today.
- **Dead or parallel code.** `AvatarSpec.imageUrl` (`model/Avatar.kt`) is always null. The Roles plugin's daemon half has a
  three-tier resolver (`AvatarResolver`: an operator override, then a bundled manifest, then a generated badge) with an upload store
  (`AvatarOverrides`). Its only caller outside tests is the debug probe `RoleCatalogProbe.kt`. The live UI does not use it.
- **The generic seam already exists.** It is `UiRegistrar.implementation(registry, key, value)`, declared under `implementations` in
  module.json and read through `LocalTabFactory.current.implementations.get(registry, key)`. The chat feed's row renderers use it
  (`FEED_RENDERERS`, `plugins/official/Infantry_ChatFeed/src/FeedRenderers.kt:33-37`), and so does the updater. The precedent for
  "pick one engine by a preference, with a built-in fallback" is the chat markdown engine (`ChatMarkdownRegistry.resolve`,
  `CHAT_MARKDOWN_PREF_KEY`).

## 3. Chosen design

Two parts, each in its own layer:

1. **A generic seam in UI core: an agent-avatar renderer registry.** This is a mechanism, not a feature. Core gains "an avatar
   style is a registered implementation, chosen by a preference, with the built-in portrait as the fallback". The 16 PNGs stay the
   default look.
2. **A separate official plugin, `Infantry_Mascot`** (UI half only, no daemon half). It registers a renderer under that registry. It
   holds the vendored MIT drawing code, the mapping from session state to mascot state, and its own config.

It is **not** an orchestration override. Avatars appear in surfaces that have nothing to do with orchestration (chat, sidebar,
header). Under the three-layer rule, agents and their presentation must not depend on the orchestration plugin. The board is one
more consumer of the core seam, so it picks up the mascot for free.

### 3.1 The seam (core, `infantryUiCore` commonMain)

The proposed shape (names are provisional):

```kotlin
// registry id, declared by a plugin under module.json "implementations"
const val AGENT_AVATAR_RENDERERS = "agent.avatar.renderers"
const val AGENT_AVATAR_PREF_KEY = "agentAvatarStyle"      // value = renderer key; default "portrait"

/** Everything a renderer may use. Pure data; plugins never see AppState internals. */
data class AvatarRequest(
    val role: String?,            // identity (as today)
    val label: String,            // fallback label (today's `fallback`)
    val catalogColor: String?,    // role colour from the catalog
    val sizeDp: Int,
    val state: String? = null,    // SessionState name when a session is known, else null
    val busyDetail: String? = null, // e.g. "compacting", the last error kind; optional
    val motion: AvatarMotion = AvatarMotion.STATIC, // what the call site allows
)
enum class AvatarMotion { STATIC, LIVE }   // a site opts into animation; a list row never does

fun interface AgentAvatarRenderer { @Composable fun Draw(request: AvatarRequest, modifier: Modifier) }
```

- `AgentAvatar` keeps its two signatures, so no call site breaks. It builds an avatar request, reads the preference, looks up
  the renderer, and draws it. A missing key, a disabled plugin or a thrown error falls back to the built-in portrait renderer.
  The guard is `GuardedBlock` or `PluginFaults`, so a faulty renderer is reported and not fatal.
- The `AgentView` overload fills `state` from `agent.session.state` (`SessionState`: STARTING, BUSY, WAITING_INPUT, IDLE, EXITED,
  ORPHANED, FAILED) and `busyDetail` from `isCompacting` / `lastError`. It sets motion to live only at the three "focus" sites
  (sidebar, chat header, title bar). Every other site stays static.
- State crosses the seam as a **string**, not as the enum, so a renderer plugin does not have to break when a new state is added.
  An unknown state draws as idle.
- The built-in renderer is today's code: portrait PNG, initials fallback, and `portraitCache`. It ignores `state`.
- The preference is a UI preference, like the markdown engine. The Appearance plugin
  (`plugins/official/Infantry_Appearance/src/AppearanceModule.kt`) shows a picker filled from the registry's keys. Its default is
  `portrait`, the current look at zero extra cost.

### 3.2 The mascot plugin (plugins/official/Infantry_Mascot, Apache-2.0 plus a vendored MIT folder) <!-- docs-freshness: ignore -->

- **Vendored code.** The drawing code is ported into the plugin's `src` folder, for example src/vendor/mascot/. Each file keeps its MIT
  header, and a NOTICE file names the source and the commit. Only Compose Canvas, animation and geometry APIs are allowed, so
  that it compiles for wasm (WasmCommonMainGuardTest applies). `scripts/license-paths.txt` gets an MIT entry for that folder,
  so LicenseMapGuardTest stays green.
- **Registers** `implementation(AGENT_AVATAR_RENDERERS, "mascot", MascotAvatarRenderer)` and declares it in module.json.
- **Mapping from agent to mascot.** The default mapping is data in the plugin's config. Users can edit it:

| Session state | Mascot state |
|---|---|
| STARTING | Greet |
| BUSY | Working (Reading while compacting) |
| WAITING_INPUT | Notify |
| IDLE | Idle |
| EXITED | Sleepy (Happy for 3 s after a successful finish, if motion is live) |
| ORPHANED | Confused |
| FAILED, or a `lastError` | Alert |
| no session (role-only sites) | Idle, static frame |

- **Per role and per agent.** The library has one character, so roles differ by **colour** (the role's catalog colour, as today) and by
  look. A config map gives role id → {eyes, finish, cat}, with a deterministic default from a hash of the role id, so 16 roles
  look different with zero setup. A per-agent override is not needed for v1, because the agent's identity is its role (M2-1).
- **Config** (module.json `config`, so the cheapest choice is the default):
  - `mascot.motion`: `static` (default: draws the frame for the current state and changes pose on a state change, with no idle
    loop), `focus` (loops only at sites that allow live motion), `all` (loops everywhere; costly).
  - `mascot.stateMap`: the table above.
  - `mascot.roleLooks`: the per-role overrides.
- **Install and disable on their own.** The plugin is a normal module that can be enabled, disabled or hot-swapped. Disabling it removes the
  registry key, and the preference falls back to the portrait without user action. The portrait PNGs never move.

### 3.3 Platforms and cost

- **Desktop, Android, wasm.** The vendored code lives in commonMain, so all three clients compile it in (D6/D9: wasm and Android
  take plugins at compile time; desktop can also load the jar at runtime). wasm is the risk: the upstream library never
  targeted it, so the port ticket must prove `compileKotlinWasmJs` and draw once in the web client.
- **Size.** The plugin has no image assets, only code, an estimated few hundred KB of bytecode or wasm. That is smaller than the 16 PNGs it would
  replace on screen.
- **CPU.** A static frame is one Canvas draw per recomposition, as cheap as an initials disc. A looping animation runs an
  infinite transition per visible avatar, which means a frame callback for every avatar on screen. A chat with 200 speaker rows must
  never loop, which is why live motion is chosen by the call site and capped by `mascot.motion`. The perf ticket checks it with the
  UI perf guard (PerfGuardTest in `infantryUi`).

## 4. Rejected alternatives

| Alternative | Why not |
|---|---|
| An override in the orchestration plugin (the owner's first idea) | Wrong layer. Avatars are drawn by chat, sidebar and header, which are not orchestration. Agents must not depend on orchestration, and the board already consumes the same core seam. |
| A shell block override (`shell` / `BlockOverride`) per site | Avatars are not shell nodes. There are 11 call sites inside other plugins' drawing code, and overriding each would be 11 hacks. |
| A child slot (SLOTS-1, `ChildSlot`) | A slot stacks panel sections. It does not swap a renderer, and it has no input data such as role, size or state. The split-into-child-plugin recipe is for panels, not for this. |
| Wire the daemon's `AvatarResolver` (images served from the daemon) | The mascot is drawn in code, not stored as an image, so the resolver cannot carry animation or state. The resolver is also not wired to the UI today. It can stay for uploaded images later, as a renderer of its own (section 7). |
| Export the 26 preview SVGs as bundled art | They are README previews: static, a single look, and no per-role variety. It also bundles the Grok-like look without the code's flexibility. |
| A Gradle dependency on the library | It is not published (mavenLocal only, so not reproducible), has no wasm target, and has a single author with no releases. |
| Call the mascot directly from `AgentAvatar` | That puts a plugin feature into core. The no-plugin-in-core guards would rightly fail. |

## 5. Expensive to reverse

- **The shape of the avatar request.** Once third-party renderers exist, removing or retyping a field breaks them. Adding fields with
  defaults is safe. That is why state is a string and the request is plain data.
- **The registry id and the preference key.** Both are persisted in user preferences and in plugin manifests.
- **Shipping the Grok-derived look as an official plugin.** Taking a public look back after launch costs more than not shipping it.

## 6. Ticket breakdown (backlog; runners first)

Each ticket names its check. The product tickets use stack-neutral wording.

1. **AVATAR-SEAM-1 (core, medium weight).** Add a pluggable agent-avatar renderer to the UI core: a named implementation
   registry, a plain-data request (role, label, colour, size, optional session state as a string, motion hint), a UI preference
   that picks the renderer (default: the built-in portrait), fault-guarded fallback to the built-in, and the live-session overload
   filling state. All existing call sites keep compiling unchanged.
   Acceptance: `grep -rn "agent.avatar.renderers" superiorSoftLibrary/infantryUiCore/src/commonMain` has a hit;
   `grep -rni "mascot" superiorSoftLibrary/` has none; a new UI test registers a fake renderer, sets the preference and finds its
   tag; with no plugin the portrait key semantics still resolve (AgentPortraitUiTest, ChatAvatarUiTest, ChatAgentPortraitUiTest
   green); `:superiorSoftLibrary:infantryUiCore:compileKotlinWasmJs` passes.
2. **AVATAR-SEAM-2 (official plugins, low weight).** Call sites that know a session pass it: the chat speaker rows and the origin chip
   pass state when the session is in the app state, and the sidebar, chat header and title bar pass live motion. All others stay
   static. Acceptance: `grep -rn "AvatarMotion.LIVE" plugins/official` shows exactly the three focus sites.
3. **AVATAR-PICKER-1 (official Appearance plugin, low weight).** An "Avatar style" picker listing the registered renderer keys,
   saved to the preference. An unknown or removed key shows as the default. Acceptance: `grep -rn "agentAvatarStyle"
   plugins/official/Infantry_Appearance` has a hit; a UI test switches the style and sees the fake renderer.
4. **AVATAR-DOCS-1 (docs, low weight).** Document the registry, the request and the fallback in `docs/wiki/plugin-api.md`, plus a
   recipe "add an avatar style". Acceptance: DocsFreshnessGuardTest green; `grep -n "agent.avatar.renderers" docs/wiki/plugin-api.md`.
5. **MASCOT-2 (new official plugin, medium weight; blocked by 1 and by owner decision 7a).** `Infantry_Mascot`: port the
   material-mascot drawing code into the plugin's common source set with its MIT notice and a NOTICE file, register the renderer,
   config fields motion (default static), stateMap and roleLooks. Acceptance: `grep -n "agent.avatar.renderers"
   plugins/official/Infantry_Mascot/module.json`; `grep -rn "SPDX-License-Identifier: MIT" plugins/official/Infantry_Mascot/src`
   matches only the vendor folder; LicenseMapGuardTest, WasmCommonMainGuardTest and the plugin's compileKotlinWasmJs are green;
   a unit test covers every state in the default state map, plus an unknown state drawing as idle.
6. **MASCOT-3 (perf and QA, low weight; after 2 and 5).** With the mascot style and motion `focus`, measure a chat of 200 rows plus 20
   sidebar agents against the UI perf budget. With motion `all`, record the cost in the setting's description. Acceptance:
   PerfGuardTest green with the mascot enabled; the setting description states the measured cost.

Order: 1 → 2 → 3 → 4 (the seam is useful on its own, for uploaded-image avatars too), then 5 → 6 once 7a is answered.

## 7. Risks and open owner decisions

- **7a. The Grok-derived look (owner decision).** The code is MIT, but it recreates the x.ai Grok avatar. Options: ship it as an official
  plugin, ship it as a community plugin that is off by default, or use only the seam and draw an original character in the
  same style. Recommendation: **community plugin, off by default**, until the look is cleared. The seam (tickets 1-4) does not depend on
  this answer.
- **7b. Default motion.** Recommendation: `static` (rule 5, the cheapest choice is the default). The owner may want `focus` to
  show the feature off.
- **Upstream risk.** Single author, no releases. We vendor a fixed commit and own the port from then on. Upstream fixes will not
  flow in on their own.
- **wasm port risk.** The upstream library never compiled for wasm. If some API is JVM-only, ticket 5 swaps it for a common one. If the
  port fails, the web client keeps the portrait, because the registry falls back.
- **Uploaded avatars (follow-up, not in scope).** The Roles daemon's `AvatarResolver` and `AvatarOverrides` could become a third renderer
  ("uploaded"), using the same seam. That would retire the dead `AvatarSpec.imageUrl`.
