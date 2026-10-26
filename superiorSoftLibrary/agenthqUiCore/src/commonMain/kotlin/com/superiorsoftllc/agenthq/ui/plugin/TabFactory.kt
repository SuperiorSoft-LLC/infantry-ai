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
import com.superiorsoftllc.agenthq.plugin.host.AcceptedPreference
import com.superiorsoftllc.agenthq.plugin.host.ModuleState
import com.superiorsoftllc.agenthq.plugin.host.ModuleStatus
import com.superiorsoftllc.agenthq.plugin.host.SimpleEventBus
import com.superiorsoftllc.agenthq.ui.Palette
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.DiffTarget
import com.superiorsoftllc.agenthq.ui.net.Controller

/**
 * PLUGINS-1: the named places tabs go. A container is any id; these are the window's two strips. A tab with a
 * [TabProvider.parent] is a sub-tab of that tab (Settings will host its pages that way).
 */
object TabContainers {
    const val CENTER = "center"
    const val RIGHT = "right"
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

/** PLUGINS-1: the named slots a module can add a [SlotBlock] to. A slot with no block draws nothing. */
object Slots {
    /** Above a container's tab content, on every tab of it: `<container>.top`. */
    fun top(container: String) = "$container.top"
    val CENTER_TOP = top(TabContainers.CENTER)
    val RIGHT_TOP = top(TabContainers.RIGHT)
}

/** A module's services when it has none (compiled-in core on web and Android, and tests). */
object NoPluginServices : PluginServices {
    override val module = ""
    override fun <T : Any> get(key: ServiceKey<T>): T? = null
}

/**
 * What a tab's content may reach. The window builds one per strip; a tab never sees the window's own variables, it
 * asks through these. [controller] is internal: core modules (compiled into this library) use it, a community module
 * reaches the daemon only through the gated [services].
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
) {
    /** Selects an agent and shows its chat. */
    fun openAgent(sessionId: String) {
        controller.select(sessionId)
        showChat()
    }

    internal fun forModule(services: PluginServices) =
        TabContext(state, controller, open, showChat, insertIntoChat, diffTarget, onOpenDiff, transcript, services, agentsList)
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
 * An interceptor on one tab (Magento's plugin): [title] runs around the tab's own title (call `proceed`), [before] and
 * [after] draw above and below its content. Bound to a tab by the module's module.json `interceptors` entry.
 */
class TabInterceptor(
    val title: ((state: AppState, proceed: () -> String) -> String)? = null,
    val before: (@Composable (TabContext) -> Unit)? = null,
    val after: (@Composable (TabContext) -> Unit)? = null,
)

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
)

/**
 * PLUGINS-1: the entry point of a module loaded at run time (module.json `entry`). It registers its contributions by
 * the names module.json declares; the host binds them, and refuses what is not declared.
 */
interface UiPlugin {
    fun register(registrar: UiRegistrar)
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
}

/** Runs [plugin]'s registration into a module of [tier]. */
fun registerModule(manifest: ModuleManifest, tier: Tier, services: PluginServices, plugin: UiPlugin): AgentHqModule {
    val tabs = mutableListOf<TabProvider>()
    val panels = mutableListOf<PanelProvider>()
    val interceptors = LinkedHashMap<String, TabInterceptor>()
    val blocks = LinkedHashMap<String, SlotBlock>()
    val observers = LinkedHashMap<String, (PluginEvent) -> Unit>()
    plugin.register(object : UiRegistrar {
        override val services = services
        override fun tab(provider: TabProvider) { tabs += provider }
        override fun panel(provider: PanelProvider) { panels += provider }
        override fun interceptor(name: String, interceptor: TabInterceptor) { interceptors[name] = interceptor }
        override fun block(name: String, block: SlotBlock) { blocks[name] = block }
        override fun observer(name: String, observer: (PluginEvent) -> Unit) { observers[name] = observer }
    })
    return AgentHqModule(manifest, tabs, interceptors, blocks, observers, tier, panels)
}

/**
 * The factory the window draws tabs from, built once from the loaded modules (in load order):
 * - a tab its module.json does not declare, or declares in another container, is refused;
 * - a second tab with an id already taken is refused (replacing one on purpose is a [preferences] entry, which the
 *   loader's trust rules have already let through);
 * - a preference puts the replacing tab in the replaced one's place (id, container, order, shortcut, tag); the
 *   replacing tab is not shown under its own id;
 * - interceptors, blocks and observers are bound by their declared names; an undeclared or unknown one is refused.
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
) {
    val modules: List<AgentHqModule> = modules.toList()
    /** Whether a loader looked at a plugins root (the desktop); false = compiled-in modules only (web, Android, tests). */
    val loadsAtRunTime: Boolean = statuses != null
    val statuses: List<ModuleStatus> = statuses ?: modules.map { ModuleStatus(it.manifest.name, it.tier, ModuleState.LOADED, manifest = it.manifest) }
    val problems: List<String>
    private val providers: List<TabProvider>
    private val sections: List<PanelProvider>
    private val interceptors: Map<String, List<TabInterceptor>>
    private val blocks: Map<String, List<Pair<String, SlotBlock>>>
    private val byModule = modules.associateBy { it.manifest.name }

    init {
        val problems = mutableListOf<String>()
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
        for (p in preferences) {
            val target = seen[p.replace]
            val with = seen[p.with]?.takeIf { it.module == p.module }
            if (target == null || with == null) {
                problems += "${p.module}: preference ${p.replace} -> ${p.with} has no tab to apply to; skipped"
                continue
            }
            seen.remove(p.with)
            seen[p.replace] = with.copy(id = target.id, container = target.container, order = target.order, parent = target.parent, shortcut = target.shortcut, testTag = target.testTag)
        }
        // A tab its module offers as a replacement shows only as one: a refused preference leaves no stray tab behind.
        for (m in modules) for (p in m.manifest.preferences) {
            if (preferences.none { it.module == m.manifest.name && it.with == p.with } && seen[p.with]?.module == m.manifest.name) {
                seen.remove(p.with)
                problems += "${m.manifest.name}: tab ${p.with} only replaces ${p.replace}, which was not accepted; not shown"
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
        sections = placed.values.toList()
        val ints = LinkedHashMap<String, MutableList<TabInterceptor>>()
        val bls = LinkedHashMap<String, MutableList<Triple<Int, String, SlotBlock>>>()
        for (m in modules) {
            val name = m.manifest.name
            for (d in m.manifest.interceptors) {
                val i = m.interceptors[d.name]
                when {
                    i == null -> problems += "$name: interceptor ${d.name} is declared but not registered; skipped"
                    d.target !in seen -> problems += "$name: interceptor ${d.name} targets ${d.target}, which no loaded module provides; skipped"
                    else -> ints.getOrPut(d.target) { mutableListOf() } += i
                }
            }
            for (n in m.interceptors.keys - m.manifest.interceptors.map { it.name }.toSet()) problems += "$name: interceptor $n is not declared in its module.json; refused"
            for (d in m.manifest.blocks) {
                val b = m.blocks[d.name]
                if (b == null) problems += "$name: block ${d.name} is declared but not registered; skipped" else bls.getOrPut(d.slot) { mutableListOf() } += Triple(d.order, name, b)
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
        }
        providers = seen.values.toList()
        interceptors = ints
        blocks = bls.mapValues { (_, v) -> v.sortedBy { it.first }.map { it.second to it.third } }
        this.problems = problems
    }

    /** A container's top-level tabs, in strip order. */
    fun tabs(container: String): List<TabProvider> =
        providers.filter { it.container == container && it.parent == null }.sortedWith(compareBy({ it.order }, { it.id }))

    /** The sub-tabs of [parentId], in order. */
    fun children(parentId: String): List<TabProvider> =
        providers.filter { it.parent == parentId }.sortedWith(compareBy({ it.order }, { it.id }))

    fun find(id: String): TabProvider? = providers.firstOrNull { it.id == id }

    /** The tab a container opens on: its first. */
    fun home(container: String): String? = tabs(container).firstOrNull()?.id

    fun byShortcut(digit: Int): TabProvider? = providers.firstOrNull { it.shortcut == digit }

    /** The tab's strip label, through its interceptors (the first loaded is the outermost). */
    fun title(tab: TabProvider, state: AppState): String =
        interceptors[tab.id].orEmpty().foldRight({ tab.title(state) }) { i, proceed -> i.title?.let { t -> { t(state, proceed) } } ?: proceed }()

    /** The module that draws [id], for the Community badge. */
    fun moduleOf(id: String): AgentHqModule? = find(id)?.let { byModule[it.module] }

    /**
     * Draws the tab [id]: its container's top slot, then a community module's badge, its interceptors' before, the
     * content, their after. A tab with none of these draws exactly as its module draws it; an unknown id draws nothing.
     */
    @Composable
    fun Content(id: String, context: TabContext) {
        val tab = find(id) ?: return
        val module = byModule[tab.module]
        val own = context.forModule(services(tab.module))
        val hooks = interceptors[id].orEmpty().filter { it.before != null || it.after != null }
        val slot = blocks[Slots.top(tab.container)].orEmpty()
        val community = module?.tier == Tier.COMMUNITY
        if (!community && hooks.isEmpty() && slot.isEmpty()) {
            tab.content(own)
            return
        }
        Column {
            slot.forEach { (owner, b) -> b.content(context.forModule(services(owner))) }
            if (community) CommunityBadge(module!!.manifest)
            hooks.forEach { it.before?.invoke(own) }
            Box(Modifier.weight(1f)) {
                // A module's plain Text reads in the app's text colour, like the core panes.
                CompositionLocalProvider(LocalContentColor provides Palette.Text) { tab.content(own) }
            }
            hooks.forEach { it.after?.invoke(own) }
        }
    }

    /** PLUGINS-3: a side panel's sections, in order. */
    fun panels(panel: String): List<PanelProvider> =
        sections.filter { it.panel == panel }.sortedWith(compareBy({ it.order }, { it.id }))

    /** The module that draws section [id], for the Community badge. */
    fun panelModuleOf(id: String): AgentHqModule? = sections.firstOrNull { it.id == id }?.let { byModule[it.module] }

    internal fun servicesOf(module: String): PluginServices = services(module)

    fun hasBlocks(slot: String): Boolean = !blocks[slot].isNullOrEmpty()
}

/**
 * PLUGINS-3: draws side panel [panel] into this column: its sections in order, with [tabs] (the panel's strip and the
 * picked tab) at [Panels.TABS_ORDER] taking the free height. A core section draws exactly as its module draws it; a
 * community one sits under the Community badge.
 */
@Composable
fun ColumnScope.PanelSections(factory: TabFactory, panel: String, context: TabContext, tabs: (@Composable () -> Unit)? = null) {
    val (above, below) = factory.panels(panel).partition { it.order < Panels.TABS_ORDER }
    above.forEach { PanelSection(factory, it, context) }
    if (tabs != null) Column(Modifier.weight(1f).fillMaxWidth()) { tabs() }
    below.forEach { PanelSection(factory, it, context) }
}

@Composable
private fun ColumnScope.PanelSection(factory: TabFactory, section: PanelProvider, context: TabContext) {
    val module = factory.panelModuleOf(section.id)
    val own = context.forModule(factory.servicesOf(section.module))
    Box(Modifier.fillMaxWidth().testTag(section.testTag).let { if (section.fill) it.weight(1f) else it }) {
        if (module?.tier == Tier.COMMUNITY) {
            Column {
                CommunityBadge(module.manifest)
                CompositionLocalProvider(LocalContentColor provides Palette.Text) { section.content(own) }
            }
        } else {
            section.content(own)
        }
    }
}
