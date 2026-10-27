# BLOCKS-INJECTORS: additive extension of a node's children (design only)

Ticket 90588545. Base: `feature-agenthq-mvp` @5a1098d. Read-only check plus design; no code changed.

Owner ask (2026-10-27): the CMS client's "block injectors" **add** blocks into a section, as a second extension method
next to override. Replace and wrap allow one winner per node. Injection lets several plugins add to the same node
without conflict.

## 1. What exists today

**Short answer: no. The resolver has no additive mechanism for shell nodes.** `BlockResolver.resolve`
(`ui/block/layout/Resolver.kt`) takes a single input, `List<NodeOverride>`, with the modes `replace | wrap`
(`OverrideModes.ALL`). Every override folds into the node itself (`OverrideBlock(inner)`). Nothing ever appends to a
node's `children`.

Five things look close. None of them is a shell-node injector:

| mechanism | where | additive? | why it is not the injector |
|---|---|---|---|
| `Slots` + `SlotBlock` (`module.json blocks[{slot,name,order}]`) | `TabFactory.kt:62`, drawn in `TabFactory` tab render | **yes**: many modules per slot, sorted by `order` | only 4 fixed slots (`center.top/bottom`, `right.top/bottom`) around tab content, outside the block tree. A slot is a named hole the core has to pre-cut. It is the CMS "region" model that the BLOCKS-LAYOUT "Impact on LAYOUT-1" section retired. |
| tab registration (`TabProvider`, `order`, `parent`) | `TabFactory.init` | yes: many tabs per container | it adds tabs to a strip, not blocks to a node |
| panel sections (`PanelProvider`, `Panels.TABS_ORDER`) | `PanelSections` | yes: sections above or below the right panel's tab strip | only one panel, and outside the tree (`RightPanelContent` draws them around `RenderPlaced(area)`) |
| interceptors (`before`/`after`/`around`) | `InterceptorKinds` | before/after are additive | per tab, not per node |
| `withChildren` hosts (`ImageViewerHostBlock`, `TitleBarBlock`, `GitPaneBlock`, `RoleFormBlock`, `BoardBlock`, `StatusBarBlock`, ...) | `shell/blocks/*` | **the hook is there** | every host renders `RenderPlaced(children)` / `RenderPlain(children)` generically, and the resolver already rebuilds a parent through `withChildren` when a child changes. Nothing else hands a host a longer child list. |

The intent is already written down but not built:
- BLOCKS-LAYOUT section 6 lists the ops `before | after | into` and says that "inserting before/after or into [locked nodes]
  is still allowed".
- The `CoreShell.locked` comment says "children stay insertable once inserts land".
- 6a says "`remove | before | after | move | into | place` come later on the same resolver".

The injector is that "later". **Section 2 tightens the locked rule, as the owner asked, and this replaces the section 6 bullet.**

Two current details matter for the design (both checked in code):
- **`rightPanelArea` (`ShellRightPanel.kt:118`) maps `RightPanelBody.children`, which is the core list, and looks each
  one up by id.** Something injected into `infantry.shell.right.body` would draw on the docked panel but not in the
  compact drawer. It must read `root.find(BODY).children` instead. Step J2 fixes this.
- **Every host's context local is `private`**: `LocalGitPaneHost`, `LocalRoleFormDraft`, `LocalBoardHost`,
  `LocalAssistantHost`, `LocalDiffHost`, `LocalChatPanelHost`, `LocalNewAgentDraft`, `LocalRoleManagerNav`. Today an
  injected child gets only `BlockScope`, `LocalBlockSize` and its module's `PluginServices`. Section 2e covers this.

## 2. The smallest injector

### 2a. Contract

```kotlin
// ui/block/layout/Resolver.kt, next to NodeOverride
sealed interface InjectAt {
    data object First : InjectAt
    data object Last : InjectAt
    data class Before(val childId: String) : InjectAt
    data class After(val childId: String) : InjectAt
}

/** What a module registers: placement and drawing. The host gives it its id. */
class BlockInjection(val place: Place = Place(), val content: @Composable (scope: BlockScope) -> Unit)

/** One injection, already through the trust rules (the loader's allowInject). */
class NodeInjection(val module: String, val name: String, val target: String, val at: InjectAt, val order: Int, val loadIndex: Int, val injection: BlockInjection)

/** The node drawn for an injection: a leaf with id `<module>.<name>`. */
class InjectedBlock(val module: String, id: String, place: Place, private val content: @Composable (BlockScope) -> Unit) :
    Block("injected", id, place) { @Composable override fun Render(scope: BlockScope) = content(scope) }

object BlockResolver {
    fun resolve(root: Block, overrides: List<NodeOverride>, injections: List<NodeInjection>, locked: Set<String>, tree: String = ...): Resolved
}
```

- module.json:
  `"inject": [{ "into": "<node id or infantry.shell#id>", "name": "<registered>", "at": "first|last|before:<id>|after:<id>", "order": 0 }]`.
  Default `at` is `last` and default `order` is 0.
- Kotlin: `UiRegistrar.inject(name, BlockInjection(place) { scope -> ... })`. A declared injection with no matching
  registration, or the reverse, is refused, as `shell` and `blocks` already are.
- `Resolved` stays as it is (root plus `problems`). The injector adds no type that callers need to know about.

**Why a leaf `InjectedBlock` and not an arbitrary plugin `Block` subtree:** an injected leaf cannot become a target
itself. That keeps resolution to one pass over core ids and blocks chains like "inject into the thing another plugin
injected". A plugin that wants structure draws it inside its own content. This can be opened up later; closing it after
release would not be possible.

**As built in J1 (ticket 55307904), where it differs from the sketch above:**
- `injections` is the **last** parameter, with a default:
  `resolve(root, overrides, locked, tree = "infantry.shell", injections = emptyList())`. The existing positional callers
  (`TabFactory`) compile unchanged and pass none until J2.
- `NodeInjection` defaults `at = Last`, `order = 0`, `loadIndex = 0` and has `id` (`<module>.<name>`).
- `InjectAt.parse("first|last|before:<id>|after:<id>")` returns null outside the grammar; `toString()` prints the
  same form, which the `problems` lines use.
- The trust rule of 2b.1 is a pure function, `admitInjections(injections, isCore, allowInject, problems)`, next to the
  resolver. J2's loader calls it with the tier and `ModuleSetting.allowInject`.
- A replace drops the injections of its whole subtree, not only of its own node. Each one gets one
  `not drawn: X is replaced by <r>` line, written by the lowest replace that drops it.

**As built in J2 (ticket 91ee3dd0):**
- module.json: `ModuleManifest.inject: List<InjectDeclaration(into, name, at = "last", order = 0)>`; `into` takes the
  bare id or `infantry.shell#<id>` (`targetId`). modules.json: `ModuleSetting.allowInject: List<String>`.
- Kotlin: `UiRegistrar.inject(name, BlockInjection)`, kept in `AgentHqModule.inject` (by name).
- **The trust rule runs in `TabFactory`, not `ModuleResolver`.** `admitInjections` and `NodeInjection` live in
  agenthqUiCore, which agenthqPlugins cannot see. `TabFactory` takes `allowInject: (module) -> Collection<String>`
  (the desktop loader passes `config.setting(it).allowInject`), binds each module's `inject` entries by name (load
  index = the module's position in load order), refuses an undeclared registration, a declaration with no
  registration and an `at` outside the grammar, then calls `admitInjections` (core = tier is not community). Every
  refusal is in `TabFactory.problems`: Settings > Plugins and the `[plugins]` log, as for overrides.
- `TabFactory` splits injections between `CoreShell.root` and `CoreShell.tabs` by target id and passes them as the
  last argument of `BlockResolver.resolve`.
- `rightPanelArea` reads the resolved body's children (`root.find(BODY)` when it is still a `RightPanelBodyBlock`,
  a wrap looked into); a replaced body or panel falls back to the per-id lookup as before.
- No built-in injector is registered; the sample is test-only (`InjectorUiTest`). The `_template` sample stays J3.

### 2b. Trust rules (same shape as L5)

1. **Loader (`ModuleResolver`, plugin side):** core modules inject freely. A community module needs the target id in
   `modules.json` `allowInject`. This is a new `ModuleSetting` list, separate from `allowOverride`. Adding to a node is
   a smaller grant than taking it over, so the owner may grant one without the other. A refusal goes to the `[plugins]`
   log.
2. **UI (`BlockResolver`, which holds the tree):** each failure below becomes one line in `problems` (Settings >
   Plugins). Nothing is silent and nothing throws.
   - target is not in the tree: `ignored: <tree> has no node X`. **Nothing is drawn and nothing crashes.**
   - target is in `locked`: `refused: X is locked`. **Never into a locked node.** This is stricter than BLOCKS-LAYOUT
     section 6. Injecting into `windowLayer` or `config` would let a plugin draw a fake token prompt over the real one.
   - the target's `withChildren` refuses (it is a leaf such as `infantry.shell.board.header` or a footer item):
     `refused: X takes no children`. The resolver catches `BlockRefusedException`.
   - `before:`/`after:` names an id that is not a **direct child** of the target: `ignored: X has no child Y`.
   - the id `<module>.<name>` is already taken by a core node or an earlier injection: `refused: duplicate id`.
     `CoreShellTreeTest` already enforces unique ids, and `RenderPlaced` keys on them.
3. **Open slots in locked nodes.** A locked node can still offer a place to add things through an explicit child that
   is **not** locked: an empty `GroupBlock`, e.g. `infantry.shell.dock.overlays`. A group is transparent
   (`flattened()`), so an empty group draws 0 px. No new concept is needed: an open slot is just an unlocked node. Each
   one is added on purpose, gets its own ticket, and its id becomes API.

### 2c. Order: deterministic with many injectors on one node

For each target:
1. Bucket the injections by anchor (`First`, `Before(c)`, `After(c)`, `Last`).
2. Sort each bucket by `(order, loadIndex, name)`. `loadIndex` is the Kahn load order the loader already produces.
   A lower `order` is closer to the start of the bucket.
3. Build the list: `First` bucket, then for each original child `c`: `Before(c)` bucket, `c`, `After(c)` bucket, then
   the `Last` bucket.

The same module set always gives the same tree. The resolver does not rewrite anchors, so a plugin that wants to sit
"after the server pill but before the right toggle" uses `after:infantry.shell.header.server` with a low order. Two
plugins with the same `order` on the same anchor are both drawn. A `problems` line says which one went first. That line
is informational and refuses nothing.

### 2d. Interaction with replace and wrap on the same node

Resolution per node, bottom-up as today:

```
kids     = children.map(resolveNode)                       // overrides below already applied
kids'    = inject(kids, injections[node.id])               // anchors match by id; an overridden child keeps its id
rebuilt  = (kids' same as children) ? node : node.withChildren(kids')
result   = overrides[node.id].fold(rebuilt) { inner, o -> OverrideBlock(o, inner) }
```

- **wrap:** the wrapper gets `inner`, which already contains the injected children. Injections survive a wrap.
- **replace:** the replacement is opaque (`OverrideBlock` has no children), so the injections into that node are not
  drawn. The resolver writes one line per injection: `<m>: injection into X not drawn: X is replaced by <r>`. A replacer
  that wants to keep them can be offered `inner` later (a `replace` that receives the resolved children). That is out of
  scope here.
- **anchor child replaced or wrapped:** the `OverrideBlock` keeps the child's id, so `before:`/`after:` still resolve.
- **no injections and no overrides:** the early return becomes `if (overrides.isEmpty() && injections.isEmpty()) return Resolved(root, ...)`,
  and the result is `CoreShell.root` itself, the same object. This is the 0-px guarantee.
- `TabFactory` splits injections between `CoreShell.root` and `CoreShell.tabs` by target id, as it does for overrides
  (`TabFactory.kt:294/298`).

### 2e. How an injected child gets what it needs

The parent decides that injection happens and where. The child knows nothing about its siblings or its parent. Same
contract as every block: it applies `scope.modifier` once, on its outermost node.

- **Today it gets:** `BlockScope` (placement, `aligned()` in a stack), `LocalBlockSize` (compact or wide), its module's
  `PluginServices`, and `HostEvents` on the event bus. That covers a status chip, a header button or a toolbar action
  that calls the daemon.
- **Host context is a separate, opt-in API step (J5):** each host that wants injectable children publishes a **public,
  read-only** context local next to its private one. Example: `LocalGitSelection: GitSelection?` (directory, branch),
  provided inside `GitPaneBlock`. `null` outside the host, so a block placed elsewhere degrades instead of crashing.
  **Do not make the existing `Local*Host` locals public.** They hold mutable state and actions (`RoleFormDraft` is the
  unsaved form). Once plugins compile against them they are API we cannot change. See "Expensive to reverse".

## 3. Where it pays off in the current shell

Every target below is unlocked and its host already renders children generically (`RenderPlaced`).

| # | target node | use |
|---|---|---|
| 1 | `infantry.shell.header` (titleBar), `after:infantry.shell.header.server` | header right group: a plugin's status pill or quick action (STT mic, CI light) next to server/attention. Several plugins coexist. With replace only, the last loaded wins. |
| 2 | `infantry.shell.footer` (statusBar, one ordered array since L2) | status-bar chips (token spend, plugin health). This is the main case: the CMS "add a widget to the footer". |
| 3 | `infantry.shell.git` (L14 tree), `after:infantry.shell.git.branches` | git pane: a PR-status or CI-pipeline section next to the core sections. Needs J5 for the selected directory. |
| 4 | `infantry.shell.roles.form`, `before:infantry.shell.roles.form.actions` | role form: a plugin's extra field group (for example routing hints). Read-only in v1. Writing into the draft needs a J5 context with an explicit `set`, so its own ticket. |
| 5 | `infantry.shell.board`, `before:infantry.shell.board.pane` | board: a filter or toolbar row above the pane. `board.header` is a leaf today, so injecting *into* the header bar first needs that bar decomposed (an L-step of its own). |

Not targets: the skeleton and dialogs (`locked`). Overlays go in through an open slot (2b.3) when one is wanted.

## 4. Risks

1. **Ordering conflicts.** Two plugins on the same anchor with the same `order` pick their places by load order. The
   result is deterministic, but the owner may not expect it. Mitigation: the `problems` line from 2c, and Settings >
   Plugins shows each injection's final index. Load order is already the tie-break for overrides.
2. **Pixel gate.** With zero injections the tree is the same object, so `diffTabShots` stays 32/32 by construction, the
   same argument as L5's "No flag". The risk is in J2: changing `rightPanelArea` and the early return must keep the
   identity (a test asserts `shellRoot === CoreShell.root`). With injectors present, the shots are not the gate. The UI
   tests are.
3. **Layout breakage inside hosts.** Header items "own the gap next to them" (L6) and the bar has a compact overflow
   (L8). An injected header item has to bring its own gap, and it **does not move into the overflow** in compact mode.
   v1 answer: give injections `place.show` (`Wide` default for header targets, recommended in the doc) and document the
   gap rule. Overflow participation is a later step.
4. (Minor) **Grant creep.** `allowInject` per target is cheap to give. A community plugin with `allowInject` on the
   header can draw anything there (a fake "update" pill, for example). That is the reason locked nodes stay closed to
   injection.

## 5. Estimate (L-step style, each 0 px with no injection, plus the existing UI tests)

| step | change | risk | weight |
|---|---|---|---|
| J1 | `InjectAt`/`BlockInjection`/`NodeInjection`/`InjectedBlock`; `BlockResolver.resolve(..., injections, ...)` with 2b.2 refusals, 2c order and 2d interplay; unit tests on plain trees | none (nothing feeds it) | 3 |
| J2 | wire it: `ModuleManifest.inject`, `ModuleSetting.allowInject`, `ModuleResolver` trust, `UiRegistrar.inject`, `AgentHqModule.inject`, `TabFactory` split root/tabs, `problems`; fix `rightPanelArea` to read the resolved body's children | low: identity early-return kept | 3 |
| J3 | `InjectorUiTest` (below) + the `_template` starter plugin gets a sample injection into `infantry.shell.footer` | none | 2 |
| J4 | first open slot(s) in locked nodes if wanted (`infantry.shell.dock.overlays`), ids pinned in `CoreShellTreeTest` | low | 1 |
| J5 | per-host public read-only context locals, one host per ticket (Git first) | medium: API surface | 2 each |

J1 to J3 are the minimum, about weight 8 (three tickets). J4 and J5 happen on demand.

**`InjectorUiTest` cases** (compose UI test, same harness as `BlockOverridesUiTest`):
1. no injections: `shellRoot === CoreShell.root`.
2. `last` into `infantry.shell.footer` draws after the last core chip. `first` draws before the first.
3. `before:`/`after:` a header child lands in the right place (checked by testTag x-order).
4. three injectors on one anchor: order by `order`, then load order, then name. Swapping the load order changes only
   the ties.
5. unknown target: nothing drawn, one `problems` line, no throw.
6. locked target (`infantry.shell.config`, `infantry.shell.windowLayer`): refused with a line.
7. leaf target (`infantry.shell.board.header`): refused ("takes no children"), no throw.
8. unknown anchor child: ignored with a line.
9. duplicate id (two modules registering the same `<module>.<name>`, or a clash with a core id): the second is refused.
10. wrap on the target: the wrapper's `inner` shows the injected child.
11. replace on the target: the injected child is not drawn, and a "replaced by" line is written.
12. anchor child replaced: the injection still sits next to the replacement.
13. community module without `allowInject`: refused at load (a `ModuleResolverTest` case). With the grant it is drawn.
14. injected into `infantry.shell.right.body`: visible docked **and** in the compact drawer (J2's `rightPanelArea` fix).
15. Git tab tree (`infantry.shell.git`) target resolves on `tabTree`, not on `shellRoot`.

## Expensive to reverse

1. **The `inject` module.json schema and the `at` grammar** (`first|last|before:<id>|after:<id>`). Plugins ship
   against it. New positions (`index:<n>`) can be added; existing ones cannot be repurposed.
2. **Leaf-only injections (no injecting into an injection).** Opening this later is easy. Closing it after plugins
   depend on it is not.
3. **Every open slot and every public context local becomes API**, like node ids since L5. Add them one at a time, on
   purpose.
4. Cheap to reverse: the tie-break order, the wording of `problems` lines, and whether `allowInject` is separate from
   `allowOverride` (the two lists can be merged without breaking anyone).
