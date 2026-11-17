// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.calendar.daemon

import com.superiorsoftllc.infantry.daemon.api.triggers.TriggerActionRecord
import com.superiorsoftllc.infantry.daemon.api.triggers.TriggerFilter
import com.superiorsoftllc.infantry.daemon.api.triggers.TriggerSaveRequest
import com.superiorsoftllc.infantry.daemon.api.triggers.Triggers
import com.superiorsoftllc.infantry.plugin.api.Notification
import com.superiorsoftllc.infantry.plugin.api.NotificationAction
import com.superiorsoftllc.infantry.plugin.api.NotificationActionEvent
import com.superiorsoftllc.infantry.plugin.api.NotificationLevel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One trigger Calendar owns: its id, the calendar item its ref names (null = not a calendar ref), and its state (`armed`, `running`, ...). */
data class OwnedTrigger(val id: String, val itemId: String?, val state: String)

/**
 * CAL-3: how an agent-targeted reminder reaches its agent: one owned one-shot `at` trigger per item, kept by the Triggers plugin
 * (CAL-PLAN-2 A5). [CalendarService] talks to this port only, so a test fakes it and a daemon without Triggers answers none.
 */
interface ReminderTriggers {
    /** Creates or updates the trigger of [item] (its [existingId] when it still exists); answers the trigger id. A refusal throws [CalendarRefusal]. */
    fun upsert(item: CalendarItem, existingId: String?): String
    /** Removes trigger [id]; a running one is left for its own one-shot cleanup. */
    fun delete(id: String)
    /** Every trigger this module owns. */
    fun owned(): List<OwnedTrigger>
}

/** CAL-3: [ReminderTriggers] over the Triggers SDK service (`services.find(Triggers::class)`, scoped: the owner is stamped by the host). */
class TriggersLink(private val triggers: Triggers, private val owner: String) : ReminderTriggers {
    override fun upsert(item: CalendarItem, existingId: String?): String {
        val existing = existingId?.let { runCatching { triggers.get(it) }.getOrNull() }?.takeIf { it.owner == owner }
        val request = TriggerSaveRequest(
            id = existing?.id,
            name = (NAME_PREFIX + item.title).take(TRIGGER_NAME_MAX),
            kind = "at",
            coalesceMs = 0,
            action = TriggerActionRecord(kind = "prompt", agentId = item.target, prompt = promptOf(item)),
            projectId = item.projectId,
            fireAt = item.remindAt,
            deleteAfterFire = true,
            ref = REF_PREFIX + item.id,
        )
        return try { triggers.save(request).id } catch (e: IllegalArgumentException) { throw CalendarRefusal("the reminder trigger was refused: ${e.message}") }
    }

    override fun delete(id: String) {
        val t = runCatching { triggers.get(id) }.getOrNull() ?: return
        if (t.owner != owner || t.state == RUNNING) return
        runCatching { triggers.delete(id) }
    }

    override fun owned(): List<OwnedTrigger> = triggers.list(TriggerFilter(owner = owner)).map {
        OwnedTrigger(it.id, it.ref?.takeIf { r -> r.startsWith(REF_PREFIX) }?.removePrefix(REF_PREFIX), it.state)
    }

    companion object {
        const val REF_PREFIX = "calendar:"
        const val NAME_PREFIX = "Calendar: "
        /** The Triggers service refuses a longer name. */
        const val TRIGGER_NAME_MAX = 100
        const val RUNNING = "running"

        /** What the agent reads when the reminder fires (the mail, or the first turn of a session the trigger starts). */
        fun promptOf(item: CalendarItem): String = buildString {
            append("Calendar reminder (").append(item.id).append("): ").append(item.title)
            item.notes?.let { append("\n\n").append(it) }
            append("\n\nMark it done with infantry_calendar_complete id=").append(item.id).append(" when it is handled.")
        }
    }
}

/** What one tick did (for tests and the log). */
data class TickResult(val fired: List<String>, val missed: List<String>)

/**
 * CAL-3: fires due alarms. [tick] runs on `api.schedule("calendar.tick")`. Each due row is claimed ONCE in the database
 * ([CalendarStore.claimAlarm]: fired_at set while still null), so a second tick, or a restart that re-reads the row, never fires it
 * again; the claim comes BEFORE the notice, so a crash between the two loses a notice rather than repeating one. Late by at most
 * [maxLateMs] (a restart) = fired once; later = `missed`, and all of one tick's misses share one summary notification.
 *
 * Firing a user reminder = a notification-bus post with Open / Snooze / Done. An agent-targeted reminder's agent side is its owned
 * `at` trigger (the Triggers plugin fires it on its own clock); the user is told here too.
 */
class CalendarReminders(
    private val store: CalendarStore,
    private val service: CalendarService,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxLateMs: () -> Long = { DEFAULT_MAX_LATE_MINUTES * CalendarService.MS_PER_MINUTE },
    private val notify: (Notification) -> Unit = {},
    private val dismiss: (String) -> Unit = {},
    private val openItem: (String) -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    fun tick(): TickResult {
        val now = clock()
        val keep = service.eventsKeep()
        val fired = mutableListOf<CalendarItem>()
        val missed = mutableListOf<CalendarItem>()
        for (item in store.dueReminders(now)) {
            val at = item.remindAt ?: continue
            val reminder = item.kind == "reminder"
            if (now - at <= maxLateMs().coerceAtLeast(0L)) {
                if (store.claimAlarm(item.id, if (reminder) "fired" else null, "fired", keep)) fired += store.get(item.id) ?: item
            } else if (store.claimAlarm(item.id, if (reminder) "missed" else null, "missed", keep)) {
                missed += store.get(item.id) ?: item
                // A missed alarm never reaches its agent either: the owned trigger goes (Triggers would record its own miss anyway).
                runCatching { service.syncTrigger(store.get(item.id) ?: item) }.onFailure { log("calendar: trigger cleanup for ${item.id} failed: ${it.message}") }
            }
        }
        fired.forEach { item ->
            runCatching { notify(notice(item)) }.onFailure { log("calendar: notice for ${item.id} failed: ${it.message}") }
            service.published(CalendarService.EVENT_FIRED, item)
        }
        if (missed.isNotEmpty()) runCatching { notify(missedSummary(missed, now)) }.onFailure { log("calendar: missed summary failed: ${it.message}") }
        if (fired.isNotEmpty() || missed.isNotEmpty()) service.changedNow(CalendarChange("fired", (fired + missed).map { it.id }, (fired + missed).map { it.day }.distinct()))
        return TickResult(fired.map { it.id }, missed.map { it.id })
    }

    /**
     * CAL-3: the start-up pass (and after Triggers loads): an item that wants a trigger and has none (or whose trigger is gone) gets one;
     * an owned trigger whose item is gone, or that is not its item's trigger any more, is deleted. A closed item drops its trigger.
     * Answers (created, deleted) counts. No Triggers plugin = nothing to do.
     */
    fun reconcile(): Pair<Int, Int> {
        val link = service.link() ?: return 0 to 0
        val owned = link.owned()
        val ownedIds = owned.map { it.id }.toSet()
        var created = 0
        val deleted = HashSet<String>()
        val items = store.linked().associateBy { it.id }
        for (item in items.values) {
            try {
                if (CalendarService.wantsTrigger(item)) {
                    if (item.triggerId == null || item.triggerId !in ownedIds) {
                        service.syncTrigger(if (item.triggerId != null) item.copy(triggerId = null).also { store.setTrigger(it.id, null) } else item)
                        created++
                    }
                } else if (item.triggerId != null && item.triggerId !in ownedIds) {
                    store.setTrigger(item.id, null)
                } else if (item.triggerId != null) {
                    if (service.syncTrigger(item).triggerId == null) deleted += item.triggerId
                }
            } catch (r: CalendarRefusal) {
                log("calendar: reconcile of ${item.id} refused: ${r.message}")
            }
        }
        for (t in owned) {
            val item = t.itemId?.let { store.get(it) }
            // A fired item's trigger may still be delivering (it deletes itself after its run): the one its item names is kept.
            if ((item == null || item.triggerId != t.id) && t.id !in deleted) runCatching { link.delete(t.id) }.onSuccess { deleted += t.id }
        }
        if (created > 0 || deleted.isNotEmpty()) log("calendar: reconcile created $created and deleted ${deleted.size} reminder trigger(s)")
        return created to deleted.size
    }

    /** A pressed button on one of this module's notifications. */
    fun onAction(e: NotificationActionEvent) {
        val itemId = itemOf(e.notificationId) ?: return
        try {
            when (e.actionId) {
                ACTION_OPEN -> openItem(itemId)
                ACTION_DONE -> { service.complete(itemId, "done", "user"); dismiss(e.notificationId) }
                else -> SNOOZES[e.actionId]?.let { preset -> service.snooze(itemId, preset, "user"); dismiss(e.notificationId) }
            }
        } catch (r: CalendarRefusal) {
            log("calendar: notification action ${e.actionId} on $itemId refused: ${r.message}")
        }
    }

    private fun notice(item: CalendarItem): Notification = Notification(
        title = item.title,
        body = buildString {
            append(if (item.kind == "reminder") "Reminder" else "Task").append(" at ").append(stamp(item.remindAt ?: clock(), item.zone))
            item.target?.let { append(" (also sent to ").append(it).append(")") }
            item.notes?.let { append("\n").append(it.take(NOTICE_NOTES_MAX)) }
        },
        level = NotificationLevel.INFO,
        id = NOTICE_PREFIX + item.id + ":" + (item.firedAt ?: clock()),
        actions = listOf(
            NotificationAction(ACTION_OPEN, "Open"), NotificationAction(SNOOZE_5M, "Snooze 5 min"), NotificationAction(SNOOZE_1H, "Snooze 1 h"),
            NotificationAction(SNOOZE_TOMORROW, "Tomorrow"), NotificationAction(ACTION_DONE, "Done"),
        ),
        sticky = true,
        projectId = item.projectId,
    )

    private fun missedSummary(missed: List<CalendarItem>, now: Long): Notification {
        val single = missed.singleOrNull()
        return Notification(
            title = if (single != null) "Missed reminder: ${single.title}" else "${missed.size} reminders were missed",
            body = missed.take(MISSED_LIST_MAX).joinToString("\n") { "- ${it.title} (${stamp(it.remindAt ?: now, it.zone)})" } +
                if (missed.size > MISSED_LIST_MAX) "\n... and ${missed.size - MISSED_LIST_MAX} more" else "",
            level = NotificationLevel.WARNING,
            // One summary per tick; the first item's id rides in the notification id, so Open goes to it.
            id = NOTICE_PREFIX + missed.first().id + ":missed:" + now,
            actions = if (single != null) listOf(NotificationAction(ACTION_OPEN, "Open"), NotificationAction(SNOOZE_5M, "Snooze 5 min"), NotificationAction(ACTION_DONE, "Done")) else listOf(NotificationAction(ACTION_OPEN, "Open")),
            sticky = true,
        )
    }

    private fun stamp(at: Long, zone: String): String =
        STAMP.format(Instant.ofEpochMilli(at).atZone(runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.systemDefault())))

    companion object {
        const val DEFAULT_TICK_SECONDS = 15L
        const val DEFAULT_MAX_LATE_MINUTES = 30L
        const val NOTICE_PREFIX = "calendar:"
        const val ACTION_OPEN = "open"
        const val ACTION_DONE = "done"
        const val SNOOZE_5M = "snooze.5m"
        const val SNOOZE_1H = "snooze.1h"
        const val SNOOZE_TOMORROW = "snooze.tomorrow"
        val SNOOZES = mapOf(SNOOZE_5M to "5m", SNOOZE_1H to "1h", SNOOZE_TOMORROW to "tomorrow")
        /** How many missed titles one summary lists. */
        const val MISSED_LIST_MAX = 10
        /** How much of the item's notes a notice carries. */
        const val NOTICE_NOTES_MAX = 300
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        /** The item id in one of this module's notification ids (`calendar:<item>:<...>`), or null. */
        fun itemOf(notificationId: String): String? =
            notificationId.takeIf { it.startsWith(NOTICE_PREFIX) }?.removePrefix(NOTICE_PREFIX)?.substringBefore(':')?.takeIf { it.isNotEmpty() }
    }
}
