// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
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
import com.superiorsoftllc.infantry.plugin.api.ActionResult
import com.superiorsoftllc.infantry.plugin.api.HitView
import com.superiorsoftllc.infantry.plugin.api.InspectOutcome
import com.superiorsoftllc.infantry.plugin.api.InspectView
import com.superiorsoftllc.infantry.plugin.api.PluginManagerPort
import com.superiorsoftllc.infantry.plugin.api.PluginView
import com.superiorsoftllc.infantry.plugin.api.RefreshView
import com.superiorsoftllc.infantry.plugin.api.SourceView
import com.superiorsoftllc.infantry.plugin.api.UpdateView
import com.superiorsoftllc.infantry.ui.Button
import com.superiorsoftllc.infantry.ui.Checkbox
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.TextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val PLUGINS_TAB_ID = "infantry.settings.plugins"
const val PLUGINS_SCREEN_TAG = "plugins-screen"
const val PLUGINS_BANNER_TAG = "plugins-restart-banner"
const val PLUGINS_NOTE_TAG = "plugins-note"
const val PLUGINS_SECTION_PREFIX = "plugins-section-"
const val PLUGIN_ROW_PREFIX = "plugin-row-"
const val PLUGIN_ENABLE_PREFIX = "plugin-enable-"
const val PLUGIN_UPDATE_PREFIX = "plugin-update-"
const val PLUGIN_ROLLBACK_PREFIX = "plugin-rollback-"
const val PLUGIN_REMOVE_PREFIX = "plugin-remove-"
const val BROWSE_QUERY_TAG = "plugins-browse-query"
const val BROWSE_SEARCH_TAG = "plugins-browse-search"
const val BROWSE_INSTALL_PREFIX = "plugins-browse-install-"
const val FILE_PATH_TAG = "plugins-file-path"
const val FILE_INSTALL_TAG = "plugins-file-install"
const val SOURCE_ID_TAG = "plugins-source-id"
const val SOURCE_LOCATION_TAG = "plugins-source-location"
const val SOURCE_ADD_TAG = "plugins-source-add"
const val SOURCE_REMOVE_PREFIX = "plugins-source-remove-"
const val SOURCE_REFRESH_TAG = "plugins-source-refresh"

private val SECTIONS = listOf("Installed", "Browse", "Sources")

private typealias Act = (() -> ActionResult, (ActionResult) -> Unit) -> Unit

/** What the consent dialog is for: [run] applies the ticked grants and answers. */
private class Consent(val title: String, val view: InspectView, val run: (Set<String>) -> ActionResult)

/**
 * PLUGIN-UI-1 (PM-8): Settings > Plugins, desktop only. Installed / Browse / Sources over the host's [PluginManagerPort];
 * every install goes through [PluginConsentDialog]. One banner says why when a change needs a restart.
 */
@Composable
fun PluginsScreen(port: PluginManagerPort) {
    var section by remember { mutableStateOf(SECTIONS[0]) }
    var banner by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var consent by remember { mutableStateOf<Consent?>(null) }
    var consentError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var version by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    /** Runs [call] off the UI thread; a restart answer sets the one banner. */
    val act: Act = { call, then ->
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.Default) { runCatching(call).getOrElse { ActionResult(false, "failed: ${it.message}") } }
            busy = false
            note = r.message
            if (r.restartNeeded) banner = r.message
            version++
            then(r)
        }
    }

    /** Checks [ref], then opens the consent dialog whose Install applies [apply] with the ticked grants. */
    fun askConsent(ref: String, title: String, apply: (Set<String>, String) -> ActionResult) {
        busy = true; note = null
        scope.launch {
            val o = withContext(Dispatchers.Default) { runCatching { port.inspect(ref) }.getOrElse { InspectOutcome(error = "could not check: ${it.message}") } }
            busy = false
            val v = o.view
            if (v == null) note = o.error else { consentError = null; consent = Consent(title, v) { g -> apply(g, v.token) } }
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp).testTag(PLUGINS_SCREEN_TAG), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        banner?.let { Text("Restart needed: $it", color = Palette.Danger, fontSize = 13.sp, modifier = Modifier.testTag(PLUGINS_BANNER_TAG)) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (s in SECTIONS) TextButton(onClick = { section = s }, modifier = Modifier.testTag(PLUGINS_SECTION_PREFIX + s)) {
                Text(s, fontWeight = if (s == section) FontWeight.Bold else FontWeight.Normal)
            }
        }
        note?.let { Text(it, color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.testTag(PLUGINS_NOTE_TAG)) }
        when (section) {
            "Installed" -> Installed(port, version, busy, act)
            "Browse" -> Browse(port, version, busy) { hit ->
                val ref = "${hit.source}:${hit.id}"
                askConsent(ref, "Install ${hit.id}?") { g, t -> port.install(ref, g, t) }
            }
            else -> Sources(port, version, busy, act)
        }
        if (section != "Sources") FileInstall(busy) { path -> askConsent(path, "Install this plugin?") { g, t -> port.install(path, g, t) } }
    }
    consent?.let { c ->
        PluginConsentDialog(c.title, c.view, consentError, busy,
            onConfirm = { granted ->
                consentError = null
                act({ c.run(granted) }) { r -> if (r.ok) consent = null else consentError = r.message }
            },
            onCancel = { consent = null })
    }
}

/** Loads [load] off the UI thread again whenever [key] changes; null while loading or when it throws. */
@Composable
private fun <T> loaded(key: Any, load: () -> T): T? {
    var value by remember { mutableStateOf<T?>(null) }
    LaunchedEffect(key) { value = withContext(Dispatchers.Default) { runCatching(load).getOrNull() } }
    return value
}

@Composable
private fun Installed(port: PluginManagerPort, version: Int, busy: Boolean, act: Act) {
    val rows = loaded(version) { port.list() }
    val offers = loaded(version) { port.updates() }.orEmpty().associateBy { it.id }
    when {
        rows == null -> Text("Loading plugins ...", color = Palette.Muted)
        rows.isEmpty() -> Text("No plugin is installed. Use Browse, or install a .ihq file.", color = Palette.Muted)
        else -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in rows) InstalledRow(port, p, offers[p.id], busy, act)
        }
    }
}

@Composable
private fun InstalledRow(port: PluginManagerPort, p: PluginView, offer: UpdateView?, busy: Boolean, act: Act) {
    Column(Modifier.fillMaxWidth().testTag(PLUGIN_ROW_PREFIX + p.id), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${p.id} ${p.version}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text("[${p.cls}] [${if (p.bundled) "bundled" else "installed"}]", color = Palette.Muted, fontSize = 12.sp)
            Checkbox(checked = p.enabled, onCheckedChange = { on -> act({ port.setEnabled(p.id, on) }, {}) }, enabled = !busy, modifier = Modifier.testTag(PLUGIN_ENABLE_PREFIX + p.id))
            Text(if (p.enabled) "enabled" else "disabled", fontSize = 12.sp)
        }
        Text("Licence: ${p.license ?: "licence not stated"} · Signer: ${p.signer ?: "unsigned"}", color = Palette.Muted, fontSize = 12.sp)
        Text("Grants: ${if (p.granted.isEmpty()) "none" else p.granted.joinToString()}", color = Palette.Muted, fontSize = 12.sp)
        p.yanked?.let { Text("Withdrawn: $it", color = Palette.Danger, fontSize = 12.sp) }
        if (offer != null) {
            Text(
                "Update ${offer.to} available" + if (offer.newGrants.isNotEmpty()) " - asks for new permissions: ${offer.newGrants.joinToString()}" else "",
                fontSize = 12.sp, color = if (offer.newGrants.isNotEmpty()) Palette.Danger else Palette.Text,
            )
        }
        if (!p.bundled) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { act({ port.update(p.id, null) }, {}) }, enabled = !busy && offer != null, modifier = Modifier.testTag(PLUGIN_UPDATE_PREFIX + p.id)) { Text("Update") }
            TextButton(onClick = { act({ port.rollback(p.id) }, {}) }, enabled = !busy && p.canRollback, modifier = Modifier.testTag(PLUGIN_ROLLBACK_PREFIX + p.id)) { Text("Rollback") }
            TextButton(onClick = { act({ port.remove(p.id) }, {}) }, enabled = !busy, modifier = Modifier.testTag(PLUGIN_REMOVE_PREFIX + p.id)) { Text("Remove") }
        }
    }
}

@Composable
private fun Browse(port: PluginManagerPort, version: Int, busy: Boolean, install: (HitView) -> Unit) {
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    val hits = loaded(version to submitted) { port.search(submitted) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(query, { query = it }, label = { Text("Search plugins") }, singleLine = true, modifier = Modifier.weight(1f).testTag(BROWSE_QUERY_TAG))
        Button(onClick = { submitted = query }, modifier = Modifier.testTag(BROWSE_SEARCH_TAG)) { Text("Search") }
    }
    when {
        hits == null -> Text("Searching ...", color = Palette.Muted)
        hits.isEmpty() -> Text("Nothing found. Add a source on the Sources tab, or refresh it.", color = Palette.Muted)
        else -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (h in hits) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("${h.id} ${h.version ?: ""} [${h.cls}] from ${h.source}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(h.description + " · " + (h.license ?: "licence not stated"), color = Palette.Muted, fontSize = 12.sp)
                }
                Button(onClick = { install(h) }, enabled = !busy && !h.installed, modifier = Modifier.testTag(BROWSE_INSTALL_PREFIX + h.id)) { Text(if (h.installed) "Installed" else "Install") }
            }
        }
    }
}

@Composable
private fun FileInstall(busy: Boolean, install: (String) -> Unit) {
    var path by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(path, { path = it }, label = { Text("Install from a .ihq file or folder") }, singleLine = true, modifier = Modifier.weight(1f).testTag(FILE_PATH_TAG))
        Button(onClick = { install(path.trim()) }, enabled = !busy && path.isNotBlank(), modifier = Modifier.testTag(FILE_INSTALL_TAG)) { Text("Install file") }
    }
}

@Composable
private fun Sources(port: PluginManagerPort, version: Int, busy: Boolean, act: Act) {
    var id by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var refreshed by remember { mutableStateOf<List<RefreshView>>(emptyList()) }
    val sources = loaded(version) { port.sources() }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(id, { id = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1f).testTag(SOURCE_ID_TAG))
        OutlinedTextField(location, { location = it }, label = { Text("URL or folder") }, singleLine = true, modifier = Modifier.weight(2f).testTag(SOURCE_LOCATION_TAG))
        Button(onClick = { act({ port.addSource(id.trim(), "custom", location.trim()) }) { if (it.ok) { id = ""; location = "" } } }, enabled = !busy && id.isNotBlank() && location.isNotBlank(), modifier = Modifier.testTag(SOURCE_ADD_TAG)) { Text("Add") }
        Button(onClick = { act({ refreshed = port.refresh(); ActionResult(true, "Sources refreshed.") }, {}) }, enabled = !busy, modifier = Modifier.testTag(SOURCE_REFRESH_TAG)) { Text("Refresh") }
    }
    when {
        sources == null -> Text("Loading sources ...", color = Palette.Muted)
        sources.isEmpty() -> Text("No source is configured. Add a catalog URL or a folder.", color = Palette.Muted)
        else -> for (s in sources) SourceRow(s, refreshed.firstOrNull { it.source == s.id }) { act({ port.removeSource(s.id) }, {}) }
    }
}

@Composable
private fun SourceRow(s: SourceView, state: RefreshView?, remove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${s.id} [${s.cls}]", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(s.location, color = Palette.Muted, fontSize = 12.sp)
            state?.let {
                val text = if (!it.ok) "offline" + (if (it.stale) ", showing the last copy (stale)" else "") + ": ${it.message}" else "${it.plugins} plugin(s)"
                Text(text, color = if (it.ok) Palette.Muted else Palette.Danger, fontSize = 12.sp)
            }
        }
        TextButton(onClick = remove, modifier = Modifier.testTag(SOURCE_REMOVE_PREFIX + s.id)) { Text("Remove") }
    }
}
