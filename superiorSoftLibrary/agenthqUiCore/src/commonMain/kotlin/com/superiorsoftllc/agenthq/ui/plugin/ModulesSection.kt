package com.superiorsoftllc.agenthq.ui.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.plugin.host.ModuleState
import com.superiorsoftllc.agenthq.ui.Chip
import com.superiorsoftllc.agenthq.ui.Palette

/** PLUGINS-1: Settings > Plugins > Modules. */
object ModulesSetting {
    const val TITLE = "Modules"
    const val APPLIES_TO = "This computer. Every module found under the plugins root, and whether it loaded. Community modules " +
        "stay off until modules.json enables them; the Community label is a trust label, not a sandbox: a loaded module runs " +
        "inside the app with its full access. Edit plugins/modules.json and restart to change this list."
}

const val MODULE_ROW_TAG_PREFIX = "plugin-module-"

/** One row per module: name, version, state and why; a community module also shows its badge (author and license). */
@Composable
fun ModulesSection(factory: TabFactory) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (s in factory.statuses) {
            Column(Modifier.testTag(MODULE_ROW_TAG_PREFIX + s.name), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val color = when (s.state) {
                        ModuleState.LOADED -> Palette.Accent
                        else -> Palette.Muted
                    }
                    Chip(s.state.name.lowercase(), color)
                    Text(s.name + (s.manifest?.version?.let { " $it" } ?: ""), color = Palette.Text, fontSize = 12.sp)
                    if (s.tier == Tier.COMMUNITY) Chip("Community", Palette.Accent)
                }
                if (s.tier == Tier.COMMUNITY) s.manifest?.let { Text(communityByline(it), color = Palette.Muted, fontSize = 11.sp) }
                s.reason?.let { Text(it, color = Palette.Muted, fontSize = 11.sp) }
            }
        }
        for (p in factory.problems) Text(p, color = Palette.Muted, fontSize = 11.sp)
    }
}
