// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official

import com.superiorsoftllc.infantry.ui.AhqAlertDialog
import kotlin.time.TimeSource
import androidx.compose.material3.LocalTextStyle
import com.superiorsoftllc.infantry.ui.PathField
import com.superiorsoftllc.infantry.ui.PathKind
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import com.superiorsoftllc.infantry.plugin.api.PluginProgress
import com.superiorsoftllc.infantry.plugin.api.Notification
import com.superiorsoftllc.infantry.plugin.api.NotificationAction
import com.superiorsoftllc.infantry.plugin.api.NotificationLevel
import com.superiorsoftllc.infantry.ui.shell.LocalShellContext
import kotlinx.coroutines.delay
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.plugin.api.ActionResult
import com.superiorsoftllc.infantry.plugin.api.HitView
import com.superiorsoftllc.infantry.plugin.api.InspectOutcome
import com.superiorsoftllc.infantry.plugin.api.InspectView
import com.superiorsoftllc.infantry.plugin.api.PluginManagerPort
import com.superiorsoftllc.infantry.ui.shell.LocalSoftRestart
import com.superiorsoftllc.infantry.ui.shell.LocalPluginLifecycle
import com.superiorsoftllc.infantry.plugin.api.PluginView
import com.superiorsoftllc.infantry.plugin.api.RefreshView
import com.superiorsoftllc.infantry.plugin.api.SourceView
import com.superiorsoftllc.infantry.plugin.api.UpdateView
import com.superiorsoftllc.infantry.ui.Button
import com.superiorsoftllc.infantry.ui.Chip
import com.superiorsoftllc.infantry.ui.FilterChip
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.Switch
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
/** PLUGIN-PROGRESS-1: the live progress strip, the result notice and the Retry button after a failure. */
const val PLUGINS_PROGRESS_TAG = "plugins-progress"
const val PLUGINS_RESULT_TAG = "plugins-result"
const val PLUGINS_RETRY_TAG = "plugins-retry"
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
const val LIST_IMPORT_PATH_TAG = "plugins-list-import-path"
const val LIST_IMPORT_TAG = "plugins-list-import"
const val BROWSE_REPO_PREFIX = "plugins-browse-repo-"
const val PLUGINS_QUERY_TAG = "plugins-installed-query"
const val PLUGINS_FILTER_PREFIX = "plugins-filter-"
const val PLUGINS_EMPTY_TAG = "plugins-installed-empty"
const val PLUGIN_EXPAND_PREFIX = "plugin-expand-"
const val PLUGIN_DETAILS_PREFIX = "plugin-details-"
const val PLUGIN_PENDING_PREFIX = "plugin-pending-"
/** MASCOT-LICENSE-1: the "Refused: <reason>" line of an installed plugin the loader refuses (shown in place of its toggle). */
const val PLUGIN_REFUSED_PREFIX = "plugin-refused-"
const val PLUGIN_DESCRIPTION_PREFIX = "plugin-description-"
const val REMOVE_CONFIRM_TAG = "plugin-remove-confirm"
const val REMOVE_CONFIRM_OK_TAG = "plugin-remove-confirm-ok"
const val DISABLE_CONFIRM_TAG = "plugin-disable-confirm"
const val DISABLE_CONFIRM_OK_TAG = "plugin-disable-confirm-ok"
const val DISABLE_CONFIRM_CANCEL_TAG = "plugin-disable-confirm-cancel"

/** PLUGINS-UI-1: the Installed list's filter chips, in order. */
val PLUGIN_FILTERS = listOf("All", "Official", "Bundled", "User", "Disabled")

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
    // PLUGIN-PROGRESS-1: the running step (from the port), when it started, the last result and how to run it again.
    var progress by remember { mutableStateOf<PluginProgress?>(null) }
    var startedAt by remember { mutableStateOf(0L) }
    var failed by remember { mutableStateOf<ActionResult?>(null) }
    var retry by remember { mutableStateOf<(() -> Unit)?>(null) }
    var version by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    // NOTIFY-CORE-1: a failed install/update/remove is also an error notification on the core bus (it reaches the user when they
    // left this tab); its Retry action runs the change again. The inline notice below stays, next to the plugin it is about.
    val bus = LocalShellContext.current?.toast?.notifications
    var lastFailure by remember { mutableStateOf<String?>(null) }
    DisposableEffect(bus) {
        val stop = bus?.onAction { e -> if (e.notificationId == lastFailure && e.actionId == PLUGIN_RETRY_ACTION) retry?.let { r -> failed = null; retry = null; r() } }
        onDispose { stop?.close() }
    }
    // PLUGIN-SOURCES-1: opening the tab refreshes stale catalogs in the background, then reloads so update offers show.
    LaunchedEffect(Unit) {
        val r = withContext(Dispatchers.Default) { runCatching { port.refreshIfStale() }.getOrNull() }
        if (!r.isNullOrEmpty()) version++
    }
    /** PLUGINS-UI-1: plugin id -> the state its switch was flipped to, while the daemon applies it. */
    val pending = remember { mutableStateMapOf<String, Boolean>() }
    DisposableEffect(port) {
        port.setProgressListener { p -> progress = p }
        onDispose { port.setProgressListener(null) }
    }

    /** Runs [call] off the UI thread; a restart answer sets the one banner. */
    fun runAct(call: () -> ActionResult, then: (ActionResult) -> Unit) {
        busy = true; failed = null; retry = null; note = null
        progress = PluginProgress("", "Starting"); startedAt = pluginClockMs()
        scope.launch {
            val r = withContext(Dispatchers.Default) { runCatching(call).getOrElse { ActionResult(false, "failed: ${it.message}") } }
            busy = false
            progress = null
            note = r.message
            if (!r.ok) {
                failed = r; retry = { runAct(call, then) }
                lastFailure = bus?.notify(pluginFailure(r))
            }
            if (r.restartNeeded) banner = r.message
            version++
            then(r)
        }
    }
    val act: Act = { call, then -> runAct(call, then) }

    /**
     * PLUGIN-CONFIG-1: a change to an installed plugin reaches the daemon half live, but the UI half (tabs, panels, config
     * cards) is only read when the module set is built. A successful change therefore soft-restarts the shell
     * ([LocalSoftRestart], as a drop-in install does), so the new or updated plugin's config appears at once.
     */
    val softRestart = LocalSoftRestart.current
    /**
     * PLUGIN-HOTSWAP-1: where the shell can, the change is swapped in live ([LocalPluginLifecycle]): only the changed plugin
     * reloads and every other tab keeps its state. The soft restart stays the fallback (no live swap here, or a leak).
     */
    val lifecycle = LocalPluginLifecycle.current
    val applyUi: (String?) -> Unit = { said ->
        val line = lifecycle?.apply?.invoke() ?: run { softRestart?.invoke(); null }
        if (line != null) note = listOfNotNull(said, line).joinToString(" - ")
    }
    val actUi: Act = { call, then -> act(call) { r -> then(r); if (r.ok) applyUi(r.message) } }

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

    CompositionLocalProvider(LocalContentColor provides Palette.Text) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp).testTag(PLUGINS_SCREEN_TAG), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        banner?.let { Text("Restart needed: $it", color = Palette.Warn, fontSize = 13.sp, modifier = Modifier.testTag(PLUGINS_BANNER_TAG)) }
        SectionTabs(section) { section = it }
        progress?.let { ProgressStrip(it, startedAt) }
        failed?.let { f -> FailedNotice(f, retry) { failed = null; retry = null } }
        note?.takeIf { failed == null }?.let { Text(if (progress == null) "Done: $it" else it, color = if (progress == null) Palette.Ok else Palette.Muted, fontSize = 12.sp, modifier = Modifier.testTag(PLUGINS_NOTE_TAG)) }
        // PLUGIN-HOTSWAP-1: a plugin whose old version stayed in memory after a live swap (the shell was reloaded instead).
        lifecycle?.warnings?.values?.forEach { Text(it, color = Palette.Warn, fontSize = 12.sp) }
        val body = Modifier.weight(1f).fillMaxWidth()
        when (section) {
            "Installed" -> Installed(port, version, busy, pending, actUi, body) { id, on ->
                pending[id] = on; note = null
                scope.launch {
                    val r = withContext(Dispatchers.Default) { runCatching { port.setEnabled(id, on) }.getOrElse { ActionResult(false, "failed: ${it.message}") } }
                    note = r.message
                    if (r.restartNeeded) banner = r.message
                    // On success the switch stays pending until the reloaded list shows the new state (no flicker back).
                    if (!r.ok) pending.remove(id)
                    version++
                    if (r.ok) applyUi(r.message)
                }
            }
            "Browse" -> Browse(port, version, busy, body) { hit ->
                val ref = "${hit.source}:${hit.id}"
                askConsent(ref, "Install ${hit.id}?") { g, t -> port.install(ref, g, t) }
            }
            else -> Sources(port, version, busy, act, body)
        }
        if (section != "Sources") FileInstall(busy) { path -> askConsent(path, "Install this plugin?") { g, t -> port.install(path, g, t) } }
    }
    }
    consent?.let { c ->
        PluginConsentDialog(c.title, c.view, consentError, busy,
            onConfirm = { granted ->
                consentError = null
                actUi({ c.run(granted) }) { r -> if (r.ok) consent = null else consentError = r.message }
            },
            onCancel = { consent = null })
    }
}

/** PLUGIN-PROGRESS-1: "Downloading 40% - 12s": determinate while the size is known, else indeterminate; the seconds tick so a long step never looks stuck. */
@Composable
private fun ProgressStrip(p: PluginProgress, startedAt: Long) {
    var now by remember { mutableStateOf(pluginClockMs()) }
    LaunchedEffect(startedAt) { while (true) { delay(1000); now = pluginClockMs() } }
    val secs = ((now - startedAt) / 1000).coerceAtLeast(0)
    val frac = if (p.total > 0 && p.done >= 0) (p.done.toFloat() / p.total).coerceIn(0f, 1f) else null
    val who = if (p.id.isEmpty()) "" else " ${displayName(p.id)}"
    Column(Modifier.fillMaxWidth().testTag(PLUGINS_PROGRESS_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${p.step}$who" + (frac?.let { " ${(it * 100).toInt()}%" } ?: " ...") + " (${secs}s)", color = Palette.Text, fontSize = 13.sp,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        // CORE-PROGRESS-DOWNLOAD-1: the one core bar; the change is also a task in the core running-tasks list.
        com.superiorsoftllc.infantry.ui.progress.ProgressBar(frac, Modifier.fillMaxWidth())
    }
}

/** NOTIFY-CORE-1: the retry action of a failed plugin change's notification. */
const val PLUGIN_RETRY_ACTION = "retry"

/** NOTIFY-CORE-1: a failed plugin change as a notification on the core bus. */
fun pluginFailure(r: ActionResult): Notification = Notification(
    title = "Plugin change failed",
    body = r.message,
    level = NotificationLevel.ERROR,
    dedupeKey = "plugins.failed:" + r.message,
    actions = listOf(NotificationAction(PLUGIN_RETRY_ACTION, "Retry")),
)

/** PLUGIN-PROGRESS-1: the exact reason a change failed, with Retry (and dismiss). */
@Composable
private fun FailedNotice(r: ActionResult, retry: (() -> Unit)?, dismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag(PLUGINS_RESULT_TAG), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Failed: ${r.message}", color = Palette.Danger, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (retry != null) TextButton(onClick = { dismiss(); retry() }, modifier = Modifier.testTag(PLUGINS_RETRY_TAG)) { Text("Retry") }
        TextButton(onClick = dismiss) { Text("Dismiss") }
    }
}

/** Loads [load] off the UI thread again whenever [key] changes; null while loading or when it throws. */
@Composable
private fun <T> loaded(key: Any, load: () -> T): T? {
    var value by remember { mutableStateOf<T?>(null) }
    LaunchedEffect(key) { value = withContext(Dispatchers.Default) { runCatching(load).getOrNull() } }
    return value
}


/** PLUGINS-UI-1: Installed / Browse / Sources as tabs: the selected one in primary text over an accent bar, each 48dp high. */
@Composable
private fun SectionTabs(selected: String, pick: (String) -> Unit) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (s in SECTIONS) {
                val on = s == selected
                Column(
                    Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(6.dp)).clickable(role = Role.Tab) { pick(s) }
                        .padding(horizontal = 12.dp).testTag(PLUGINS_SECTION_PREFIX + s),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom,
                ) {
                    Text(s, color = if (on) Palette.Text else Palette.Muted, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(top = 14.dp, bottom = 10.dp))
                    Box(Modifier.height(2.dp).width(32.dp).background(if (on) Palette.Accent else Color.Transparent))
                }
            }
        }
        HorizontalDivider(color = Palette.Border)
    }
}

/** `Infantry_Agents` reads as "Infantry Agents". */
internal fun displayName(id: String) = id.replace('_', ' ')

/** PLUGINS-UI-1: does [p] pass filter chip [filter] and the search [query] (name, description, what it adds)? */
internal fun matches(p: PluginView, filter: String, query: String): Boolean {
    val inFilter = when (filter) {
        "Official" -> p.cls == "official"
        "Bundled" -> p.bundled
        "User" -> !p.bundled
        "Disabled" -> !p.enabled
        else -> true
    }
    val q = query.trim()
    return inFilter && (q.isEmpty() || (listOf(p.id, displayName(p.id), p.description) + p.contributes).any { it.contains(q, ignoreCase = true) })
}

/** Official first, then by name. */
internal fun pluginOrder(rows: List<PluginView>) = rows.sortedWith(compareBy<PluginView>({ it.cls != "official" }, { it.id.lowercase() }))

/** PLUGINS-UI-1: turning [p] off asks first when the app ships it as an official plugin, or an enabled plugin needs it. */
internal fun needsDisableConfirm(p: PluginView) = p.enabled && ((p.cls == "official" && p.bundled) || p.requiredBy.isNotEmpty() || p.parents.size >= 2)

@Composable
private fun Installed(
    port: PluginManagerPort, version: Int, busy: Boolean, pending: MutableMap<String, Boolean>, act: Act, modifier: Modifier,
    toggle: (String, Boolean) -> Unit,
) {
    val rows = loaded(version) { port.list() }
    val offers = loaded(version) { port.updates() }.orEmpty().associateBy { it.id }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(PLUGIN_FILTERS[0]) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf<PluginView?>(null) }
    LaunchedEffect(rows) { rows?.forEach { r -> if (pending[r.id] == r.enabled) pending.remove(r.id) } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            rows == null -> Text("Loading plugins ...", color = Palette.Muted, fontSize = 13.sp)
            rows.isEmpty() -> Text("No plugin is installed. Use Browse, or install a .ihq file.", color = Palette.Muted, fontSize = 13.sp)
            else -> {
                OutlinedTextField(query, { query = it }, placeholder = { Text("Search installed plugins", color = Palette.Muted) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(PLUGINS_QUERY_TAG))
                FilterRow(filter, rows) { filter = it }
                val shown = pluginOrder(rows.filter { matches(it, filter, query) })
                Text("${shown.size} of ${rows.size} plugins · ${rows.count { it.enabled }} on", color = Palette.Muted, fontSize = 12.sp)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (shown.isEmpty()) Text("No plugin matches.", color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp).testTag(PLUGINS_EMPTY_TAG))
                    val byId = rows.associateBy { it.id }
                    val shownIds = shown.map { it.id }.toSet()
                    // PLUGIN-TREE-2b: children nest under their parent row; a child with 2+ parents appears under each, in the same state.
                    @Composable fun TreeRow(p: PluginView, depth: Int) {
                        Column(Modifier.padding(start = (depth * 20).dp)) {
                            InstalledRow(
                                port, p, offers[p.id], busy, pending[p.id], p.id in expanded, act,
                                onExpand = { expanded = if (p.id in expanded) expanded - p.id else expanded + p.id },
                                onToggle = { on -> if (!on && needsDisableConfirm(p)) confirm = p else toggle(p.id, on) },
                                parentsOn = p.parents.filter { byId[it]?.let { q -> q.enabled && q.heldOffBy.isEmpty() } == true },
                            )
                        }
                        if (depth < 4) for (c in p.children.mapNotNull { byId[it] }.filter { it.id in shownIds }.sortedBy { it.id }) TreeRow(c, depth + 1)
                    }
                    // Top level: plugins with no shown parent (a filter that hides the parents lifts the children up).
                    for (p in shown.filter { q -> q.parents.none { it in shownIds } }) TreeRow(p, 0)
                }
            }
        }
    }
    confirm?.let { p -> DisableConfirm(p, onYes = { confirm = null; toggle(p.id, false) }, onNo = { confirm = null }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterRow(selected: String, rows: List<PluginView>, pick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (f in PLUGIN_FILTERS) {
            val n = rows.count { matches(it, f, "") }
            FilterChip(selected = f == selected, onClick = { pick(f) }, label = { Text("$f  $n", fontSize = 12.sp) },
                modifier = Modifier.heightIn(min = 40.dp).testTag(PLUGINS_FILTER_PREFIX + f))
        }
    }
}

/** A list row: a rounded panel with a thin border. */
@Composable
private fun RowCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(Palette.Panel).border(1.dp, Palette.Border, shape), content = content)
}

/** The plugin's initial (after the vendor prefix) in a round tile. */
@Composable
private fun Initial(id: String) {
    val letter = id.substringAfter('_', id).firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '?'
    Box(Modifier.size(36.dp).clip(CircleShape).background(Palette.Raised).border(1.dp, Palette.Border, CircleShape), contentAlignment = Alignment.Center) {
        Text(letter.toString(), color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The leading part every row shares: initial, name, version, badges, then up to two lines of description. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RowHead(id: String, version: String?, badges: List<Pair<String, Color>>, description: String?, modifier: Modifier, title: String = displayName(id)) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Initial(id)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                version?.takeIf { it.isNotBlank() }?.let { Text(it, color = Palette.Muted, fontSize = 12.sp) }
                for ((b, c) in badges) Chip(b, c)
            }
            if (!description.isNullOrBlank()) Text(description, color = Palette.Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(PLUGIN_DESCRIPTION_PREFIX + id))
        }
    }
}

/** A chevron pointing right (collapsed) or down (open), in a 48dp target. */
@Composable
private fun Chevron(open: Boolean, name: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = (if (open) "Hide details of " else "Show details of ") + name },
        contentAlignment = Alignment.Center,
    ) {
        val c = Palette.Muted
        Canvas(Modifier.size(12.dp).rotate(if (open) 90f else 0f)) {
            val w = size.width; val h = size.height
            drawLine(c, Offset(w * 0.3f, h * 0.1f), Offset(w * 0.75f, h * 0.5f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawLine(c, Offset(w * 0.75f, h * 0.5f), Offset(w * 0.3f, h * 0.9f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

/** One label / value line of a row's details; the label column is fixed so the values align. */
@Composable
private fun Detail(label: String, value: String, color: Color = Palette.Text) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.width(96.dp))
        Text(value, color = color, fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun InstalledRow(
    port: PluginManagerPort, p: PluginView, offer: UpdateView?, busy: Boolean, pendingTo: Boolean?, open: Boolean, act: Act,
    onExpand: () -> Unit, onToggle: (Boolean) -> Unit, parentsOn: List<String> = emptyList(),
) {
    val badges = buildList {
        if (p.cls == "official") add("Official" to Palette.Info) else add(p.cls.replaceFirstChar { it.uppercase() } to Palette.Muted)
        if (p.bundled) add("Bundled" to Palette.Muted)
        if (!p.bundled && p.signer == null) add("Unsigned" to Palette.Warn)
        if (offer != null) add("Update ${offer.to}" to Palette.Ok)
        if (p.yanked != null) add("Withdrawn" to Palette.Danger)
        if (p.refused != null) add("Refused" to Palette.Danger)
        if (p.heldOffBy.isNotEmpty()) add(("Off: its parents are off (" + p.heldOffBy.joinToString(", ") { displayName(it) } + ")") to Palette.Warn)
        else if (parentsOn.size >= 2) add(("In use by " + parentsOn.map { displayName(it) }.let { n -> n.dropLast(1).joinToString(", ") + " and " + n.last() }) to Palette.Muted)
    }
    val on = pendingTo ?: p.enabled
    var confirmRemove by remember { mutableStateOf(false) }
    if (confirmRemove) AhqAlertDialog(
        onDismissRequest = { confirmRemove = false },
        modifier = Modifier.testTag(REMOVE_CONFIRM_TAG),
        title = { Text("Remove ${displayName(p.id)}?") },
        text = { Text("It is turned off and its files are deleted. Install it again from Browse to get it back.", fontSize = 13.sp) },
        confirmButton = { TextButton(onClick = { confirmRemove = false; act({ port.remove(p.id) }, {}) }, modifier = Modifier.testTag(REMOVE_CONFIRM_OK_TAG)) { Text("Remove", color = Palette.Danger) } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Keep") } },
    )
    RowCard(Modifier.testTag(PLUGIN_ROW_PREFIX + p.id)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onExpand).heightIn(min = 64.dp).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowHead(p.id, p.version, badges, p.description, Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(start = 8.dp)) {
                if (pendingTo != null) CircularProgressIndicator(Modifier.size(16.dp).testTag(PLUGIN_PENDING_PREFIX + p.id), color = Palette.Muted, strokeWidth = 2.dp)
                // MASCOT-LICENSE-1: a refused plugin does not load whatever its flag says, so it gets no toggle; the reason shows below.
                if (p.refused == null) Switch(
                    checked = on, onCheckedChange = onToggle, enabled = !busy && pendingTo == null,
                    modifier = Modifier.testTag(PLUGIN_ENABLE_PREFIX + p.id).semantics {
                        contentDescription = displayName(p.id)
                        stateDescription = when (pendingTo) { true -> "turning on"; false -> "turning off"; null -> if (on) "on" else "off" }
                    },
                )
                Chevron(open, displayName(p.id), Modifier.testTag(PLUGIN_EXPAND_PREFIX + p.id), onExpand)
            }
        }
        p.refused?.let {
            Text("Refused: $it", color = Palette.Danger, fontSize = 12.sp,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp).testTag(PLUGIN_REFUSED_PREFIX + p.id))
        }
        // PLUGIN-LIFECYCLE-1: Update and Remove sit on the row itself (not behind the expand arrow) for every plugin the manager installed.
            if (p.bundled) Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) { Detail("Remove", "Not available: it ships with the app. Turn it off instead.", Palette.Muted) }
            if (!p.bundled) Row(Modifier.padding(start = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { act({ port.update(p.id, null) }, {}) }, enabled = !busy && offer != null, modifier = Modifier.testTag(PLUGIN_UPDATE_PREFIX + p.id)) { Text("Update") }
                TextButton(onClick = { act({ port.rollback(p.id) }, {}) }, enabled = !busy && p.canRollback, modifier = Modifier.testTag(PLUGIN_ROLLBACK_PREFIX + p.id)) { Text("Roll back") }
                TextButton(onClick = { confirmRemove = true }, enabled = !busy, modifier = Modifier.testTag(PLUGIN_REMOVE_PREFIX + p.id)) { Text("Remove", color = Palette.Danger) }
            }
        if (open) {
            HorizontalDivider(color = Palette.Border)
            Column(
                Modifier.fillMaxWidth().padding(start = 60.dp, end = 16.dp, top = 10.dp, bottom = 12.dp).testTag(PLUGIN_DETAILS_PREFIX + p.id),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Detail("License", p.license ?: "Not stated")
                Detail("Signed by", p.signer ?: if (p.bundled) "Ships with the app" else "Not signed")
                Detail("Permissions", if (p.granted.isEmpty()) "None granted" else p.granted.joinToString(", "))
                val refused = p.requested.filter { it !in p.granted }
                if (refused.isNotEmpty()) Detail("Not granted", refused.joinToString(", "))
                Detail("Adds", if (p.contributes.isEmpty()) "Nothing in the window (works in the background)" else p.contributes.joinToString("\n"))
                if (p.requiredBy.isNotEmpty()) Detail("Needed by", p.requiredBy.joinToString(", ") { displayName(it) })
                p.yanked?.let { Detail("Withdrawn", it, Palette.Danger) }
                offer?.let {
                    Detail("Update", "${it.to} is available" + if (it.newGrants.isNotEmpty()) "; asks for new permissions: ${it.newGrants.joinToString()}" else "",
                        if (it.newGrants.isNotEmpty()) Palette.Warn else Palette.Text)
                }
            }
        }
    }
}

/** PLUGINS-UI-1: "turn this off?" for a plugin the app ships or another plugin needs. */
@Composable
private fun DisableConfirm(p: PluginView, onYes: () -> Unit, onNo: () -> Unit) {
    AhqAlertDialog(
        onDismissRequest = onNo,
        modifier = Modifier.testTag(DISABLE_CONFIRM_TAG),
        title = { Text("Turn off ${displayName(p.id)}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (p.cls == "official" && p.bundled) Text("It ships with the app. What it adds disappears until you turn it back on.", fontSize = 13.sp)
                if (p.contributes.isNotEmpty()) Text("It adds: " + p.contributes.joinToString(", "), color = Palette.Muted, fontSize = 12.sp)
                if (p.requiredBy.isNotEmpty()) Text("These plugins need it and will stop working: " + p.requiredBy.joinToString(", ") { displayName(it) },
                    color = Palette.Warn, fontSize = 13.sp)
            }
        },
        confirmButton = { TextButton(onClick = onYes, modifier = Modifier.testTag(DISABLE_CONFIRM_OK_TAG)) { Text("Turn off", color = Palette.Danger) } },
        dismissButton = { TextButton(onClick = onNo, modifier = Modifier.testTag(DISABLE_CONFIRM_CANCEL_TAG)) { Text("Keep on") } },
    )
}

@Composable
private fun Browse(port: PluginManagerPort, version: Int, busy: Boolean, modifier: Modifier, install: (HitView) -> Unit) {
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    val hits = loaded(version to submitted) { port.search(submitted) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, placeholder = { Text("Search plugin sources", color = Palette.Muted) }, singleLine = true,
                modifier = Modifier.weight(1f).testTag(BROWSE_QUERY_TAG))
            Button(onClick = { submitted = query }, modifier = Modifier.heightIn(min = 48.dp).testTag(BROWSE_SEARCH_TAG)) { Text("Search") }
        }
        when {
            hits == null -> Text("Searching ...", color = Palette.Muted, fontSize = 13.sp)
            hits.isEmpty() -> Text("Nothing found. Import a plugin list or add a source on the Sources tab, or refresh it.", color = Palette.Muted, fontSize = 13.sp)
            else -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (h in hits) RowCard {
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val official = h.cls == "official"
                        val badges = listOf(
                            (if (official) "Official" else h.cls.replaceFirstChar { it.uppercase() }) to (if (official) Palette.Info else Palette.Muted),
                            "from ${h.listName ?: h.source}" to Palette.Muted,
                        ) + h.tags.map { it to Palette.Muted }
                        Column(Modifier.weight(1f)) {
                            RowHead(h.id, h.version ?: if (h.fromRepo) "latest release" else null, badges, h.description, Modifier.fillMaxWidth(), title = h.name ?: displayName(h.id))
                            h.repo?.let { repo -> RepoLink(h.id, repo) }
                        }
                        Button(onClick = { install(h) }, enabled = !busy && !h.installed, modifier = Modifier.padding(start = 8.dp).heightIn(min = 48.dp).testTag(BROWSE_INSTALL_PREFIX + h.id)) {
                            Text(if (h.installed) "Installed" else "Install")
                        }
                    }
                }
            }
        }
    }
}

/** PLUGIN-REPO-1: an entry's repository as a link (owner/name opens its GitHub page; a URL opens as given). */
@Composable
private fun RepoLink(id: String, repo: String) {
    val uri = LocalUriHandler.current
    val target = repoPage(repo)
    TextButton(onClick = { target?.let { runCatching { uri.openUri(it) } } }, enabled = target != null,
        modifier = Modifier.padding(start = 44.dp).heightIn(min = 32.dp).testTag(BROWSE_REPO_PREFIX + id)) {
        Text(repo, color = Palette.Info, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The web page of a list entry's `repo`: `owner/name` = its GitHub page, an http(s) URL as given; null = no page (a file URL). */
internal fun repoPage(repo: String): String? {
    val r = repo.trim()
    return when {
        r.startsWith("https://", true) || r.startsWith("http://", true) -> r
        Regex("[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9_.-]+").matches(r) -> "https://github.com/$r"
        else -> null
    }
}

/** PLUGIN-REPO-1: "Import plugin list": a JSON file (picker) or a URL, saved as a community source. */
@Composable
private fun ListImport(port: PluginManagerPort, busy: Boolean, act: Act) {
    var location by remember { mutableStateOf("") }
    FormRow(
        fields = { PathField(location, { location = it }, PathKind.FILE, "Import plugin list (JSON file or URL)", Modifier.fillMaxWidth(), tag = LIST_IMPORT_PATH_TAG, title = "Choose a plugin list (.json)", extensions = listOf("json"), textStyle = LocalTextStyle.current) },
        buttons = {
            Button(onClick = { act({ port.importList(location.trim()) }) { if (it.ok) location = "" } }, enabled = !busy && location.isNotBlank(),
                modifier = Modifier.heightIn(min = 48.dp).testTag(LIST_IMPORT_TAG)) { Text("Import list") }
        },
    )
}

/** The fields side by side when wide, stacked under 560dp; the buttons follow. */
@Composable
private fun FormRow(fields: @Composable (Boolean) -> Unit, buttons: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 560.dp) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            fields(true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
        } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) { fields(false) }
            buttons()
        }
    }
}

@Composable
private fun FileInstall(busy: Boolean, install: (String) -> Unit) {
    var path by remember { mutableStateOf("") }
    FormRow(
        // PATH-PICKER-1: Browse opens a file chooser narrowed to .ihq; a plugin folder can still be typed.
        fields = { PathField(path, { path = it }, PathKind.FILE, "Install from a .ihq file or folder", Modifier.fillMaxWidth(), tag = FILE_PATH_TAG, title = "Choose a plugin (.ihq)", extensions = listOf("ihq"), textStyle = LocalTextStyle.current) },
        buttons = { Button(onClick = { install(path.trim()) }, enabled = !busy && path.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp).testTag(FILE_INSTALL_TAG)) { Text("Install file") } },
    )
}

@Composable
private fun Sources(port: PluginManagerPort, version: Int, busy: Boolean, act: Act, modifier: Modifier) {
    var id by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var refreshed by remember { mutableStateOf<List<RefreshView>>(emptyList()) }
    val sources = loaded(version) { port.sources() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ListImport(port, busy, act)
        Text("A list is shown as community: it never raises what a plugin may do. Every install still shows its grants and licence first.",
            color = Palette.Muted, fontSize = 12.sp)
        FormRow(
            fields = { stacked ->
                OutlinedTextField(id, { id = it }, label = { Text("Name") }, singleLine = true,
                    modifier = (if (stacked) Modifier.fillMaxWidth() else Modifier.width(180.dp)).testTag(SOURCE_ID_TAG))
                PathField(location, { location = it }, PathKind.FOLDER, "URL or folder", Modifier.fillMaxWidth(), tag = SOURCE_LOCATION_TAG, title = "Choose a plugin source folder", textStyle = LocalTextStyle.current)
            },
            buttons = {
                Button(onClick = { act({ port.addSource(id.trim(), "custom", location.trim()) }) { if (it.ok) { id = ""; location = "" } } },
                    enabled = !busy && id.isNotBlank() && location.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp).testTag(SOURCE_ADD_TAG)) { Text("Add") }
                Button(onClick = { act({ refreshed = port.refresh(); ActionResult(true, "Sources refreshed.") }, {}) }, enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag(SOURCE_REFRESH_TAG)) { Text("Refresh") }
            },
        )
        when {
            sources == null -> Text("Loading sources ...", color = Palette.Muted, fontSize = 13.sp)
            sources.isEmpty() -> Text("No source is configured. Add a catalog URL or a folder.", color = Palette.Muted, fontSize = 13.sp)
            else -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in sources) SourceRow(s, refreshed.firstOrNull { it.source == s.id }) { act({ port.removeSource(s.id) }, {}) }
            }
        }
    }
}

@Composable
private fun SourceRow(s: SourceView, state: RefreshView?, remove: () -> Unit) {
    val badges = buildList {
        add(s.cls.replaceFirstChar { it.uppercase() } to (if (s.cls == "official") Palette.Info else Palette.Muted))
        if (!s.enabled) add("Off" to Palette.Muted)
        state?.let { add((if (it.ok) "${it.plugins} plugin(s)" else if (it.stale) "Offline, last copy" else "Offline") to (if (it.ok) Palette.Ok else Palette.Danger)) }
    }
    val detail = s.location + (state?.takeIf { !it.ok }?.let { "\n" + it.message } ?: "")
    RowCard {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RowHead(s.id, null, badges, detail, Modifier.weight(1f))
            TextButton(onClick = remove, modifier = Modifier.padding(start = 8.dp).heightIn(min = 48.dp).testTag(SOURCE_REMOVE_PREFIX + s.id)) { Text("Remove", color = Palette.Danger) }
        }
    }
}

/** PLUGIN-PROGRESS-1 fix: a multiplatform elapsed clock (System.currentTimeMillis does not exist on wasm, which broke every nightly build). */
private val pluginClockOrigin = TimeSource.Monotonic.markNow()
private fun pluginClockMs(): Long = pluginClockOrigin.elapsedNow().inWholeMilliseconds
