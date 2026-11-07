// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldTypes
import com.superiorsoftllc.infantry.plugin.api.ConfigManifestSource
import com.superiorsoftllc.infantry.plugin.api.EventBus
import com.superiorsoftllc.infantry.plugin.api.GrantPattern
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.PluginEvent
import com.superiorsoftllc.infantry.plugin.api.PluginServices
import com.superiorsoftllc.infantry.plugin.api.ServiceKey
import com.superiorsoftllc.infantry.plugin.api.Services
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.ui.plugin.config.ChildrenControl
import com.superiorsoftllc.infantry.ui.plugin.config.ConfigSource
import com.superiorsoftllc.infantry.ui.plugin.config.ConfigTree
import com.superiorsoftllc.infantry.ui.plugin.config.CompiledConfig
import com.superiorsoftllc.infantry.ui.plugin.config.CoreConfig
import com.superiorsoftllc.infantry.ui.plugin.config.FieldType
import com.superiorsoftllc.infantry.ui.plugin.config.FieldTypes
import com.superiorsoftllc.infantry.ui.plugin.config.DaemonNodeConfigApi
import com.superiorsoftllc.infantry.ui.plugin.config.ImplementationFields
import com.superiorsoftllc.infantry.ui.plugin.config.Implementations
import com.superiorsoftllc.infantry.ui.plugin.config.RegisteredImplementation
import com.superiorsoftllc.infantry.ui.plugin.config.TabSorter
import com.superiorsoftllc.infantry.ui.plugin.config.TabSorters
import com.superiorsoftllc.infantry.ui.plugin.config.NodeConfigApi
import com.superiorsoftllc.infantry.ui.plugin.config.NodeConfigState
import com.superiorsoftllc.infantry.ui.plugin.config.ResolvedConfig
import com.superiorsoftllc.infantry.plugin.host.AcceptedPreference
import com.superiorsoftllc.infantry.plugin.host.AcceptedShellOverride
import com.superiorsoftllc.infantry.plugin.host.ModuleState
import com.superiorsoftllc.infantry.plugin.host.ModuleStatus
import com.superiorsoftllc.infantry.plugin.host.SimpleEventBus
import com.superiorsoftllc.infantry.ui.ModuleTier
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockInjection
import com.superiorsoftllc.infantry.ui.block.layout.BlockOverride
import com.superiorsoftllc.infantry.ui.block.layout.BlockResolver
import com.superiorsoftllc.infantry.ui.block.layout.BlockType
import com.superiorsoftllc.infantry.ui.block.layout.BlockTypes
import com.superiorsoftllc.infantry.ui.block.layout.PageJson
import com.superiorsoftllc.infantry.ui.block.layout.PageLayout
import com.superiorsoftllc.infantry.ui.block.layout.GroupBlock
import kotlinx.serialization.json.JsonObject
import com.superiorsoftllc.infantry.ui.block.layout.InjectAt
import com.superiorsoftllc.infantry.ui.block.layout.InjectedBlock
import com.superiorsoftllc.infantry.ui.block.layout.NodeInjection
import com.superiorsoftllc.infantry.ui.block.layout.NodeOverride
import com.superiorsoftllc.infantry.ui.block.layout.NodeSelector
import com.superiorsoftllc.infantry.ui.block.layout.PlaceholderBlock
import com.superiorsoftllc.infantry.ui.block.layout.admitInjections
import com.superiorsoftllc.infantry.ui.block.layout.Resolved
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.DiffTarget
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.shell.blocks.CoreShell

/**
 * PLUGINS-1: the named places tabs go. A container is any id; these are the window's two strips. A tab with a
 * [TabProvider.parent] is a sub-tab of that tab (Settings will host its pages that way).
 */
object TabContainers {
    const val CENTER = "center"
    const val RIGHT = "right"
}

/**
 * SETTINGS-TAB-1: the switch on a host tab (one that other tabs name as [TabProvider.parent]): `children.grouped`, a
 * checkbox the host's module declares on its own node. True draws those tabs as the host's sub-tabs; false or unset
 * leaves them in their strip and hides the host, so the old layout stays one click away.
 */
object TabGroups {
    const val GROUPED = "children.grouped"
}

/**
 * PLUGINS-3: the window's two side panels. A panel is a column of [PanelProvider] sections in order; the right panel
 * also hosts the [TabContainers.RIGHT] strip, at [TABS_ORDER] (sections ordered below it draw above the strip).
 */
object Panels {
    const val LEFT = "left"
    const val RIGHT = "right"
    /**
     * EXAMPLE-1: an empty slot in the left panel, right under the sidebar's Board button. The section that draws the
     * Board button draws this slot's sections there ([PanelSlot]); nothing else draws, so an empty slot takes no room.
     */
    const val LEFT_BOARD = "left.board"
    val ALL = setOf(LEFT, RIGHT, LEFT_BOARD)
    /** Where a panel's tab strip sits among its sections. */
    const val TABS_ORDER = 100
}

/**
 * PLUGINS-1: the named slots a module can add a [SlotBlock] to. A slot with no block draws nothing. Every tab container
 * declares two: `<container>.top` above its tab content and `<container>.bottom` below it, on every tab of it. A block
 * bound to any other slot name is refused with one line in [TabFactory.problems] (HOOKS-1: never a silent drop).
 */
object Slots {
    /** Above a container's tab content, on every tab of it: `<container>.top`. */
    fun top(container: String) = "$container.top"
    /** HOOKS-1: below a container's tab content, on every tab of it: `<container>.bottom`. */
    fun bottom(container: String) = "$container.bottom"
    val CENTER_TOP = top(TabContainers.CENTER)
    val RIGHT_TOP = top(TabContainers.RIGHT)
    val CENTER_BOTTOM = bottom(TabContainers.CENTER)
    val RIGHT_BOTTOM = bottom(TabContainers.RIGHT)
    /** Every slot a container declares. */
    val ALL = listOf(CENTER_TOP, CENTER_BOTTOM, RIGHT_TOP, RIGHT_BOTTOM)
}

/**
 * HOOKS-1: an interceptor's module.json `kind`, which decides the parts of its [TabInterceptor] the host runs:
 * [BEFORE] draws only `before` (above the content), [AFTER] only `after` (below it), [AROUND] runs every part (`title`
 * and `content` wrap the tab's own through `proceed`; `before` and `after` draw). A registered part its kind does not
 * run is logged and not run; an unknown kind refuses the interceptor.
 */
object InterceptorKinds {
    const val BEFORE = "before"
    const val AFTER = "after"
    const val AROUND = "around"
    val ALL = setOf(BEFORE, AFTER, AROUND)
}

/** A module's services when it has none (compiled-in core on web and Android, and tests). */
object NoPluginServices : PluginServices {
    override val module = ""
    override fun <T : Any> get(key: ServiceKey<T>): T? = null
}

/**
 * What a tab's content may reach. The window builds one per strip; a tab never sees the window's own variables, it
 * asks through these. RUNTIME-16: no module reaches the window's controller any more (`coreController` is gone); a module
 * reaches the daemon through the ShellContext domains or the gated [services].
 */
class TabContext(
    val state: AppState,
    internal val controller: Controller,
    /** Shows another tab by id, in that tab's own container. */
    val open: (tabId: String) -> Unit,
    /** Shows the chat of the selected agent (the centre container's home tab) and leaves spawn mode. */
    val showChat: () -> Unit,
    /** Puts text into the chat box at the cursor. */
    val insertIntoChat: (String) -> Unit,
    val diffTarget: DiffTarget?,
    val onOpenDiff: (DiffTarget) -> Unit,
    /** The selected agent's transcript. Only the centre strip has one; elsewhere it draws nothing. */
    val transcript: @Composable () -> Unit,
    /** The platform services of the module drawing this tab, as its module.json and the owner allow. */
    val services: PluginServices = NoPluginServices,
    /** RUNTIME-5: the sub-tab picked in a host tab (host id -> sub-tab id; null = none yet, the host opens on its first). */
    val subTab: (hostId: String) -> String? = { null },
    /** RUNTIME-5: remembers [subTabId] as the one picked in host [hostId] (the window owns it, while the app runs). */
    val pickSubTab: (hostId: String, subTabId: String) -> Unit = { _, _ -> },
    /** CONFIG-7 (additive): node [nodeId]'s resolved config values, live; every key reads null where there is no shell. */
    val config: (nodeId: String) -> com.superiorsoftllc.infantry.ui.shell.ConfigValues = { com.superiorsoftllc.infantry.ui.shell.EmptyConfig },
) {
    /**
     * RUNTIME-5 (plugin API, additive): draws host tab [hostId]'s sub-tabs ([TabFactory.children]) as a strip and the
     * picked one's content, the picked one kept by the window ([subTab] / [pickSubTab]). Any tier may call it.
     */
    @Composable
    fun SubTabs(hostId: String) =
        SubTabHost(com.superiorsoftllc.infantry.ui.LocalTabFactory.current, hostId, this, subTab(hostId)) { pickSubTab(hostId, it) }

    /** Selects an agent and shows its chat. */
    fun openAgent(sessionId: String) {
        controller.select(sessionId)
        showChat()
    }

    internal fun forModule(services: PluginServices) =
        TabContext(state, controller, open, showChat, insertIntoChat, diffTarget, onOpenDiff, transcript, services, subTab, pickSubTab, config)
}

/**
 * One tab, as a module contributes it. The UI never names a tab: [TabFactory] lists a container's providers and
 * draws whichever one is picked. A tab must be declared in its module's module.json (`tabs`), or it is refused.
 */
data class TabProvider(
    /** Unique across modules, `vendor.name` (e.g. `infantry.git`). */
    val id: String,
    val container: String,
    /** Lower first; the built-in tabs leave gaps of 10 so a module can slot a tab between two of them. */
    val order: Int,
    /** The strip label, badge and shortcut hint included; read from state so a count stays live. */
    val title: (AppState) -> String,
    /** A drawn vector icon (never a text glyph: the web build renders those as tofu). */
    val icon: ImageVector,
    /** The test tag on the strip entry. */
    val testTag: String,
    /** The tab this one sits under, for a container tab with sub-tabs; null = top level. */
    val parent: String? = null,
    /** The digit of the Ctrl+<digit> shortcut that shows this tab; null = no shortcut. */
    val shortcut: Int? = null,
    /** The controller calls a core tab makes (documentation; a community tab's daemon routes are in its module.json). */
    val routes: List<String> = emptyList(),
    /** The `Vendor_Module` that contributes it. Set by the factory from the owning module; whatever is passed is ignored. */
    val module: String = "",
    val content: @Composable (TabContext) -> Unit,
)

/**
 * PLUGINS-3: one section of a side panel, as a module contributes it; declared in its module.json (`panels`) or
 * refused, like a tab. Sections stack in [order]; a [fill] section takes the panel's free height (shared if several).
 */
data class PanelProvider(
    /** Unique across modules, `vendor.name`. */
    val id: String,
    /** [Panels.LEFT] or [Panels.RIGHT]. */
    val panel: String,
    /** Lower first; on the right, below [Panels.TABS_ORDER] draws above the tab strip. */
    val order: Int,
    val testTag: String,
    val fill: Boolean = false,
    /** The `Vendor_Module` that contributes it. Set by the factory; whatever is passed is ignored. */
    val module: String = "",
    val content: @Composable (TabContext) -> Unit,
)

/**
 * An interceptor on one tab (Magento's plugin): [title] runs around the tab's own title (call `proceed`), [content]
 * runs around the tab's own content (call `proceed` to draw it, or don't to replace it), [before] and [after] draw
 * above and below its content. Bound to a tab by the module's module.json `interceptors` entry, whose `kind` decides
 * which parts run (see [InterceptorKinds]).
 */
class TabInterceptor(
    val title: ((state: AppState, proceed: () -> String) -> String)? = null,
    val before: (@Composable (TabContext) -> Unit)? = null,
    val after: (@Composable (TabContext) -> Unit)? = null,
    val content: (@Composable (context: TabContext, proceed: @Composable () -> Unit) -> Unit)? = null,
) {
    /** The parts [kind] runs; null for an unknown kind. */
    internal fun forKind(kind: String): TabInterceptor? = when (kind) {
        InterceptorKinds.BEFORE -> TabInterceptor(before = before)
        InterceptorKinds.AFTER -> TabInterceptor(after = after)
        InterceptorKinds.AROUND -> this
        else -> null
    }

    /** The names of the parts this one registers. */
    internal val parts: List<String>
        get() = listOfNotNull(title?.let { "title" }, before?.let { "before" }, after?.let { "after" }, content?.let { "content" })

    /** S4d: this one with its parts drawn as module tier [core]. */
    internal fun atTier(core: Boolean): TabInterceptor = if (core) this else TabInterceptor(
        title,
        before?.let { b -> { c -> ModuleTier(false) { b(c) } } },
        after?.let { a -> { c -> ModuleTier(false) { a(c) } } },
        content?.let { w -> { c, proceed -> ModuleTier(false) { w(c, proceed) } } },
    )
}

/** AR-7b: the colour family of a session-row badge; core maps it to the palette. */
enum class BadgeTone { INFO, MUTED, ACCENT, OK, WARN, DANGER }

/** AR-7b: one badge on a session row: data only (label, tone, tooltip), no plugin names in core. */
data class SessionBadge(val label: String, val tone: BadgeTone = BadgeTone.INFO, val tooltip: String? = null, val testTag: String? = null)

/**
 * AR-7b: a module's contribution to every session row. [badge] returns the badge for one session, or null for none;
 * it reads live state, so a title or count stays current. Providers draw in [order], then [id].
 */
data class SessionBadgeProvider(val id: String, val order: Int = 0, val badge: (AppState, AgentView) -> SessionBadge?)

/** A block a module adds to a named slot (see [Slots]); bound by the module.json `blocks` entry. */
class SlotBlock(val content: @Composable (TabContext) -> Unit)

/**
 * A module's code: everything it contributes, under the names its module.json uses. Core modules are values in
 * plugins/core; a community module's [UiPlugin] fills one through a [UiRegistrar].
 */
class InfantryModule(
    val manifest: ModuleManifest,
    val tabs: List<TabProvider> = emptyList(),
    val interceptors: Map<String, TabInterceptor> = emptyMap(),
    val blocks: Map<String, SlotBlock> = emptyMap(),
    val observers: Map<String, (PluginEvent) -> Unit> = emptyMap(),
    val tier: Tier = Tier.CORE,
    val panels: List<PanelProvider> = emptyList(),
    /** BLOCKS-LAYOUT L5: shell node overrides, by the names module.json `shell` uses. */
    val shell: Map<String, BlockOverride> = emptyMap(),
    /** BLOCKS-INJECTORS J2: blocks added among a node's children, by the names module.json `inject` uses. */
    val inject: Map<String, BlockInjection> = emptyMap(),
    /** S4: the trees drawn in the shell's empty slots ([CoreShell.slots]), by slot id (module.json `fills`); core tier only. */
    val fills: Map<String, Block> = emptyMap(),
    /** S4: the tab trees the module's tabs draw ([TabFactory.tabTree]), by the id of the tab that draws each; core tier only. */
    val tabTrees: Map<String, Block> = emptyMap(),
    /** P1 step 2 (6s D): implementations for config `implementation` fields, registry -> key -> value (module.json `implementations`). */
    val implementations: Map<String, Map<String, Any>> = emptyMap(),
    /** NS1 / PB1 (6t.2): the block types the module registers, by type name (module.json `blockTypes`). */
    val blockTypes: Map<String, BlockType> = emptyMap(),
    /**
     * NS3: a composition's trees as data, by slot id (module.json `fills[].layout`, the page JSON read from the module
     * folder); instantiated through the block-type registry. A community one needs the slot in its `allowOverride` grant.
     */
    val layoutFills: Map<String, JsonObject> = emptyMap(),
    /** CONFIG-5: the config field types the module registers, by id (module.json `fieldTypes`). */
    val fieldTypes: Map<String, FieldType> = emptyMap(),
    /** S7: runs once when the host unloads the module (a soft restart replaces the module set, or the app closes it). */
    val onStop: () -> Unit = {},
    /** AR-7b: the session-row badges the module contributes (`UiRegistrar.sessionBadge`). */
    val sessionBadges: List<SessionBadgeProvider> = emptyList(),
)

/**
 * PLUGINS-1: the entry point of a module loaded at run time (module.json `entry`). It registers its contributions by
 * the names module.json declares; the host binds them, and refuses what is not declared.
 */
interface UiPlugin {
    fun register(registrar: UiRegistrar)

    /**
     * S7: the host is unloading this module (soft restart, install, remove). Release what [register] or the module's tabs
     * started (threads, timers, sockets). Its event observers are unsubscribed by the host, not here. Default: nothing.
     */
    fun stop() {}
}

interface UiRegistrar {
    /** The module's services (permission-gated). */
    val services: PluginServices
    fun tab(provider: TabProvider)
    /** PLUGINS-3: a side panel section; declare it under `panels` in module.json. */
    fun panel(provider: PanelProvider) {}
    fun interceptor(name: String, interceptor: TabInterceptor)
    fun block(name: String, block: SlotBlock)
    fun observer(name: String, observer: (PluginEvent) -> Unit)
    /** BLOCKS-LAYOUT L5: an override of a core shell node; declare it under `shell` in module.json (node, name, mode). */
    fun shell(name: String, override: BlockOverride) {}
    /** BLOCKS-INJECTORS J2: a block added among a node's children; declare it under `inject` in module.json (into, name, at, order). */
    fun inject(name: String, injection: BlockInjection) {}
    /** S4: the tree drawn in empty shell slot [node] (its id is the block's); declare it under `fills` in module.json. */
    fun fill(node: String, block: Block) {}
    /** S4: the tab tree tab [tab] draws (`TabFactory.tabTree(root.id)`). */
    fun tabTree(tab: String, root: Block) {}
    /** P1 step 2: [value] under [key] of registry [registry]; declare it under `implementations` in module.json. */
    fun implementation(registry: String, key: String, value: Any) {}
    /** NS1 / PB1: block type [type] (`vendor.name`); declare it under `blockTypes` in module.json. */
    fun blockType(type: String, blockType: BlockType) {}
    /** CONFIG-5: config field type [id] (editor + validate); declare it under `fieldTypes` in module.json. */
    fun fieldType(id: String, type: FieldType) {}
    /** AR-7b: a badge on every session row (label, tone, tooltip as data). */
    fun sessionBadge(provider: SessionBadgeProvider) {}
}

/** Runs [plugin]'s registration into a module of [tier]. */
fun registerModule(manifest: ModuleManifest, tier: Tier, services: PluginServices, plugin: UiPlugin): InfantryModule {
    val tabs = mutableListOf<TabProvider>()
    val panels = mutableListOf<PanelProvider>()
    val interceptors = LinkedHashMap<String, TabInterceptor>()
    val blocks = LinkedHashMap<String, SlotBlock>()
    val observers = LinkedHashMap<String, (PluginEvent) -> Unit>()
    val shell = LinkedHashMap<String, BlockOverride>()
    val inject = LinkedHashMap<String, BlockInjection>()
    val fills = LinkedHashMap<String, Block>()
    val tabTrees = LinkedHashMap<String, Block>()
    val implementations = LinkedHashMap<String, LinkedHashMap<String, Any>>()
    val blockTypes = LinkedHashMap<String, BlockType>()
    val fieldTypes = LinkedHashMap<String, FieldType>()
    val sessionBadges = mutableListOf<SessionBadgeProvider>()
    plugin.register(object : UiRegistrar {
        override val services = services
        override fun tab(provider: TabProvider) { tabs += provider }
        override fun panel(provider: PanelProvider) { panels += provider }
        override fun interceptor(name: String, interceptor: TabInterceptor) { interceptors[name] = interceptor }
        override fun block(name: String, block: SlotBlock) { blocks[name] = block }
        override fun observer(name: String, observer: (PluginEvent) -> Unit) { observers[name] = observer }
        override fun shell(name: String, override: BlockOverride) { shell[name] = override }
        override fun inject(name: String, injection: BlockInjection) { inject[name] = injection }
        override fun fill(node: String, block: Block) { fills[node] = block }
        override fun tabTree(tab: String, root: Block) { tabTrees[tab] = root }
        override fun implementation(registry: String, key: String, value: Any) { implementations.getOrPut(registry) { LinkedHashMap() }[key] = value }
        override fun blockType(type: String, blockType: BlockType) { blockTypes[type] = blockType }
        override fun fieldType(id: String, type: FieldType) { fieldTypes[id] = type }
        override fun sessionBadge(provider: SessionBadgeProvider) { sessionBadges += provider }
    })
    return InfantryModule(manifest, tabs, interceptors, blocks, observers, tier, panels, shell, inject, fills, tabTrees, implementations, blockTypes, fieldTypes = fieldTypes, onStop = plugin::stop, sessionBadges = sessionBadges)
}

/**
 * The factory the window draws tabs from, built once from the loaded modules (in load order):
 * - a tab its module.json does not declare, or declares in another container, is refused;
 * - a second tab with an id already taken is refused (replacing one on purpose is a [preferences] entry, which the
 *   loader's trust rules have already let through);
 * - a preference puts the replacing tab in the replaced one's place (id, container, order, shortcut, tag); the
 *   replacing tab is not shown under its own id. HOOKS-1: a panel section replaces a panel section the same way (id,
 *   panel, order, tag, fill);
 * - interceptors, blocks and observers are bound by their declared names; an undeclared or unknown one is refused.
 *   An interceptor runs only the parts its `kind` names; a block binds only to a slot in [Slots.ALL];
 * - BLOCKS-LAYOUT L5: [shellOverrides] (the loader's trust rules already let them through) bind to the module's
 *   registered [InfantryModule.shell] by name and go into [CoreShell.root] through [BlockResolver]; [shellRoot] is
 *   the tree the window draws ([CoreShell.root] itself when none applies);
 * - BLOCKS-INJECTORS J2: each module's module.json `inject` entries bind to its registered [InfantryModule.inject] by
 *   name, pass the trust rule ([admitInjections]: a core module freely, a community one only into a target its
 *   [allowInject] lists) and go into the tree that holds their target, through [BlockResolver] like the overrides;
 * - S4 (drop-in path): a core module's declared [InfantryModule.fills] go into [CoreShell.root]'s empty slots before
 *   anything else ([baseRoot]), so the overrides and injections address their nodes as if the core drew them; an
 *   undeclared fill, one of a community module, of a node that is no slot or of a slot already filled is refused.
 *   The tab trees ([tabTreeRoots]) are the modules' [InfantryModule.tabTrees], in load order.
 * Everything refused is in [problems]; nothing here throws.
 */
class TabFactory(
    modules: List<InfantryModule>,
    preferences: List<AcceptedPreference> = emptyList(),
    /** The services of each module, by name. */
    private val services: (module: String) -> PluginServices = { NoPluginServices },
    /** The bus the window publishes to and observers listen on. */
    val events: EventBus = SimpleEventBus(),
    /** Every module the loader looked at, for Settings > Plugins; defaults to [modules], all loaded. */
    statuses: List<ModuleStatus>? = null,
    /** PLUGINS-2: how Settings > Plugins enables and disables a module; null = this host cannot change modules.json. */
    val changes: ModuleChanges? = null,
    /** BLOCKS-LAYOUT L5: the shell overrides the loader accepted, in load order. */
    shellOverrides: List<AcceptedShellOverride>? = null,
    /** BLOCKS-INJECTORS J2: the node ids modules.json `allowInject` grants each community module. */
    private val allowInject: (module: String) -> Collection<String> = { emptyList() },
    /** P1 step 2: the core contributions (by id) modules.json `allowOverride` grants each community module; a granted node takes its config. */
    private val allowOverride: (module: String) -> Collection<String> = { emptyList() },
    /** P1 step 2: where the node config is stored (the window's daemon; a test passes a fake). */
    private val configApi: NodeConfigApi = DaemonNodeConfigApi,
    /** CONFIG-FIELDS-4 (G8): the owner's modules.json; `configReadGate` and the `config.read` grant decide what a community module reads. */
    private val modulesConfig: com.superiorsoftllc.infantry.plugin.api.ModulesConfig = com.superiorsoftllc.infantry.plugin.api.ModulesConfig(),
) {
    val modules: List<InfantryModule> = modules.toList()
    /** TOAST-1: where a module's `Notifications.notify` lands in the window; the window sets it to its controller's. */
    var toasts: com.superiorsoftllc.infantry.ui.ToastService = com.superiorsoftllc.infantry.ui.NoToastService
    /**
     * NS3: exports node id N's subtree as a composition `.ihq` and returns the line to show (the file, or the refusals);
     * null = this host cannot export (only the desktop sets it). Settings > Plugins > Registered shows "Export section…".
     */
    var sectionExporter: ((node: String) -> String)? = null
    /** NS10: exports node id N and starts it in a fresh shell process; returns the line to show. Null = cannot (not desktop). */
    var freshShellLauncher: ((node: String) -> String)? = null
    /** Whether a loader looked at a plugins root (the desktop); false = compiled-in modules only (web, Android, tests). */
    val loadsAtRunTime: Boolean = statuses != null
    val statuses: List<ModuleStatus> = statuses ?: modules.map { ModuleStatus(it.manifest.name, it.tier, ModuleState.LOADED, manifest = it.manifest) }
    private val bindProblems: List<String>
    private val nodeOverrides: List<NodeOverride>
    private val nodeInjections: List<NodeInjection>
    private val slotFills: Map<String, Block>
    /** S4: the core tree with the modules' fills in, before any override or injection (the core's empty tree when none fills). */
    val baseRoot: Block by lazy { CoreShell.root.filled(slotFills) }
    /** S4c: whether a loaded module fills the empty slot [slot] ([CoreShell.slots]). */
    fun fills(slot: String): Boolean = slot in slotFills
    /** S4d: the module that fills each filled slot, by slot (node) id (the Registered view names it). */
    val fillModules: Map<String, String>
    /** S4: the tab trees the loaded modules draw, unresolved, in load order. */
    val tabTreeRoots: List<Block>
    /** S4 (P1 f60f4d97's map): the tab (its `TabProvider` id) that draws each tree of [tabTreeRoots], by tree id. */
    val tabTreeTabs: Map<String, String>
    /** BLOCKS-LAYOUT L5: the core tree with the overrides (and J2 injections) in, resolved once (lazily: the tree is not built at init). */
    private val shell: Resolved by lazy {
        val inTabs = tabTreeRoots.flatMap { it.ids() }.toSet()
        BlockResolver.resolve(
            baseRoot, nodeOverrides.filter { o -> tabTreeRoots.none { NodeSelector.find(it, o.node) != null } }, locked,
            injections = nodeInjections.filter { it.target !in inTabs },
        )
    }
    /** BLOCKS-LAYOUT L14: each tab tree ([tabTreeRoots]) with the overrides and injections of its own nodes in, resolved once. */
    private val tabTrees: List<Resolved> by lazy {
        tabTreeRoots.map { t ->
            val ids = t.ids()
            BlockResolver.resolve(t, nodeOverrides.filter { o -> NodeSelector.find(t, o.node) != null }, locked, t.id, nodeInjections.filter { it.target in ids })
        }
    }
    /** The tree the window draws: [resolvedShellRoot] without the injections a node's settings disable (P1 step 2). */
    val shellRoot: Block get() = drawn(shell.root)
    /** P1: the core tree as resolved, every injection in (the registry view lists the disabled ones too). */
    val resolvedShellRoot: Block get() = shell.root
    /** BLOCKS-LAYOUT L14: the core tab tree with root [id] as resolved (e.g. `infantry.shell.git`), or null if there is none. */
    fun tabTree(id: String): Block? = resolvedTabTree(id)?.let(::drawn)
    /** P1: [tabTree] with every injection in. */
    fun resolvedTabTree(id: String): Block? = tabTrees.firstOrNull { it.root.id == id }?.root

    /**
     * P1 step 2 (6s E): [root] without the injected children a node's `children.disabled` names, the very same object
     * when none applies; memoised per stored copy, so a frame never rebuilds a tree.
     */
    private fun drawn(root: Block): Block {
        val stored = config.stored
        if (drawnFor !== stored) { drawnCache.clear(); drawnFor = stored }
        return drawnCache.getOrPut(root.id) { root.withoutDisabled { node -> ChildrenControl.disabled(config, node) } }
    }
    private var drawnFor: Any? = null
    private val drawnCache = HashMap<String, Block>()
    /** Everything refused or ignored, one line each. */
    val problems: List<String> get() = bindProblems + lockedNodes.second + shell.problems + tabTrees.flatMap { it.problems } + blockTypes.problems + fieldTypes.problems

    /** GAP-AUDIT-1 (b): the declared node locks, and a line per refused one (a community module locks only `<name>.` ids). */
    private val lockedNodes: Pair<Set<String>, List<String>> by lazy {
        val refused = mutableListOf<String>()
        val ids = modules.flatMap { m ->
            val name = m.manifest.name
            m.manifest.nodes.filter { !it.overridable }.mapNotNull { n ->
                when {
                    n.id.isBlank() -> { refused += "$name: nodes[] entry with an empty id ignored"; null }
                    m.tier == Tier.COMMUNITY && !n.id.startsWith("$name.") ->
                        { refused += "$name: overridable:false on ${n.id} refused: a community module locks only its own nodes ($name.*)"; null }
                    else -> n.id
                }
            }
        }
        (CoreShell.locked + ids) to refused
    }

    /**
     * GAP-AUDIT-1 (b): the nodes no module may replace, wrap, place a block beside or inject into: [CoreShell.locked] plus
     * every node a loaded module's module.json marks `overridable: false`.
     */
    val locked: Set<String> get() = lockedNodes.first

    /**
     * GAP-AUDIT-1 (c): who overrides what, for Settings > Plugins > Registered: every accepted shell override (the loader's
     * trust rule already passed) with its selector resolved to the node's full path, and whether it is drawn now; a
     * refused or ignored one carries its problem line. Read-only; built from the resolved trees, never a second resolve.
     */
    val overrideMap: List<OverrideEntry> by lazy {
        val trees = listOf(shell) + tabTrees
        nodeOverrides.map { o ->
            val tree = trees.firstOrNull { NodeSelector.find(it.root, o.node) != null } ?: shell
            val id = NodeSelector.find(baseRoot, o.node) ?: tabTreeRoots.firstNotNullOfOrNull { NodeSelector.find(it, o.node) }
            val path = id?.let { pathTo(tree.root, it) ?: tabTreeRoots.firstNotNullOfOrNull { t -> pathTo(t, it) } }
            val prefix = "${o.module}: shell override (${o.mode}) of ${o.node}"
            val problem = tree.problems.firstOrNull { it.startsWith(prefix) }
                ?: (listOf(shell) + tabTrees).flatMap { it.problems }.firstOrNull { it.startsWith(prefix) }
            val drawn = id != null && problem == null && isDrawn(tree.root, o.module, o.mode, id)
            OverrideEntry(o.module, byModule[o.module]?.tier ?: Tier.CORE, o.mode, o.node, id, path, o.priority, drawn,
                problem?.removePrefix(prefix)?.trim())
        }
    }
    private val providers: List<TabProvider>
    /** P1 (f60f4d97): each tab id a preference replaced, and the module whose tab it replaced. */
    internal val replacedTabs: Map<String, String>
    private val sections: List<PanelProvider>
    private val interceptors: Map<String, List<TabInterceptor>>
    private val blocks: Map<String, List<Pair<String, SlotBlock>>>
    private val byModule = modules.associateBy { it.manifest.name }

    init {
        val problems = mutableListOf<String>()
        val replaced = LinkedHashMap<String, String>()
        val seen = LinkedHashMap<String, TabProvider>()
        for (m in modules) for (t in m.tabs) {
            val name = m.manifest.name
            val declared = m.manifest.tabs.firstOrNull { it.id == t.id }
            val taken = seen[t.id]
            when {
                declared == null -> problems += "$name: tab ${t.id} is not declared in its module.json; refused"
                declared.container != t.container -> problems += "$name: tab ${t.id} is declared in ${declared.container}, not ${t.container}; refused"
                taken != null -> problems += "$name: tab ${t.id} is already provided by ${taken.module}; skipped"
                else -> seen[t.id] = t.copy(module = name)
            }
        }
        // PLUGINS-3: panel sections bind the way tabs do.
        val knownPanels = Panels.ALL + modules.flatMap { it.manifest.slots }
        val placed = LinkedHashMap<String, PanelProvider>()
        for (m in modules) for (p in m.panels) {
            val name = m.manifest.name
            val declared = m.manifest.panels.firstOrNull { it.id == p.id }
            val taken = placed[p.id]
            when {
                declared == null -> problems += "$name: panel section ${p.id} is not declared in its module.json; refused"
                declared.panel != p.panel -> problems += "$name: panel section ${p.id} is declared in ${declared.panel}, not ${p.panel}; refused"
                p.panel !in knownPanels -> problems += "$name: panel section ${p.id} names unknown panel ${p.panel}; refused"
                taken != null -> problems += "$name: panel section ${p.id} is already provided by ${taken.module}; skipped"
                else -> placed[p.id] = p.copy(module = name)
            }
        }
        for (p in preferences) {
            val target = seen[p.replace]
            val with = seen[p.with]?.takeIf { it.module == p.module }
            val targetSection = placed[p.replace]
            val withSection = placed[p.with]?.takeIf { it.module == p.module }
            when {
                target != null && with != null -> {
                    replaced[p.replace] = target.module
                    seen.remove(p.with)
                    seen[p.replace] = with.copy(id = target.id, container = target.container, order = target.order, parent = target.parent, shortcut = target.shortcut, testTag = target.testTag)
                }
                // HOOKS-1: a panel section takes the replaced section's place the same way.
                targetSection != null && withSection != null -> {
                    placed.remove(p.with)
                    placed[p.replace] = withSection.copy(id = targetSection.id, panel = targetSection.panel, order = targetSection.order, testTag = targetSection.testTag, fill = targetSection.fill)
                }
                else -> problems += "${p.module}: preference ${p.replace} -> ${p.with} has no tab or panel section to apply to; skipped"
            }
        }
        // A tab or section its module offers as a replacement shows only as one: a refused preference leaves no stray behind.
        for (m in modules) for (p in m.manifest.preferences) {
            if (preferences.any { it.module == m.manifest.name && it.with == p.with }) continue
            if (seen[p.with]?.module == m.manifest.name) {
                seen.remove(p.with)
                problems += "${m.manifest.name}: tab ${p.with} only replaces ${p.replace}, which was not accepted; not shown"
            }
            if (placed[p.with]?.module == m.manifest.name) {
                placed.remove(p.with)
                problems += "${m.manifest.name}: panel section ${p.with} only replaces ${p.replace}, which was not accepted; not shown"
            }
        }
        sections = placed.values.toList()
        val ints = LinkedHashMap<String, MutableList<TabInterceptor>>()
        val bls = LinkedHashMap<String, MutableList<Triple<Int, String, SlotBlock>>>()
        for (m in modules) {
            val name = m.manifest.name
            for (d in m.manifest.interceptors) {
                val i = m.interceptors[d.name]
                val bound = i?.forKind(d.kind)
                when {
                    i == null -> problems += "$name: interceptor ${d.name} is declared but not registered; skipped"
                    bound == null -> problems += "$name: interceptor ${d.name} has unknown kind '${d.kind}' (${InterceptorKinds.ALL.joinToString()}); skipped"
                    d.target !in seen -> problems += "$name: interceptor ${d.name} targets ${d.target}, which no loaded module provides; skipped"
                    else -> {
                        val unused = i.parts - bound.parts.toSet()
                        if (unused.isNotEmpty()) problems += "$name: interceptor ${d.name} is kind ${d.kind}; its ${unused.joinToString()} not run"
                        ints.getOrPut(d.target) { mutableListOf() } += bound.atTier(m.tier != Tier.COMMUNITY)
                    }
                }
            }
            for (n in m.interceptors.keys - m.manifest.interceptors.map { it.name }.toSet()) problems += "$name: interceptor $n is not declared in its module.json; refused"
            for (d in m.manifest.blocks) {
                val b = m.blocks[d.name]
                when {
                    b == null -> problems += "$name: block ${d.name} is declared but not registered; skipped"
                    d.slot !in Slots.ALL -> problems += "$name: block ${d.name} names unknown slot ${d.slot} (${Slots.ALL.joinToString()}); not drawn"
                    else -> bls.getOrPut(d.slot) { mutableListOf() } += Triple(d.order, name, b)
                }
            }
            for (n in m.blocks.keys - m.manifest.blocks.map { it.name }.toSet()) problems += "$name: block $n is not declared in its module.json; refused"
            for (d in m.manifest.observers) {
                val o = m.observers[d.name]
                val bus = services(name).get(Services.EVENTS)
                when {
                    o == null -> problems += "$name: observer ${d.name} is declared but not registered; skipped"
                    bus == null -> problems += "$name: observer ${d.name} needs the events service; skipped"
                    else -> bus.subscribe(d.event, o)
                }
            }
            for (n in m.observers.keys - m.manifest.observers.map { it.name }.toSet()) problems += "$name: observer $n is not declared in its module.json; refused"
            for (n in m.shell.keys - m.manifest.shell.map { it.name }.toSet()) problems += "$name: shell override $n is not declared in its module.json; refused"
            for (n in m.inject.keys - m.manifest.inject.map { it.name }.toSet()) problems += "$name: injection $n is not declared in its module.json; refused"
        }
        // BLOCKS-INJECTORS J2: modules are in load order, so a module's index is its load index (the tie-break after order).
        val declared = modules.flatMapIndexed { loadIndex, m ->
            val name = m.manifest.name
            m.manifest.inject.mapNotNull { d ->
                val j = m.inject[d.name]
                val at = InjectAt.parse(d.at)
                when {
                    j == null -> { problems += "$name: injection ${d.name} is declared but not registered; skipped"; null }
                    at == null -> { problems += "$name: injection ${d.name} into ${d.targetId} has unknown at '${d.at}' (first, last, before:<id>, after:<id>); skipped"; null }
                    else -> NodeInjection(name, d.name, d.targetId, at, d.order, loadIndex, j, community = m.tier == Tier.COMMUNITY)
                }
            }
        }
        nodeInjections = admitInjections(declared, isCore = { byModule[it]?.tier != Tier.COMMUNITY }, allowInject = allowInject, problems = problems)
        // OVERRIDE-1: no loader list (web, Android, tests: compiled-in modules) = read the overrides off the manifests, same trust rule.
        val accepted = shellOverrides ?: modules.flatMap { m ->
            m.manifest.shell.mapNotNull { d ->
                if (m.tier == Tier.COMMUNITY && !GrantPattern.matches(allowOverride(m.manifest.name), d.nodeId)) {
                    problems += "${m.manifest.name}: shell override ${d.name} of ${d.nodeId} refused: a community module cannot override core shell node ${d.nodeId} unless modules.json allows it"
                    null
                } else AcceptedShellOverride(m.manifest.name, d.nodeId, d.name, d.mode, d.priority)
            }
        }
        nodeOverrides = accepted.mapNotNull { a ->
            val o = byModule[a.module]?.shell?.get(a.name)
            if (o == null) problems += "${a.module}: shell override ${a.name} is declared but not registered; skipped"
            o?.let { NodeOverride(a.module, a.node, a.mode, it, community = byModule[a.module]?.tier == Tier.COMMUNITY, priority = a.priority) }
        }
        // S4: the fills and the tab trees, core tier only.
        val fills = LinkedHashMap<String, Block>()
        var layoutTypes: BlockTypes? = null
        val fillers = LinkedHashMap<String, String>()
        val trees = LinkedHashMap<String, Pair<String, Block>>()
        // CONFIG-10b: fills[].replaces: (replaced module, node) pairs whose fill gives way to the replacing module's.
        val replacedFills = modules.flatMap { m -> m.manifest.fills.filter { it.replaces != null && it.node in m.fills }.map { it.replaces!! to it.node } }.toSet()
        for (m in modules) {
            val name = m.manifest.name
            for ((node, b) in m.fills) when {
                (name to node) in replacedFills -> problems += "$name: fill of $node replaced by another module's (fills[].replaces); unused"
                m.tier == Tier.COMMUNITY -> problems += "$name: fill of $node refused: only a core module fills a shell slot"
                node !in m.manifest.fillNodes -> problems += "$name: fill of $node is not declared in its module.json; refused"
                node !in CoreShell.slots -> problems += "$name: fill of $node refused: it is not an empty shell slot"
                b.id != node -> problems += "$name: fill of $node refused: its block's id is ${b.id}"
                node in fills -> problems += "$name: fill of $node skipped: already filled"
                else -> { fills[node] = b; fillers[node] = name }
            }
            for (node in m.manifest.fills.filter { it.layout == null }.map { it.node } - m.fills.keys) problems += "$name: fill of $node is declared but not registered; skipped"
            // NS3: a composition's fills[].layout, instantiated through the registry.
            for ((node, page) in m.layoutFills) when {
                m.manifest.fills.none { it.node == node && it.layout != null } -> problems += "$name: layout fill of $node is not declared in its module.json; refused"
                m.tier == Tier.COMMUNITY && !GrantPattern.matches(allowOverride(name), node) -> problems += "$name: layout fill of $node refused: not granted (allowOverride)"
                node !in CoreShell.slots -> problems += "$name: layout fill of $node refused: it is not an empty shell slot"
                node in fills -> problems += "$name: layout fill of $node skipped: already filled"
                else -> {
                    val types = layoutTypes ?: BlockTypes.of(modules.map { Triple(it.manifest.name, it.manifest.blockTypes, it.blockTypes) }).also { layoutTypes = it }
                    val built = PageLayout.instantiate(page, types)
                    built.problems.forEach { problems += "$name: layout fill of $node: $it" }
                    val root = built.root
                    if (root != null) { fills[node] = if (root.id == node) root else GroupBlock(node, listOf(root)); fillers[node] = name }
                }
            }
            for ((tab, root) in m.tabTrees) when {
                m.tier == Tier.COMMUNITY -> problems += "$name: tab tree ${root.id} refused: only a core module adds a tab tree"
                root.id in trees -> problems += "$name: tab tree ${root.id} skipped: already provided by ${trees.getValue(root.id).first}"
                else -> trees[root.id] = tab to root
            }
        }
        slotFills = fills
        fillModules = fillers
        tabTreeRoots = trees.values.map { it.second }
        tabTreeTabs = trees.mapValues { it.value.first }
        providers = seen.values.toList()
        replacedTabs = replaced
        interceptors = ints
        blocks = bls.mapValues { (_, v) -> v.sortedBy { it.first }.map { it.second to it.third } }
        bindProblems = problems
    }

    /** A container's top-level tabs, in strip order: P1 step 2, the strip node's stored order, without its disabled tabs. */
    fun tabs(container: String): List<TabProvider> = arranged(stripNode(container), declaredTabs(container))

    /** The sub-tabs of [parentId], in order (the parent tab's stored order, without its disabled sub-tabs). */
    fun children(parentId: String): List<TabProvider> = arranged(parentId, declaredChildren(parentId))

    /**
     * P1: a container's top-level tabs in their declared order, disabled ones included. SETTINGS-TAB-1: a tab whose
     * parent does not group its sub-tabs now ([grouped]) is top level here, and that parent is not drawn.
     */
    fun declaredTabs(container: String): List<TabProvider> =
        providers.filter { it.container == container && hostOf(it.id) == null && !(isHost(it.id) && !grouped(it.id)) }
            .sortedWith(compareBy({ it.order }, { it.id }))

    /** P1: [parentId]'s sub-tabs in their declared order, disabled ones included; none while it does not group them. */
    fun declaredChildren(parentId: String): List<TabProvider> =
        providers.filter { hostOf(it.id) == parentId }.sortedWith(compareBy({ it.order }, { it.id }))

    /**
     * SETTINGS-TAB-1: whether host tab [hostId] draws the tabs that name it [TabProvider.parent] as its sub-tabs now: its
     * own `children.grouped` setting ([TabGroups.GROUPED], stored on the host node); unset is false, so a host shows
     * nothing until the owner switches it on and its sub-tabs stay in their strip.
     */
    fun grouped(hostId: String): Boolean = resolvedConfig.values(hostId).boolean(TabGroups.GROUPED) ?: false

    /** SETTINGS-TAB-1: whether some loaded tab names [id] its parent. */
    fun isHost(id: String): Boolean = providers.any { it.parent == id }

    /** SETTINGS-TAB-1: every loaded host tab (one that some loaded tab names its parent), in declared order. */
    fun hostTabs(): List<TabProvider> = providers.filter { isHost(it.id) }.sortedWith(compareBy({ it.order }, { it.id }))

    /** SETTINGS-TAB-1: the tabs that name [hostId] their parent, grouped or not, in declared order. */
    fun groupable(hostId: String): List<TabProvider> = providers.filter { it.parent == hostId }.sortedWith(compareBy({ it.order }, { it.id }))

    /**
     * SETTINGS-TAB-1: the tab [id] is drawn under now, or null when it is drawn in its strip: its declared parent while
     * that parent is loaded and [grouped]. A parent whose module did not load leaves its sub-tabs in the strip.
     */
    fun hostOf(id: String): String? {
        val parent = find(id)?.parent ?: return null
        return parent.takeIf { find(it) != null && grouped(it) }
    }

    /** P1: the node a container's strip settings are stored on (its [NodeRegistry.strips] node). */
    fun stripNode(container: String): String = NodeRegistry.strips.entries.firstOrNull { it.value == container }?.key ?: container

    /** P1 step 2: [tabs] as node [node]'s settings arrange them; never empty while [tabs] is not (the home tab cannot be disabled). */
    private fun arranged(node: String, tabs: List<TabProvider>): List<TabProvider> =
        ChildrenControl.arrange(config, node, sorted(node, tabs), home = tabs.firstOrNull()?.id) { it.id }

    /**
     * P1 step 2 (6s D): [tabs] (declared order) through the [TabSorter] node [node]'s `sorter` field names (a sub-tab host
     * inherits its strip's); the declared order when none resolves. The stored order (Up/Down) still goes first.
     */
    fun sorted(node: String, tabs: List<TabProvider>): List<TabProvider> {
        val key = resolvedConfig.values(node).string(TabSorters.FIELD) ?: return tabs
        val sorter = implementations.get(TabSorters.REGISTRY, key) as? TabSorter ?: return tabs
        return sorter.sort(tabs)
    }

    /** Whether tab [id] is hidden by its strip's (or its parent tab's) settings. */
    fun isDisabled(id: String): Boolean {
        val t = find(id) ?: return false
        val host = hostOf(id)
        return id in ChildrenControl.disabled(config, host ?: stripNode(t.container)) || (host?.let(::isDisabled) ?: false)
    }

    /** P1 step 2: whether [module] is granted node [node] by modules.json (`allowOverride` or `allowInject`). */
    internal fun granted(module: String, node: String): Boolean = GrantPattern.matches(allowOverride(module), node) || GrantPattern.matches(allowInject(module), node)

    fun find(id: String): TabProvider? = providers.firstOrNull { it.id == id }

    /** The tab a container opens on: its first. */
    fun home(container: String): String? = declaredTabs(container).firstOrNull()?.id

    fun byShortcut(digit: Int): TabProvider? = providers.firstOrNull { it.shortcut == digit }

    /** The tab's strip label, through its interceptors (the first loaded is the outermost). */
    fun title(tab: TabProvider, state: AppState): String =
        interceptors[tab.id].orEmpty().foldRight({ tab.title(state) }) { i, proceed -> i.title?.let { t -> { t(state, proceed) } } ?: proceed }()

    /** CONFIG-FIELDS-4: whether [module] reads config through a gate (a community module with the gate on); others read as before. */
    fun configGated(module: String): Boolean = modulesConfig.configReadGate && byModule[module]?.tier == Tier.COMMUNITY

    /** CONFIG-FIELDS-4: whether [module] may read [node]'s config (`ConfigReadGate`). An unknown module is not gated. */
    fun configReadAllows(module: String, node: String): Boolean {
        val m = byModule[module] ?: return true
        return com.superiorsoftllc.infantry.plugin.host.ConfigReadGate.allows(module, m.manifest, m.tier, modulesConfig, node)
    }

    /** The module that draws [id], for the Community badge. */
    fun moduleOf(id: String): InfantryModule? = find(id)?.let { byModule[it.module] }

    /**
     * Draws the tab [id]: its container's top slot, then a community module's badge, its interceptors' before, the
     * content (inside their content wrappers, the first loaded outermost), their after, the container's bottom slot.
     * A tab with none of these draws exactly as its module draws it; an unknown id draws nothing.
     */
    @Composable
    fun Content(id: String, context: TabContext) {
        val tab = find(id) ?: return
        val module = byModule[tab.module]
        // RUNTIME-2: only a core module gets the window controller; official is a program like community (D4).
        val own = context.forModule(services(tab.module))
        val hooks = interceptors[id].orEmpty().filter { it.before != null || it.after != null || it.content != null }
        val top = blocks[Slots.top(tab.container)].orEmpty()
        val bottom = blocks[Slots.bottom(tab.container)].orEmpty()
        val community = module?.tier == Tier.COMMUNITY
        val core = module?.tier.isCoreTier()
        if (!community && hooks.isEmpty() && top.isEmpty() && bottom.isEmpty()) {
            ModuleTier(core) { ModuleScope(tab.module) { tab.content(own) } }
            return
        }
        val inner = hooks.foldRight<TabInterceptor, @Composable () -> Unit>({ ModuleTier(core) { ModuleScope(tab.module) { tab.content(own) } } }) { i, proceed ->
            i.content?.let { wrap -> { wrap(own, proceed) } } ?: proceed
        }
        Column {
            top.forEach { (owner, b) -> ModuleTier(byModule[owner]?.tier.isCoreTier()) { ModuleScope(owner) { b.content(context.forModule(services(owner))) } } }
            if (community) CommunityBadge(module!!.manifest)
            hooks.forEach { it.before?.invoke(own) }
            Box(Modifier.weight(1f)) {
                // A module's plain Text reads in the app's text colour, like the core panes.
                CompositionLocalProvider(LocalContentColor provides Palette.Text) { inner() }
            }
            hooks.forEach { it.after?.invoke(own) }
            bottom.forEach { (owner, b) -> ModuleTier(byModule[owner]?.tier.isCoreTier()) { ModuleScope(owner) { b.content(context.forModule(services(owner))) } } }
        }
    }

    /** AR-7b: the badges every module contributes to session row [agent], in provider order. */
    fun sessionBadgesFor(state: AppState, agent: AgentView): List<SessionBadge> =
        modules.flatMap { it.sessionBadges }.sortedWith(compareBy({ it.order }, { it.id })).mapNotNull { it.badge(state, agent) }

    /** PLUGINS-3: a side panel's sections, in order. */
    fun panels(panel: String): List<PanelProvider> =
        sections.filter { it.panel == panel }.sortedWith(compareBy({ it.order }, { it.id }))

    /** The module that draws section [id], for the Community badge. */
    fun panelModuleOf(id: String): InfantryModule? = sections.firstOrNull { it.id == id }?.let { byModule[it.module] }

    internal fun servicesOf(module: String): PluginServices = services(module)

    fun hasBlocks(slot: String): Boolean = !blocks[slot].isNullOrEmpty()

    /**
     * P1 step 2 (docs/BLOCKS-LAYOUT-DESIGN.md 6s B): the one merged config tree, core's [CoreConfig] then each loaded
     * module's module.json `config`, over the hierarchy of the trees this factory draws (a tab's parent is its strip or
     * parent tab; a tab tree's root is under the tab that draws it). A community module declares only on its own nodes
     * (its tabs, ids under its name) and on those modules.json grants it.
     */
    val configTree: ConfigTree by lazy {
        val parents = LinkedHashMap(ConfigTree.parentsOf(listOf(resolvedShellRoot) + tabTreeRoots.map { resolvedTabTree(it.id) ?: it }))
        val strips = NodeRegistry.strips.entries.associate { (node, container) -> container to node }
        for (t in providers) parents.getOrPut(t.id) { t.parent ?: strips[t.container] ?: t.container }
        for ((tree, tab) in tabTreeTabs) parents.getOrPut(tree) { tab }
        val problems = mutableListOf<String>()
        val core = ConfigTree.parse(CoreConfig.JSON, problems, CORE_SHELL_MODULE)
        // CONFIG-9: a compiled-in build (web, Android) also reads the build step's GeneratedConfig; the desktop reads module.json.
        val generated = if (loadsAtRunTime) emptyMap() else CompiledConfig.declarations(problems)
        val sources = modules.map { m ->
            val name = m.manifest.name
            // EXAMPLE-1: a module owns the nodes of its own tabs and of its own panel sections.
            val own = (m.manifest.tabs.map { it.id } + m.manifest.panels.map { it.id }).toSet()
            val declared = m.manifest.config.flatMap { d -> d.fields.map { d.node to it.key } }.toSet()
            val extra = generated[name].orEmpty().map { d -> d.copy(fields = d.fields.filter { (d.node to it.key) !in declared }) }.filter { it.fields.isNotEmpty() || it.block != null }
            ConfigSource(name, m.tier, m.manifest.config + extra) { node ->
                m.tier != Tier.COMMUNITY || node in own || node.startsWith("$name.") || GrantPattern.matches(allowOverride(name), node) || GrantPattern.matches(allowInject(name), node)
            }
        }
        val merged = ConfigTree.merge(core, sources, parents, ConfigFieldTypes.BUILT_IN + fieldTypes.ids)
        ConfigTree(merged.fields, merged.parents, problems + merged.problems, merged.declarations)
    }

    /** P1 step 2: the stored copy of the config (the daemon's `node_config`), loaded by the window once its link is live. */
    val config: NodeConfigState by lazy {
        NodeConfigState(configTree, configApi, host = if (loadsAtRunTime) "desktop" else "compiled", sources = modules.map { ConfigManifestSource(it.manifest.name, it.manifest.version) }) { ImplementationFields.reconcile(it, configTree, implementations) }
    }

    /** P1 step 2 (6s C): what [com.superiorsoftllc.infantry.ui.shell.ShellContext.config] answers: [config] resolved over [configTree]. */
    val resolvedConfig: ResolvedConfig by lazy { ResolvedConfig(config, implementations) }

    /**
     * P1 step 2 (6s D): every registered implementation, core's ([TabSorters]) first, then each module's in load order.
     * A registration module.json does not declare, or a key a registry already has, is refused with a line.
     */
    val implementations: Implementations by lazy {
        val out = LinkedHashMap<String, LinkedHashMap<String, RegisteredImplementation>>()
        for ((registry, keys) in TabSorters.core) for ((key, value) in keys) out.getOrPut(registry) { LinkedHashMap() }[key] = RegisteredImplementation(registry, key, CORE_SHELL_MODULE, value)
        val problems = mutableListOf<String>()
        for (m in modules) {
            val name = m.manifest.name
            for ((registry, keys) in m.implementations) for ((key, value) in keys) {
                val taken = out[registry]?.get(key)
                when {
                    m.manifest.implementations.none { it.registry == registry && it.key == key } -> problems += "$name: implementation $registry/$key is not declared in its module.json; refused"
                    taken != null -> problems += "$name: implementation $registry/$key is already registered by ${taken.module}; refused"
                    else -> out.getOrPut(registry) { LinkedHashMap() }[key] = RegisteredImplementation(registry, key, name, value)
                }
            }
            for (d in m.manifest.implementations) if (m.implementations[d.registry]?.containsKey(d.key) != true) problems += "$name: implementation ${d.registry}/${d.key} is declared but not registered; skipped"
        }
        Implementations(out, problems)
    }

    /** CONFIG-5: every registered config field type (module.json `fieldTypes` + `UiRegistrar.fieldType`), in load order. */
    val fieldTypes: FieldTypes by lazy {
        FieldTypes.of(modules.map { Triple(it.manifest.name, it.manifest.fieldTypes, it.fieldTypes) })
    }

    /** NS1 / PB1 (6t.2): every registered block type, core's layout primitives first, then each module's in load order. */
    val blockTypes: BlockTypes by lazy {
        BlockTypes.of(modules.map { Triple(it.manifest.name, it.manifest.blockTypes, it.blockTypes) })
    }

    /**
     * NS1 (6t.5): [root] (default the drawn shell) as the page JSON: each node's registered type (or its own
     * [Block.blockType] when it is not a type yet), id, the resolved values of its declared fields (its type's and its
     * node's), children. The modules list is every loaded module with its version.
     */
    fun pageJson(root: Block = shellRoot): JsonObject = PageJson.render(
        root,
        modules.map { it.manifest.name to it.manifest.version },
        typeOf = { blockTypes.typeOf(it) ?: it.blockType },
        config = { b ->
            // A type field not in the config tree yet (instances join it in PB2) falls back to the type's default.
            val keys = LinkedHashMap<String, kotlinx.serialization.json.JsonElement?>()
            blockTypes.typeOf(b)?.let { t -> blockTypes[t]?.declaration?.fields?.forEach { keys[it.key] = it.default } }
            configTree.fields[b.id]?.forEach { keys.getOrPut(it.key) { null } }
            keys.mapNotNull { (k, d) -> (resolvedConfig.json(b.id, k) ?: d)?.let { k to it } }.toMap()
        },
    )
}

/**
 * PLUGINS-3: draws side panel [panel] into this column: its sections in order, with [tabs] (the panel's strip and the
 * picked tab, drawn into a column) at [Panels.TABS_ORDER] taking the free height. A core section draws exactly as its module draws it; a
 * community one sits under the Community badge.
 */
@Composable
fun ColumnScope.PanelSections(factory: TabFactory, panel: String, context: TabContext, tabs: (@Composable ColumnScope.() -> Unit)? = null) {
    val (above, below) = factory.panels(panel).partition { it.order < Panels.TABS_ORDER }
    CompositionLocalProvider(LocalPanelHost provides PanelHost(factory, context)) {
        above.forEach { PanelSection(factory, it, context) }
        if (tabs != null) Column(Modifier.weight(1f).fillMaxWidth()) { tabs() }
        below.forEach { PanelSection(factory, it, context) }
    }
}

/** EXAMPLE-1: the factory and window context of the [PanelSections] being drawn, for a section that hosts a [PanelSlot]. */
internal class PanelHost(val factory: TabFactory, val context: TabContext)

private val LocalPanelHost = staticCompositionLocalOf<PanelHost?> { null }

/**
 * EXAMPLE-1: draws panel slot [panel]'s sections (e.g. [Panels.LEFT_BOARD]) where the calling section puts this call,
 * in order, each as its own module (badge, tier, services). Nothing outside [PanelSections], or when no section fills it.
 */
@Composable
fun ColumnScope.PanelSlot(panel: String) {
    val host = LocalPanelHost.current ?: return
    host.factory.panels(panel).forEach { PanelSection(host.factory, it, host.context) }
}

@Composable
internal fun ColumnScope.PanelSection(factory: TabFactory, section: PanelProvider, context: TabContext) {
    PanelSectionBody(factory, section, context, Modifier.fillMaxWidth().testTag(section.testTag).let { if (section.fill) it.weight(1f) else it })
}

/** CHAT-VOICE-SEAMS-1: one section drawn as its module draws it (tier, badge, services), in a box with [modifier]; any scope. PLUGIN-SLOT-UX-1: [badge] false for inline slot fills (no credit tag). */
@Composable
internal fun PanelSectionBody(factory: TabFactory, section: PanelProvider, context: TabContext, modifier: Modifier, badge: Boolean = true) {
    val module = factory.panelModuleOf(section.id)
    val own = context.forModule(factory.servicesOf(section.module))
    Box(modifier) {
        if (badge && module?.tier == Tier.COMMUNITY) {
            Column {
                CommunityBadge(module.manifest)
                CompositionLocalProvider(LocalContentColor provides Palette.Text) { ModuleTier(false) { ModuleScope(section.module) { section.content(own) } } }
            }
        } else {
            ModuleTier(module?.tier.isCoreTier()) { ModuleScope(section.module) { section.content(own) } }
        }
    }
}

/** P1 step 2: this tree without the injected children [off] names for their parent; the very same object when none applies. */
private fun Block.withoutDisabled(off: (node: String) -> Set<String>): Block {
    if (children.isEmpty()) return this
    val gone = off(id)
    val kids = children.filterNot { it is InjectedBlock && it.id in gone }.map { it.withoutDisabled(off) }
    return if (kids.size == children.size && kids.indices.all { kids[it] === children[it] }) this else withChildren(kids)
}

/** Every node id in the tree under (and including) this block. */
private fun Block.ids(): Set<String> = setOf(id) + children.flatMap { it.ids() }

/** GAP-AUDIT-1 (c): the `/`-joined ids from [root] down to the node [id], or null when it is not in the tree. */
internal fun pathTo(root: Block, id: String): String? {
    if (root.id == id) return root.id
    if (root is com.superiorsoftllc.infantry.ui.block.layout.OverrideBlock) return pathTo(root.inner, id)
    return root.children.firstNotNullOfOrNull { pathTo(it, id) }?.let { "${root.id}/$it" }
}

/** GAP-AUDIT-1 (c): whether [module]'s [mode] override of node [id] is in the resolved tree [root]. */
private fun isDrawn(root: Block, module: String, mode: String, id: String): Boolean = when {
    root is com.superiorsoftllc.infantry.ui.block.layout.OverrideBlock && root.id == id && root.module == module && root.mode == mode -> true
    root is com.superiorsoftllc.infantry.ui.block.layout.InjectedBlock && root.module == module && root.id == "$module.$mode-$id" -> true
    root is com.superiorsoftllc.infantry.ui.block.layout.OverrideBlock -> isDrawn(root.inner, module, mode, id)
    else -> root.children.any { isDrawn(it, module, mode, id) }
}

/**
 * GAP-AUDIT-1 (c): one row of [TabFactory.overrideMap]. [selector] as declared, [node] the id it resolves to (null: no
 * such node), [path] that node's ids from the tree root, [drawn] whether it is applied now, [problem] why not.
 */
data class OverrideEntry(
    val module: String,
    val tier: Tier,
    val mode: String,
    val selector: String,
    val node: String?,
    val path: String?,
    val priority: Int,
    val drawn: Boolean,
    val problem: String?,
)

/** S4: this tree with each empty slot that [fills] names replaced by its fill; the very same object when none applies. */
private fun Block.filled(fills: Map<String, Block>): Block {
    if (fills.isEmpty()) return this
    if (this is PlaceholderBlock && default == null) return fills[id] ?: this
    val kids = children.map { it.filled(fills) }
    return if (kids.indices.all { kids[it] === children[it] }) this else withChildren(kids)
}

/** RUNTIME-2: core rights (window controller, core tier) go to a core module, or to the shell's own (no module), never to official. */
internal fun Tier?.isCoreTier(): Boolean = (this ?: Tier.CORE) == Tier.CORE
