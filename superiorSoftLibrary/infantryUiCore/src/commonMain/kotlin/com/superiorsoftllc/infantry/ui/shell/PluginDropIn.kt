// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.shell

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draganddrop.DragAndDropEvent

/**
 * S6 (drop-in path, docs/SHELL-EMPTY-DROPIN-FEASIBILITY.md section 3b): installing a plugin by dropping a `.ihq` on the
 * shell. Desktop only: [PlatformHost.dropIn] is null on web and Android, so they never draw the drop box. Every call
 * checks the file again; nothing is installed before the owner said Install in the consent dialog.
 */
interface PluginDropIn {
    /** The lockdown is on: nothing may be installed or removed (the plugins folder is never written). */
    val lockdown: Boolean

    /** The shell started in safe mode (`--safe-mode`): no community module is loaded. */
    val safeMode: Boolean

    /** Whether [pickPackage] can show a file dialog here. */
    val canPick: Boolean

    /** Every check the install makes, nothing installed: what the consent dialog shows, or why the file is refused. */
    fun inspect(path: String): DropCheck

    /** Installs [preview]'s file, then writes the grants: the community module is on with [granted] node ids only. Null = installed, else why not. */
    fun install(preview: DropPreview, granted: Set<String>): String?

    /** The installed community modules, by name: what safe mode can remove. */
    fun installed(): List<String>

    /** Removes installed community module [name] (folder and modules.json entry). Null = removed, else why not. */
    fun remove(name: String): String?

    /** A file dialog for a `.ihq`; its path, or null when cancelled. */
    fun pickPackage(): String?

    /** The paths of the files dropped in [event]. */
    fun droppedPaths(event: DragAndDropEvent): List<String>
}

/** S6: [PluginDropIn.inspect]'s answer. */
sealed interface DropCheck {
    data class Ready(val preview: DropPreview) : DropCheck
    data class Refused(val message: String) : DropCheck
}

/** S6: one shell node a dropped plugin asks for. [locked] = in the locked set: refused whatever the owner ticks. */
data class NodeAsk(val node: String, val kind: Kind, val locked: Boolean) {
    enum class Kind { OVERRIDE, INJECT, FILL }

    /** A community module gets this node only when the owner ticks it; a core one gets every unlocked node it asks for. */
    fun grantable(core: Boolean): Boolean = !core && !locked && kind != Kind.FILL
}

/** S6: what the consent dialog shows about a checked file. [core] = signed with a trusted (Infantry) key. */
data class DropPreview(
    val path: String,
    val name: String,
    val version: String,
    val author: String?,
    val signer: String?,
    val core: Boolean,
    val asks: List<NodeAsk>,
) {
    /** It fills the shell's empty slots: it is a whole interface, not an addition to one. */
    val replacesInterface: Boolean get() = core && asks.any { it.kind == NodeAsk.Kind.FILL && !it.locked }

    /** S7: who signed it, in words: the common name of the signer (`CN=Acme Ltd, O=Acme` gives `Acme Ltd`), else the whole name; null = unsigned. */
    val signerName: String? get() = signer?.let { commonName(it) ?: it }

    companion object {
        /** The CN of an RFC 2253 distinguished name (`\,` escapes a comma), or null when it has none. */
        fun commonName(dn: String): String? {
            val rdn = Regex("""(?:^|,\s*)CN=((?:\\.|[^,])+)""").find(dn)?.groupValues?.get(1) ?: return null
            return rdn.replace(Regex("""\\(.)"""), "$1").trim().takeIf { it.isNotEmpty() }
        }
    }
}

/**
 * S6: the soft restart: the shell rebuilds its module set ([PlatformHost.reloadTabs]) and composes the window again with
 * it, in the same process. Null where the window cannot (a test window drawn with a fixed module set).
 */
val LocalSoftRestart = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * PLUGIN-HOTSWAP-1 (docs/wiki/Plugin-lifecycle.md): what [PlatformHost.swapPlugins] applied live: the module set the window
 * draws from now on, the line to show, and the leak check to run once the window has let go of the old versions.
 */
class PluginSwap(
    val factory: com.superiorsoftllc.infantry.ui.plugin.TabFactory,
    val message: String,
    /** The swapped-out modules whose class loader is still reachable after the host's timeout (blocking; run off the UI thread). */
    val leaked: () -> List<String>,
)

/**
 * PLUGIN-HOTSWAP-1: the shell's live plugin swap, for Settings > Plugins. [apply] applies what changed in the plugins root
 * to only the plugins it touches (no soft restart) and answers the line to show; where the host cannot swap live, or a
 * swapped-out plugin stays in memory, it falls back to the soft restart ([LocalSoftRestart]) and [warnings] says why.
 */
class PluginLifecycle(
    /** Module -> why it did not unload cleanly (the last swap that found it still in memory). */
    val warnings: Map<String, String>,
    val apply: () -> String,
)

/**
 * UI-PLUGIN-BRIDGE-1: the window's one [PluginLifecycle] for code that is not composable (a client capability answers a daemon
 * call on a coroutine, not in a composition). The shell sets it while it is shown and clears it when it leaves; the Plugins
 * tab and a capability both call the same [PluginLifecycle.apply], so there is one swap path.
 */
object PluginLifecycleBridge {
    @kotlin.concurrent.Volatile var current: PluginLifecycle? = null

    /** PLUGIN-APPLY-2: the host's plugin manager, so `plugins.apply` with an `action` changes the state before it swaps. */
    @kotlin.concurrent.Volatile var manager: com.superiorsoftllc.infantry.plugin.api.PluginManagerPort? = null
}

/** PLUGIN-HOTSWAP-1: the shell's [PluginLifecycle]; null where the window has none (a test window). */
val LocalPluginLifecycle = staticCompositionLocalOf<PluginLifecycle?> { null }
