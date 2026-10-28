package com.superiorsoftllc.agenthq.ui.plugin

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import com.superiorsoftllc.agenthq.plugin.api.EventBus
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.PluginEvent
import com.superiorsoftllc.agenthq.plugin.api.PluginServices
import com.superiorsoftllc.agenthq.plugin.api.ServiceKey
import com.superiorsoftllc.agenthq.plugin.api.Services
import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.ui.plugin.config.ChildrenControl
import com.superiorsoftllc.agenthq.ui.plugin.config.ConfigSource
import com.superiorsoftllc.agenthq.ui.plugin.config.ConfigTree
import com.superiorsoftllc.agenthq.ui.plugin.config.CoreConfig
import com.superiorsoftllc.agenthq.ui.plugin.config.DaemonNodeConfigApi
import com.superiorsoftllc.agenthq.ui.plugin.config.ImplementationFields
import com.superiorsoftllc.agenthq.ui.plugin.config.Implementations
import com.superiorsoftllc.agenthq.ui.plugin.config.RegisteredImplementation
import com.superiorsoftllc.agenthq.ui.plugin.config.TabSorter
import com.superiorsoftllc.agenthq.ui.plugin.config.TabSorters
import com.superiorsoftllc.agenthq.ui.plugin.config.NodeConfigApi
import com.superiorsoftllc.agenthq.ui.plugin.config.NodeConfigState
import com.superiorsoftllc.agenthq.ui.plugin.config.ResolvedConfig
import com.superiorsoftllc.agenthq.plugin.host.AcceptedPreference
import com.superiorsoftllc.agenthq.plugin.host.AcceptedShellOverride
import com.superiorsoftllc.agenthq.plugin.host.ModuleState
import com.superiorsoftllc.agenthq.plugin.host.ModuleStatus
import com.superiorsoftllc.agenthq.plugin.host.SimpleEventBus
import com.superiorsoftllc.agenthq.ui.ModuleTier
import com.superiorsoftllc.agenthq.ui.Palette
import com.superiorsoftllc.agenthq.ui.block.layout.Block
import com.superiorsoftllc.agenthq.ui.block.layout.BlockInjection
import com.superiorsoftllc.agenthq.ui.block.layout.BlockOverride
import com.superiorsoftllc.agenthq.ui.block.layout.BlockResolver
import com.superiorsoftllc.agenthq.ui.block.layout.BlockType
import com.superiorsoftllc.agenthq.ui.block.layout.BlockTypes
import com.superiorsoftllc.agenthq.ui.block.layout.PageJson
import com.superiorsoftllc.agenthq.ui.block.layout.PageLayout
import com.superiorsoftllc.agenthq.ui.block.layout.GroupBlock
import kotlinx.serialization.json.JsonObject
import com.superiorsoftllc.agenthq.ui.block.layout.InjectAt
import com.superiorsoftllc.agenthq.ui.block.layout.InjectedBlock
import com.superiorsoftllc.agenthq.ui.block.layout.NodeInjection
import com.superiorsoftllc.agenthq.ui.block.layout.NodeOverride
import com.superiorsoftllc.agenthq.ui.block.layout.PlaceholderBlock
import com.superiorsoftllc.agenthq.ui.block.layout.admitInjections
import com.superiorsoftllc.agenthq.ui.block.layout.Resolved
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.DiffTarget
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.shell.blocks.CoreShell

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
    val ALL = setOf(LEFT, RIGHT)
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
 * asks through these. A core module reaches the window's controller ([coreController]); a community module reaches
 * the daemon only through the gated [services].
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
    /** PLUGINS-3: the window's agents list (search, filters, the list, New agent). Only the left panel has one. */
    val agentsList: @Composable () -> Unit = {},
    /** S4: whether the module this context is for is core tier ([forModule] sets it from the module's tier). */
    private val core: Boolean = true,
) {
    /**
     * S4 (drop-in path, docs/SHELL-LEAKS.md): the window's controller, for a core module's content only; null in a
     * community module's. Since S4 the core modules compile outside this library, so `internal` no longer marks them.
     */
    val coreController: Controller? get() = controller.takeIf { core }

    /** Selects an agent and shows its chat. */
    fun openAgent(sessionId: String) {
        controller.select(sessionId)
        showChat()
    }

    internal fun forModule(services: PluginServices, core: Boolean) =
        TabContext(state, controller, open, showChat, insertIntoChat, diffTarget, onOpenDiff, transcript, services, agentsList, core)
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

    /** S4d: this one with its parts drawn as module tier [core] (a community one's see no [com.superiorsoftllc.agenthq.ui.coreShell]). */
    internal fun atTier(core: Boolean): TabInterceptor = if (core) this else TabInterceptor(
        title,
        before?.let { b -> { c -> ModuleTier(false) { b(c) } } },
        after?.let { a -> { c -> ModuleTier(false) { a(c) } } },
        content?.let { w -> { c, proceed -> ModuleTier(false) { w(c, proceed) } } },
    )
}

/** A block a module adds to a named slot (see [Slots]); bound by the module.json `blocks` entry. */
class SlotBlock(val content: @Composable (TabContext) -> Unit)

/**
 * A module's code: everything it contributes, under the names its module.json uses. Core modules are values in
 * plugins/core; a community module's [UiPlugin] fills one through a [UiRegistrar].
 */
class AgentHqModule(
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
    /** S7: runs once when the host unloads the module (a soft restart replaces the module set, or the app closes it). */
    val onStop: () -> Unit = {},
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
}

/** Runs [plugin]'s registration into a module of [tier]. */
fun registerModule(manifest: ModuleManifest, tier: Tier, services: PluginServices, plugin: UiPlugin): AgentHqModule {
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
    })
    return AgentHqModule(manifest, tabs, interceptors, blocks, observers, tier, panels, shell, inject, fills, tabTrees, implementations, blockTypes, onStop = plugin::stop)
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
 *   registered [AgentHqModule.shell] by name and go into [CoreShell.root] through [BlockResolver]; [shellRoot] is
 *   the tree the window draws ([CoreShell.root] itself when none applies);
 * - BLOCKS-INJECTORS J2: each module's module.json `inject` entries bind to its registered [AgentHqModule.inject] by
 *   name, pass the trust rule ([admitInjections]: a core module freely, a community one only into a target its
 *   [allowInject] lists) and go into the tree that holds their target, through [BlockResolver] like the overrides;
 * - S4 (drop-in path): a core module's declared [AgentHqModule.fills] go into [CoreShell.root]'s empty slots before
 *   anything else ([baseRoot]), so the overrides and injections address their nodes as if the core drew them; an
 *   undeclared fill, one of a community module, of a node that is no slot or of a slot already filled is refused.
 *   The tab trees ([tabTreeRoots]) are the modules' [AgentHqModule.tabTrees], in load order.
 * Everything refused is in [problems]; nothing here throws.
 */
class TabFactory(
    modules: List<AgentHqModule>,
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
    shellOverrides: List<AcceptedShellOverride> = emptyList(),
    /** BLOCKS-INJECTORS J2: the node ids modules.json `allowInject` grants each community module. */
    private val allowInject: (module: String) -> Collection<String> = { emptyList() },
    /** P1 step 2: the core contributions (by id) modules.json `allowOverride` grants each community module; a granted node takes its config. */
    private val allowOverride: (module: String) -> Collection<String> = { emptyList() },
    /** P1 step 2: where the node config is stored (the window's daemon; a test passes a fake). */
    private val configApi: NodeConfigApi = DaemonNodeConfigApi,
) {
    val modules: List<AgentHqModule> = modules.toList()
    /** TOAST-1: where a module's `Notifications.notify` lands in the window; the window sets it to its controller's. */
    var toasts: com.superiorsoftllc.agenthq.ui.ToastService = com.superiorsoftllc.agenthq.ui.NoToastService
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
            baseRoot, nodeOverrides.filter { it.node !in inTabs }, CoreShell.locked,
            injections = nodeInjections.filter { it.target !in inTabs },
        )
    }
    /** BLOCKS-LAYOUT L14: each tab tree ([tabTreeRoots]) with the overrides and injections of its own nodes in, resolved once. */
    private val tabTrees: List<Resolved> by lazy {
        tabTreeRoots.map { t ->
            val ids = t.ids()
            BlockResolver.resolve(t, nodeOverrides.filter { it.node in ids }, CoreShell.locked, t.id, nodeInjections.filter { it.target in ids })
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
    val problems: List<String> get() = bindProblems + shell.problems + tabTrees.flatMap { it.problems } + blockTypes.problems
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
        val placed = LinkedHashMap<String, PanelProvider>()
        for (m in modules) for (p in m.panels) {
            val name = m.manifest.name
            val declared = m.manifest.panels.firstOrNull { it.id == p.id }
            val taken = placed[p.id]
            when {
                declared == null -> problems += "$name: panel section ${p.id} is not declared in its module.json; refused"
                declared.panel != p.panel -> problems += "$name: panel section ${p.id} is declared in ${declared.panel}, not ${p.panel}; refused"
                p.panel !in Panels.ALL -> problems += "$name: panel section ${p.id} names unknown panel ${p.panel}; refused"
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
        nodeOverrides = shellOverrides.mapNotNull { a ->
            val o = byModule[a.module]?.shell?.get(a.name)
            if (o == null) problems += "${a.module}: shell override ${a.name} is declared but not registered; skipped"
            o?.let { NodeOverride(a.module, a.node, a.mode, it, community = byModule[a.module]?.tier == Tier.COMMUNITY) }
        }
        // S4: the fills and the tab trees, core tier only.
        val fills = LinkedHashMap<String, Block>()
        var layoutTypes: BlockTypes? = null
        val fillers = LinkedHashMap<String, String>()
        val trees = LinkedHashMap<String, Pair<String, Block>>()
        for (m in modules) {
            val name = m.manifest.name
            for ((node, b) in m.fills) when {
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
                m.tier == Tier.COMMUNITY && node !in allowOverride(name) -> problems += "$name: layout fill of $node refused: not granted (allowOverride)"
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
    internal fun granted(module: String, node: String): Boolean = node in allowOverride(module) || node in allowInject(module)

    fun find(id: String): TabProvider? = providers.firstOrNull { it.id == id }

    /** The tab a container opens on: its first. */
    fun home(container: String): String? = declaredTabs(container).firstOrNull()?.id

    fun byShortcut(digit: Int): TabProvider? = providers.firstOrNull { it.shortcut == digit }

    /** The tab's strip label, through its interceptors (the first loaded is the outermost). */
    fun title(tab: TabProvider, state: AppState): String =
        interceptors[tab.id].orEmpty().foldRight({ tab.title(state) }) { i, proceed -> i.title?.let { t -> { t(state, proceed) } } ?: proceed }()

    /** The module that draws [id], for the Community badge. */
    fun moduleOf(id: String): AgentHqModule? = find(id)?.let { byModule[it.module] }

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
        val own = context.forModule(services(tab.module), module?.tier.isCoreTier())
        val hooks = interceptors[id].orEmpty().filter { it.before != null || it.after != null || it.content != null }
        val top = blocks[Slots.top(tab.container)].orEmpty()
        val bottom = blocks[Slots.bottom(tab.container)].orEmpty()
        val community = module?.tier == Tier.COMMUNITY
        val core = module?.tier.isCoreTier()
        if (!community && hooks.isEmpty() && top.isEmpty() && bottom.isEmpty()) {
            ModuleTier(core) { tab.content(own) }
            return
        }
        val inner = hooks.foldRight<TabInterceptor, @Composable () -> Unit>({ ModuleTier(core) { tab.content(own) } }) { i, proceed ->
            i.content?.let { wrap -> { wrap(own, proceed) } } ?: proceed
        }
        Column {
            top.forEach { (owner, b) -> ModuleTier(byModule[owner]?.tier.isCoreTier()) { b.content(context.forModule(services(owner), byModule[owner]?.tier.isCoreTier())) } }
            if (community) CommunityBadge(module!!.manifest)
            hooks.forEach { it.before?.invoke(own) }
            Box(Modifier.weight(1f)) {
                // A module's plain Text reads in the app's text colour, like the core panes.
                CompositionLocalProvider(LocalContentColor provides Palette.Text) { inner() }
            }
            hooks.forEach { it.after?.invoke(own) }
            bottom.forEach { (owner, b) -> ModuleTier(byModule[owner]?.tier.isCoreTier()) { b.content(context.forModule(services(owner), byModule[owner]?.tier.isCoreTier())) } }
        }
    }

    /** PLUGINS-3: a side panel's sections, in order. */
    fun panels(panel: String): List<PanelProvider> =
        sections.filter { it.panel == panel }.sortedWith(compareBy({ it.order }, { it.id }))

    /** The module that draws section [id], for the Community badge. */
    fun panelModuleOf(id: String): AgentHqModule? = sections.firstOrNull { it.id == id }?.let { byModule[it.module] }

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
        val sources = modules.map { m ->
            val name = m.manifest.name
            val own = m.manifest.tabs.map { it.id }.toSet()
            ConfigSource(name, m.tier, m.manifest.config) { node ->
                m.tier != Tier.COMMUNITY || node in own || node.startsWith("$name.") || node in allowOverride(name) || node in allowInject(name)
            }
        }
        val merged = ConfigTree.merge(core, sources, parents)
        ConfigTree(merged.fields, merged.parents, problems + merged.problems)
    }

    /** P1 step 2: the stored copy of the config (the daemon's `node_config`), loaded by the window once its link is live. */
    val config: NodeConfigState by lazy {
        NodeConfigState(configTree, configApi) { ImplementationFields.reconcile(it, configTree, implementations) }
    }

    /** P1 step 2 (6s C): what [com.superiorsoftllc.agenthq.ui.shell.ShellContext.config] answers: [config] resolved over [configTree]. */
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
    above.forEach { PanelSection(factory, it, context) }
    if (tabs != null) Column(Modifier.weight(1f).fillMaxWidth()) { tabs() }
    below.forEach { PanelSection(factory, it, context) }
}

@Composable
private fun ColumnScope.PanelSection(factory: TabFactory, section: PanelProvider, context: TabContext) {
    val module = factory.panelModuleOf(section.id)
    val own = context.forModule(factory.servicesOf(section.module), module?.tier.isCoreTier())
    Box(Modifier.fillMaxWidth().testTag(section.testTag).let { if (section.fill) it.weight(1f) else it }) {
        if (module?.tier == Tier.COMMUNITY) {
            Column {
                CommunityBadge(module.manifest)
                CompositionLocalProvider(LocalContentColor provides Palette.Text) { ModuleTier(false) { section.content(own) } }
            }
        } else {
            ModuleTier(module?.tier.isCoreTier()) { section.content(own) }
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

/** S4: this tree with each empty slot that [fills] names replaced by its fill; the very same object when none applies. */
private fun Block.filled(fills: Map<String, Block>): Block {
    if (fills.isEmpty()) return this
    if (this is PlaceholderBlock && default == null) return fills[id] ?: this
    val kids = children.map { it.filled(fills) }
    return if (kids.indices.all { kids[it] === children[it] }) this else withChildren(kids)
}

/** RUNTIME-2: core rights (window controller, core tier) go to a core module, or to the shell's own (no module), never to official. */
internal fun Tier?.isCoreTier(): Boolean = (this ?: Tier.CORE) == Tier.CORE
