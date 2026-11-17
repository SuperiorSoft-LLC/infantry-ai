// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kizitonwose.calendar.compose.HorizontalCalendar
import com.kizitonwose.calendar.compose.rememberCalendarState
import com.kizitonwose.calendar.core.CalendarDay
import com.kizitonwose.calendar.core.DayPosition
import com.kizitonwose.calendar.core.daysOfWeek
import com.superiorsoftllc.infantry.plugin.api.Services
import com.superiorsoftllc.infantry.ui.*
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.plugin.TabContext
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/** The weekday the month grid starts on. */
private val FIRST_DAY_OF_WEEK = DayOfWeek.SUNDAY

/** Months the grid can scroll either side of this month. */
private const val CALENDAR_MONTHS_SPAN = 24

private fun kindColor(kindKey: String): Color = when (kindKey) {
    "task" -> Palette.Info
    "reminder" -> Palette.Accent
    else -> Palette.Muted // ".closed": done or cancelled
}

private val LIVE_SESSION_STATES = setOf(SessionState.STARTING, SessionState.BUSY, SessionState.WAITING_INPUT, SessionState.IDLE)

/** The `role:<role>` targets a reminder can aim at (the built-in roles, the catalog, visible profiles) and the live `session:<id>` ones, as (target, chip label). */
internal fun calendarTargets(state: AppState): List<Pair<String, String>> {
    val roles = (listOf("pm", "qa", "version-control", "assistant") + state.roles.map { it.id } + state.visibleProfiles.map { it.role }).distinct()
    val sessions = state.agents.values.filter { it.session.state in LIVE_SESSION_STATES }.sortedByDescending { it.session.spawnedAt }
    return roles.map { "role:$it" to it } + sessions.map { "session:${it.id}" to "${it.role ?: "agent"} ${it.shortId}" }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CalendarTab(ctx: TabContext) {
    val scope = rememberCoroutineScope()
    val api = remember(ctx.services) { CalendarApi(ctx.services.get(Services.DAEMON)) }
    val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
    val thisMonth = YearMonth(today.year, today.month)
    val calendarState = rememberCalendarState(
        startMonth = thisMonth.minus(CALENDAR_MONTHS_SPAN, DateTimeUnit.MONTH), endMonth = thisMonth.plus(CALENDAR_MONTHS_SPAN, DateTimeUnit.MONTH),
        firstVisibleMonth = thisMonth, firstDayOfWeek = FIRST_DAY_OF_WEEK,
    )
    val month = calendarState.firstVisibleMonth.yearMonth
    var version by remember { mutableStateOf(0) }
    var items by remember { mutableStateOf<List<CalItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<LocalDate?>(null) }
    var open by remember { mutableStateOf<CalItem?>(null) }
    var draft by remember { mutableStateOf<CalendarDraft?>(null) }
    var confirmWipe by remember { mutableStateOf(false) }

    // Live updates: the daemon's calendar_changed frame (module.json daemonFrames), republished by the host.
    DisposableEffect(ctx.services) {
        val off = ctx.services.get(Services.EVENTS)?.subscribe(CALENDAR_CHANGED_EVENT) { version++ }
        onDispose { off?.invoke() }
    }
    // CAL-3: a reminder notification's Open button asks the tab to open that item (the daemon's calendar_open frame).
    DisposableEffect(ctx.services) {
        val off = ctx.services.get(Services.EVENTS)?.subscribe(CALENDAR_OPEN_EVENT) { e ->
            val id = CalendarModel.openId(e.data["data"]) ?: return@subscribe
            scope.launch { api.get(id).onSuccess { n -> if (n != null) { open = n; selected = runCatching { LocalDate.parse(n.day) }.getOrNull() ?: selected } } }
        }
        onDispose { off?.invoke() }
    }
    val live = ctx.state.connection.isLive
    LaunchedEffect(month, version, live) {
        if (!live) return@LaunchedEffect
        val from = month.firstDay.minus(7, DateTimeUnit.DAY).toString()
        val to = month.lastDay.plus(7, DateTimeUnit.DAY).toString()
        api.list(from, to).onSuccess { items = it; error = null }.onFailure { error = it.message }
        open?.let { o -> api.get(o.id).onSuccess { n -> open = n } }
    }
    val byDay = remember(items) { CalendarModel.byDay(items) }

    Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1.6f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { scope.launch { calendarState.animateScrollToMonth(month.minus(1, DateTimeUnit.MONTH)) } }) { Text("<") }
                Text("${month.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${month.year}", color = Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                TextButton(onClick = { scope.launch { calendarState.animateScrollToMonth(month.plus(1, DateTimeUnit.MONTH)) } }) { Text(">") }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { selected = today; scope.launch { calendarState.animateScrollToMonth(thisMonth) } }) { Text("Today", fontSize = 12.sp) }
                TextButton(onClick = { confirmWipe = true }, enabled = live) { Text("Delete all", fontSize = 12.sp, color = Palette.Danger) }
                Button(onClick = { draft = CalendarDraft(date = (selected ?: today).toString()) }, enabled = live) { Text("+ New", fontSize = 12.sp) }
            }
            error?.let { Text(it, color = Palette.Danger, fontSize = 11.sp) }
            Row(Modifier.fillMaxWidth()) {
                daysOfWeek(FIRST_DAY_OF_WEEK).forEach { d ->
                    Text(d.name.take(3).lowercase().replaceFirstChar { it.uppercase() }, color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                }
            }
            HorizontalCalendar(
                state = calendarState,
                dayContent = { day -> DayCell(day, today, selected, byDay[day.date], onClick = { selected = day.date }) },
            )
        }
        DayPanel(selected, selected?.let { byDay[it] }.orEmpty(), Modifier.weight(1f), onOpen = { it0 -> open = it0; scope.launch { api.get(it0.id).onSuccess { n -> if (n != null) open = n } } }, onNew = { d -> draft = CalendarDraft(date = d.toString()) })
    }

    if (confirmWipe) AhqAlertDialog(
        onDismissRequest = { confirmWipe = false },
        title = { Text("Delete all calendar data?") },
        text = { Text("Every item, its history and the reminders' triggers are removed. This cannot be undone.") },
        confirmButton = { Button(onClick = { confirmWipe = false; scope.launch { api.clear().onSuccess { open = null; version++ }.onFailure { error = it.message } } }) { Text("Delete all") } },
        dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } },
    )
    open?.let { item ->
        ItemDialog(item, api, onClose = { open = null }, onEdit = { draft = CalendarDraft.from(it); open = null }, onChanged = { version++ })
    }
    draft?.let { d -> EditDialog(d, ctx.state, api, onClose = { draft = null }, onSaved = { saved -> draft = null; version++; open = saved }) }
}

@Composable
private fun DayCell(day: CalendarDay, today: LocalDate, selected: LocalDate?, items: List<CalItem>?, onClick: () -> Unit) {
    val inMonth = day.position == DayPosition.MonthDate
    val dots = CalendarModel.dots(items)
    val isToday = day.date == today
    Box(
        Modifier.aspectRatio(1f).padding(2.dp).clip(RoundedCornerShape(6.dp))
            .background(if (day.date == selected) Palette.Accent.copy(alpha = 0.18f) else Color.Transparent)
            .border(1.dp, if (isToday) Palette.Accent else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable { onClick() },
    ) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(day.date.day.toString(), color = if (inMonth) Palette.Text else Palette.Muted.copy(alpha = 0.5f), fontSize = 13.sp, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal)
            Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                dots.kinds.forEach { k -> Box(Modifier.size(6.dp).clip(CircleShape).background(kindColor(k))) }
                if (dots.more > 0) Text("+${dots.more}", color = Palette.Muted, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun DayPanel(day: LocalDate?, items: List<CalItem>, modifier: Modifier, onOpen: (CalItem) -> Unit, onNew: (LocalDate) -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (day == null) {
            Text("Pick a day to see what is planned.", color = Palette.Muted, fontSize = 12.sp)
            return@Column
        }
        Text(day.toString(), color = Palette.Text, fontWeight = FontWeight.SemiBold)
        if (items.isEmpty()) {
            Text("Nothing planned", color = Palette.Muted, fontSize = 12.sp)
            OutlinedButton(onClick = { onNew(day) }) { Text("+ New", fontSize = 12.sp) }
        }
        items.forEach { i ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).border(1.dp, Palette.Border, RoundedCornerShape(6.dp)).background(Palette.Raised)
                    .clickable { onOpen(i) }.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(kindColor(i.kind + if (i.open) "" else ".closed")))
                    Text(i.title, color = Palette.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 2)
                    Chip(i.kind, kindColor(i.kind))
                }
                Text(CalendarModel.timeLabel(i) + if (i.open) "" else "  -  ${i.status}", color = Palette.Muted, fontSize = 11.sp)
                i.target?.let { Text("for $it", color = Palette.Muted, fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun ItemDialog(item: CalItem, api: CalendarApi, onClose: () -> Unit, onEdit: (CalItem) -> Unit, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    fun act(block: suspend () -> Result<*>) { scope.launch { block().onSuccess { problem = null; onChanged() }.onFailure { problem = it.message } } }
    AhqAlertDialog(
        onDismissRequest = onClose,
        title = { Text(item.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Chip(item.kind, kindColor(item.kind)); Chip(item.status, if (item.open) Palette.Info else Palette.Ok) }
                Text("${item.day}  ${CalendarModel.timeLabel(item)}  (${item.zone})", color = Palette.Text, fontSize = 12.sp)
                item.target?.let { Text("Reminds $it", color = Palette.Muted, fontSize = 12.sp) }
                Text("Added by ${item.createdBy}", color = Palette.Muted, fontSize = 11.sp)
                item.notes?.let { n ->
                    val md = LocalChatMarkdown.current
                    md.engine.parse(n).forEach { b -> md.renderers.rendererFor(b).Render(b) }
                }
                if (item.history.isNotEmpty()) {
                    Text("HISTORY", color = Palette.Muted, fontSize = 10.sp)
                    item.history.forEach { h -> Text("${h.kind}${h.by?.let { " by $it" }.orEmpty()}${h.detail?.let { ": $it" }.orEmpty()}", color = Palette.Muted, fontSize = 11.sp) }
                }
                problem?.let { Text(it, color = Palette.Danger, fontSize = 11.sp) }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (item.open) Button(onClick = { act { api.complete(item.id, "done") } }) { Text("Done") }
                else OutlinedButton(onClick = { act { api.complete(item.id, "open") } }) { Text("Reopen") }
                if (item.snoozable) CalendarModel.SNOOZE_PRESETS.forEach { (preset, label) ->
                    OutlinedButton(onClick = { act { api.snooze(item.id, preset) } }) { Text(label) }
                }
                OutlinedButton(onClick = { onEdit(item) }) { Text("Edit") }
                TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = Palette.Danger) }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
        wide = true,
    )
    if (confirmDelete) AhqAlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this item?") },
        text = { Text("\"${item.title}\" and its history are removed.") },
        confirmButton = { Button(onClick = { confirmDelete = false; scope.launch { api.delete(item.id).onSuccess { onChanged(); onClose() }.onFailure { problem = it.message } } }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

@Composable
private fun EditDialog(initial: CalendarDraft, state: AppState, api: CalendarApi, onClose: () -> Unit, onSaved: (CalItem?) -> Unit) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(initial) }
    var failure by remember { mutableStateOf<String?>(null) }
    val problem = draft.problem()
    AhqAlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (initial.id == null) "New item" else "Edit item") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Kind", color = Palette.Muted, fontSize = 11.sp)
                    listOf("task", "reminder").forEach { k -> Chip(k, if (draft.kind == k) Palette.Accent else Palette.Muted, Modifier.clickable { draft = draft.copy(kind = k) }) }
                }
                OutlinedTextField(draft.title, { draft = draft.copy(title = it) }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(draft.notes, { draft = draft.copy(notes = it) }, label = { Text("Notes (markdown)") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(draft.date, { draft = draft.copy(date = it) }, label = { Text("Date (yyyy-MM-dd)") }, singleLine = true, modifier = Modifier.weight(1f))
                    Checkbox(checked = draft.allDay, onCheckedChange = { draft = draft.copy(allDay = it) })
                    Text("All day", color = Palette.Muted, fontSize = 11.sp)
                    if (!draft.allDay) OutlinedTextField(draft.time, { draft = draft.copy(time = it) }, label = { Text("Time") }, singleLine = true, modifier = Modifier.width(90.dp))
                }
                OutlinedTextField(draft.remindAt, { draft = draft.copy(remindAt = it) }, label = { Text("Remind at (yyyy-MM-ddTHH:mm, optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (draft.kind == "reminder") {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Remind", color = Palette.Muted, fontSize = 11.sp)
                        Chip("me", if (draft.target.isBlank()) Palette.Accent else Palette.Muted, Modifier.clickable { draft = draft.copy(target = "") })
                        calendarTargets(state).forEach { (t, label) -> Chip(label, if (draft.target == t) Palette.Accent else Palette.Muted, Modifier.clickable { draft = draft.copy(target = t) }) }
                    }
                }
                (problem?.takeIf { draft.title.isNotBlank() } ?: failure)?.let { Text(it, color = Palette.Danger, fontSize = 11.sp) }
            }
        },
        confirmButton = {
            Button(enabled = problem == null, onClick = { scope.launch { api.save(draft.toArgs()).onSuccess { onSaved(it) }.onFailure { failure = it.message } } }) {
                Text(if (initial.id == null) "Create" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
        wide = true,
    )
}
