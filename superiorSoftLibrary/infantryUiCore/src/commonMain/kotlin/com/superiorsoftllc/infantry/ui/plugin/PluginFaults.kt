// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.TextButton

/** PLUGIN-CRASH-CONTAIN-1: what a plugin threw, and the reload [generation] it threw in (a reload clears it). */
data class PluginFault(val module: String, val message: String, val generation: Int, val trace: String)

/**
 * PLUGIN-CRASH-CONTAIN-1: the host's record of plugins that threw. A faulted plugin's slot items are not drawn until the
 * module is reloaded (its generation changes); the rest of the app keeps running. Fed by the section guard (a section that
 * threw in its first composition) and by the window exception handler ([handle]: an exception from input or a later
 * recomposition, attributed to a plugin from its stack by [claim]ed package prefixes).
 */
object PluginFaults {
    private val byModule = mutableStateMapOf<String, PluginFault>()
    private val packages = mutableMapOf<String, String>()

    /** The newest exception no plugin owned (an in-app banner shows it); null when none or dismissed. */
    val hostError = mutableStateOf<String?>(null)

    /** A module's current reload generation; the window sets it from its [TabFactory] so [handle] stamps faults correctly. */
    @kotlin.concurrent.Volatile
    var generationLookup: (String) -> Int = { 0 }

    val all: List<PluginFault> get() = byModule.values.toList()

    /** The fault of [module] if it happened in [generation] (a reloaded module draws again). */
    fun faultOf(module: String, generation: Int): PluginFault? = byModule[module]?.takeIf { it.generation == generation }

    fun record(module: String, error: Throwable, generation: Int) {
        byModule[module] = PluginFault(module, error.toString().take(400), generation, error.stackTraceToString().take(4000))
    }

    fun clear(module: String) { byModule.remove(module) }

    /** [module]'s classes live under [packagePrefix] (its entry class's package); the loader calls this. */
    fun claim(module: String, packagePrefix: String) { synchronized(packages) { packages[packagePrefix] = module } }

    /** The module whose claimed package a frame of [error] (or its causes) is in, or null. */
    fun owner(error: Throwable): String? {
        val seen = HashSet<Throwable>()
        var t: Throwable? = error
        val claims = synchronized(packages) { packages.toMap() }
        while (t != null && seen.add(t)) {
            for (f in t.stackTrace) claims.entries.firstOrNull { f.className.startsWith(it.key + ".") }?.let { return it.value }
            t = t.cause
        }
        return null
    }

    /**
     * The window exception handler's body: never a modal. A plugin's exception marks that plugin faulted ([generationOf]
     * its current reload generation); any other goes to [hostError]. Returns the module, or null.
     */
    fun handle(error: Throwable, generationOf: (String) -> Int = { generationLookup(it) }): String? {
        System.err.println("[infantry-ui] uncaught UI exception: ${error.stackTraceToString().take(2000)}")
        val module = owner(error)
        if (module != null) record(module, error, generationOf(module)) else hostError.value = error.toString().take(400)
        return module
    }

    /** Tests. */
    fun reset() { byModule.clear(); synchronized(packages) { packages.clear() }; hostError.value = null }
}

const val FAULTED_PLUGIN_TAG_PREFIX = "plugin-faulted-"

/** PLUGIN-CRASH-CONTAIN-1: Settings > Plugins: each faulted plugin with its message, Disable and Report. */
@Composable
fun FaultedPluginsList(factory: TabFactory) {
    val faults = PluginFaults.all.filter { it.generation == factory.generation(it.module) }
    if (faults.isEmpty()) return
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (f in faults) {
            Row(Modifier.testTag(FAULTED_PLUGIN_TAG_PREFIX + f.module), verticalAlignment = Alignment.CenterVertically) {
                Text("${f.module} faulted and is not drawn: ${f.message}", color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                factory.changes?.let { c -> TextButton({ c.request(ModuleRequest(f.module, enable = false)) }) { Text("Disable") } }
                TextButton({ clipboard.setText(AnnotatedString("Plugin ${f.module} faulted:\n${f.trace}")) }) { Text("Report") }
            }
        }
    }
}
