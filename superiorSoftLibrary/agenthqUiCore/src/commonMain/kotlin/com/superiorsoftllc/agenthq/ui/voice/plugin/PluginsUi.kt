package com.superiorsoftllc.agenthq.ui.voice.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import com.superiorsoftllc.agenthq.ui.OutlinedButton
import com.superiorsoftllc.agenthq.ui.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Test tags: each takes the plugin id as suffix. */
const val PLUGIN_ROW_TAG_PREFIX = "plugin-row-"
const val PLUGIN_INSTALL_TAG_PREFIX = "plugin-install-"
const val PLUGIN_REMOVE_TAG_PREFIX = "plugin-remove-"
const val PLUGIN_ENABLED_TAG_PREFIX = "plugin-enabled-"
const val PLUGIN_STATUS_TAG_PREFIX = "plugin-status-"
const val PLUGINS_EMPTY_TAG = "plugins-empty"

/**
 * VOICE-PLUGIN-1: Configuration > Plugins. Lists the catalog and what is installed; Install (download size shown) is the
 * only thing that downloads; Remove deletes the plugin's folder; the switch picks which plugin speaks (one at most).
 * Every [plugins] call runs off the main thread.
 */
@Composable
fun PluginsSection(plugins: VoicePlugins) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<VoicePluginRow>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    suspend fun reload() { rows = withContext(Dispatchers.Default) { runCatching { plugins.rows() }.getOrDefault(emptyList()) } }
    LaunchedEffect(plugins) { reload() }

    fun act(id: String, verb: String, work: () -> VoicePluginOutcome?) {
        if (busy != null) return
        busy = id; progress = null; message = null
        scope.launch {
            val out = withContext(Dispatchers.Default) {
                runCatching { work() }.getOrElse { VoicePluginOutcome("failed", it.message ?: it::class.simpleName) }
            }
            if (out?.result == "failed") message = "$verb failed: ${out.message ?: "unknown reason"}"
            reload()
            busy = null; progress = null
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val list = rows
        when {
            list == null -> Text("Loading...", color = Palette.Muted, fontSize = 12.sp)
            list.isEmpty() -> Text("No plugins are offered for this computer.", color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.testTag(PLUGINS_EMPTY_TAG))
            else -> list.forEach { r ->
                Column(Modifier.testTag(PLUGIN_ROW_TAG_PREFIX + r.id), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(r.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    if (r.description.isNotBlank()) Text(r.description, fontSize = 12.sp)
                    if (r.license.isNotBlank()) Text("Licence: ${r.license}", color = Palette.Muted, fontSize = 11.sp)
                    Text(PluginsSetting.statusText(r), color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.testTag(PLUGIN_STATUS_TAG_PREFIX + r.id))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (r.state == "missing") {
                            OutlinedButton(
                                onClick = { act(r.id, "Install") { plugins.install(r.id) { p -> progress = p } } },
                                enabled = busy == null && r.downloadBytes > 0,
                                modifier = Modifier.testTag(PLUGIN_INSTALL_TAG_PREFIX + r.id),
                            ) { Text(PluginsSetting.installLabel(r), fontSize = 12.sp) }
                        } else {
                            Switch(
                                checked = r.enabled,
                                onCheckedChange = { on -> act(r.id, "Change") { plugins.setEnabled(r.id, on); null } },
                                enabled = busy == null && (r.state == "installed" || r.enabled),
                                modifier = Modifier.testTag(PLUGIN_ENABLED_TAG_PREFIX + r.id),
                            )
                            Text(if (r.enabled) "Use this voice" else "Not used", fontSize = 12.sp)
                            OutlinedButton(
                                onClick = { act(r.id, "Remove") { plugins.remove(r.id) } },
                                enabled = busy == null,
                                modifier = Modifier.testTag(PLUGIN_REMOVE_TAG_PREFIX + r.id),
                            ) { Text("Remove", fontSize = 12.sp) }
                        }
                    }
                    if (busy == r.id) Text(progress ?: "Working...", color = Palette.Muted, fontSize = 11.sp)
                }
            }
        }
        message?.let { Text(it, color = Palette.Danger, fontSize = 11.sp) }
    }
}
