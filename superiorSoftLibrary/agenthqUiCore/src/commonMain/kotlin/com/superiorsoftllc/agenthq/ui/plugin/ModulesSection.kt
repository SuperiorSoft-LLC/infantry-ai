package com.superiorsoftllc.agenthq.ui.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.plugin.host.DependencyGraph
import com.superiorsoftllc.agenthq.plugin.host.ModuleChange
import com.superiorsoftllc.agenthq.plugin.host.ModuleState
import com.superiorsoftllc.agenthq.plugin.host.ModuleStatus
import com.superiorsoftllc.agenthq.ui.Checkbox
import com.superiorsoftllc.agenthq.ui.Chip
import com.superiorsoftllc.agenthq.ui.Palette
import com.superiorsoftllc.agenthq.ui.TextButton

/** PLUGINS-1: Settings > Plugins > Modules. */
object ModulesSetting {
    const val TITLE = "Modules"
    const val APPLIES_TO = "This computer. Every module found under the plugins root, and whether it loaded. Community modules " +
        "stay off until modules.json enables them; the Community label is a trust label, not a sandbox: a loaded module runs " +
        "inside the app with its full access. Enable or disable a module here (or edit plugins/modules.json) and restart."
}

/** PLUGINS-2: enable ([enable] true) or disable [module]; [cascade] also disables the enabled modules that need it. */
data class ModuleRequest(val module: String, val enable: Boolean, val cascade: Boolean = false)

/** PLUGINS-2: the host's way to change modules.json; the dependency rules decide, the answer says what happened. */
fun interface ModuleChanges {
    fun request(request: ModuleRequest): ModuleChange
}

const val MODULE_ROW_TAG_PREFIX = "plugin-module-"
const val MODULE_NEEDS_TAG_PREFIX = "plugin-module-needs-"
const val MODULE_REQUIRED_BY_TAG_PREFIX = "plugin-module-required-by-"
const val MODULE_TOGGLE_TAG_PREFIX = "plugin-module-toggle-"
const val MODULE_MESSAGE_TAG_PREFIX = "plugin-module-message-"
const val MODULE_CASCADE_TAG = "plugin-modules-cascade"

/**
 * One row per module: name, version, state and why, what it needs and what needs it; a community module also shows
 * its badge (author and license). With [TabFactory.changes] each row can be enabled or disabled; a refusal is shown
 * under the row, in the rules' own words.
 */
@Composable
fun ModulesSection(factory: TabFactory) {
    val graph = remember(factory) { DependencyGraph.of(factory.statuses) }
    // What this session changed in modules.json (it applies at the next start), and the last answer per module.
    val enabledNow = remember(factory) { mutableStateMapOf<String, Boolean>() }
    val messages = remember(factory) { mutableStateMapOf<String, ModuleChange>() }
    var cascade by remember(factory) { mutableStateOf(false) }
    val changes = factory.changes

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (changes != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(cascade, { cascade = it }, Modifier.testTag(MODULE_CASCADE_TAG))
                Text("When disabling, also disable the modules that need it (cascade)", color = Palette.Text, fontSize = 12.sp)
            }
        }
        for (s in factory.statuses) {
            ModuleRow(
                s, graph,
                enabled = enabledNow[s.name] ?: (s.state != ModuleState.DISABLED),
                message = messages[s.name],
                onToggle = changes?.let { c ->
                    { on: Boolean ->
                        val answer = c.request(ModuleRequest(s.name, on, cascade))
                        messages[s.name] = answer
                        if (answer is ModuleChange.Applied) for (n in answer.changed) enabledNow[n] = on
                    }
                },
            )
        }
        for (p in factory.problems) Text(p, color = Palette.Muted, fontSize = 11.sp)
    }
}

@Composable
private fun ModuleRow(s: ModuleStatus, graph: DependencyGraph, enabled: Boolean, message: ModuleChange?, onToggle: ((Boolean) -> Unit)?) {
    Column(Modifier.testTag(MODULE_ROW_TAG_PREFIX + s.name), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val color = when (s.state) {
                ModuleState.LOADED -> Palette.Accent
                else -> Palette.Muted
            }
            Chip(s.state.name.lowercase(), color)
            Text(s.name + (s.manifest?.version?.let { " $it" } ?: ""), color = Palette.Text, fontSize = 12.sp)
            if (s.tier == Tier.COMMUNITY) Chip("Community", Palette.Accent)
            if (onToggle != null && s.manifest != null) {
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { onToggle(!enabled) },
                    modifier = Modifier.height(28.dp).testTag(MODULE_TOGGLE_TAG_PREFIX + s.name),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) { Text(if (enabled) "Disable" else "Enable", fontSize = 12.sp) }
            }
        }
        val needs = graph.needs(s.name)
        if (needs.isNotEmpty()) {
            Text("needs: " + needs.joinToString(", ") { DependencyGraph.label(it) }, Modifier.testTag(MODULE_NEEDS_TAG_PREFIX + s.name), color = Palette.Muted, fontSize = 11.sp)
        }
        val requiredBy = graph.requiredBy(s.name)
        if (requiredBy.isNotEmpty()) {
            Text("required by: " + requiredBy.joinToString(", "), Modifier.testTag(MODULE_REQUIRED_BY_TAG_PREFIX + s.name), color = Palette.Muted, fontSize = 11.sp)
        }
        if (s.tier == Tier.COMMUNITY) s.manifest?.let { Text(communityByline(it), color = Palette.Muted, fontSize = 11.sp) }
        s.reason?.let { Text(it, color = Palette.Muted, fontSize = 11.sp) }
        message?.let {
            Text(
                it.message,
                Modifier.testTag(MODULE_MESSAGE_TAG_PREFIX + s.name).semantics { liveRegion = LiveRegionMode.Polite },
                color = if (it is ModuleChange.Refused) Palette.Danger else Palette.Ok,
                fontSize = 11.sp,
            )
        }
    }
}
