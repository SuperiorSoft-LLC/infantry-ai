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
