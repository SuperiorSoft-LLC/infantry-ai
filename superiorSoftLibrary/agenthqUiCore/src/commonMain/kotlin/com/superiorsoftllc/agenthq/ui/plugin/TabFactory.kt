package com.superiorsoftllc.agenthq.ui.plugin

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.superiorsoftllc.agenthq.ui.model.DiffTarget
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.net.Controller
import kotlinx.serialization.Serializable

/**
 * PLUGINS-1: the named places tabs go. A container is any id; these are the window's two strips. A tab with a
 * [TabProvider.parent] is a sub-tab of that tab (Settings will host its pages that way).
 */
object TabContainers {
    const val CENTER = "center"
    const val RIGHT = "right"
}

/**
 * What a tab's content may reach. The window builds one per strip; a tab never sees the window's own variables, it
 * asks through these.
 */
class TabContext(
    val state: AppState,
    val controller: Controller,
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
) {
    /** Selects an agent and shows its chat. */
    fun openAgent(sessionId: String) {
        controller.select(sessionId)
        showChat()
    }
}

/**
 * One tab, as a module contributes it. The UI never names a tab: [TabFactory] lists a container's providers and
 * draws whichever one is picked.
 */
class TabProvider(
    /** Unique across modules, `vendor.name` (e.g. `infantry.git`). */
    val id: String,
    /** The `Vendor_Module` that contributes it. */
    val module: String,
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
    /** The controller calls the tab makes (enforced once the demo lock lands; declared now). */
    val routes: List<String> = emptyList(),
    /** The events the tab listens to. */
    val events: List<String> = emptyList(),
    val content: @Composable (TabContext) -> Unit,
)

/** A module's identity, the same fields its module.json carries. */
@Serializable
data class ModuleManifest(
    val name: String,
    val version: String,
    val apiVersion: Int = 1,
    val author: String = "",
    val description: String = "",
    /** Modules that must load before this one. */
    val sequence: List<String> = emptyList(),
)

/** A module compiled into the app (every core module, and every module on web and Android). */
class AgentHqModule(val manifest: ModuleManifest, val tabs: List<TabProvider>)

/**
 * The factory the window draws tabs from. Built once from the loaded modules; a second tab with an id already taken
 * is left out and reported in [problems] (replacing a tab on purpose is a preference, which comes with the loader).
 */
class TabFactory(modules: List<AgentHqModule>) {
    val modules: List<AgentHqModule> = modules.toList()
    val problems: List<String>
    private val providers: List<TabProvider>

    init {
        val seen = LinkedHashMap<String, TabProvider>()
        val problems = mutableListOf<String>()
        for (m in modules) for (t in m.tabs) {
            val taken = seen[t.id]
            if (taken != null) problems += "${m.manifest.name}: tab ${t.id} is already provided by ${taken.module}; skipped"
            else seen[t.id] = t
        }
        providers = seen.values.toList()
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

    /** Draws the tab [id]; an id no module provides draws nothing. */
    @Composable
    fun Content(id: String, context: TabContext) {
        find(id)?.content?.invoke(context)
    }
}
