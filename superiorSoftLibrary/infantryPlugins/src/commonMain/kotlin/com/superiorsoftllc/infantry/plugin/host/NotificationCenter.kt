// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugin.host

import com.superiorsoftllc.infantry.plugin.api.Notification
import com.superiorsoftllc.infantry.plugin.api.NotificationActionEvent
import com.superiorsoftllc.infantry.plugin.api.NotificationChannels
import com.superiorsoftllc.infantry.plugin.api.NotificationLevel
import com.superiorsoftllc.infantry.plugin.api.NotificationPresenter
import com.superiorsoftllc.infantry.plugin.api.NotificationSettings
import com.superiorsoftllc.infantry.plugin.api.Notifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * NOTIFY-CORE-1 (docs/wiki/Notifications.md): the notification bus of one host. Pure (no UI, no platform), so every client
 * shares it and the daemon's hub uses the same dedupe rule.
 *
 * [deliver] stamps the notification (id, time), drops a repeat (same id, or the same dedupe key within the window), routes it
 * to its channels (the level's route, or its own [Notification.channels]; quiet hours keep all but urgent off the OS), and
 * hands it to each channel's ONE active presenter:
 *  - `notifications.presenter.<channel>` = a module name: that module's presenter; `core`: the core default; blank: the last
 *    plugin presenter registered for the channel, else the core default.
 *  - A plugin presenter that throws is contained (reported to [onFault], skipped from then on) and the core default shows it;
 *    one that answers false (cannot show it) falls back to the core default too.
 *  - A channel nobody can show falls back to [NotificationChannels.IN_APP], so nothing routed is lost.
 * Every delivered notification lands in [history] (the inbox), newest first, at most `notifications.historyMax`.
 */
class NotificationCenter(
    private val settings: () -> NotificationSettings = { NotificationSettings() },
    private val clock: () -> Long = { 0L },
    private val minuteOfDay: () -> Int? = { null },
    private val newId: () -> String = SequentialIds()::next,
    private val onFault: (presenterId: String, channel: String, error: Throwable) -> Unit = { _, _, _ -> },
    private val source: String = "",
) : Notifications {

    /** One notification in the history: where it was shown (channel -> presenter id), how often it repeated, whether dismissed. */
    data class Record(
        val notification: Notification,
        val shownOn: Map<String, String> = emptyMap(),
        val repeats: Int = 0,
        val dismissed: Boolean = false,
    )

    /** What [deliver] did: [deduped] = dropped as a repeat; else [shownOn] names each channel's presenter. */
    data class Delivery(val notification: Notification, val deduped: Boolean, val shownOn: Map<String, String> = emptyMap())

    private class Entry(val id: String, val channels: Set<String>, val presenter: NotificationPresenter, val seq: Long)

    private data class State(
        val history: List<Record> = emptyList(),
        val lastByKey: Map<String, Pair<Long, String>> = emptyMap(),
        val presenters: List<Entry> = emptyList(),
        val defaults: Map<String, NotificationPresenter> = emptyMap(),
        val faulted: Set<String> = emptySet(),
        val listeners: List<Pair<Long, (NotificationActionEvent) -> Unit>> = emptyList(),
        val seq: Long = 0,
    )

    private val state = MutableStateFlow(State())
    private val _history = MutableStateFlow<List<Record>>(emptyList())

    /** The inbox: every delivered notification, newest first. */
    val history: StateFlow<List<Record>> = _history.asStateFlow()

    /** Sets the core default presenter of [channel] (the in-app toast host is the one every client has). */
    fun setDefault(channel: String, presenter: NotificationPresenter?) {
        state.update { s -> s.copy(defaults = if (presenter == null) s.defaults - channel else s.defaults + (channel to presenter)) }
    }

    override fun present(presenterId: String, channels: Set<String>, presenter: NotificationPresenter): AutoCloseable {
        var mine = 0L
        state.update { s ->
            mine = s.seq + 1
            s.copy(
                presenters = s.presenters + Entry(presenterId, channels, presenter, mine),
                faulted = s.faulted - presenterId,
                seq = mine,
            )
        }
        return AutoCloseable { state.update { s -> s.copy(presenters = s.presenters.filterNot { it.seq == mine }) } }
    }

    /** The id of the presenter that shows [channel] now: a module name, [NotificationChannels.CORE], or null when nobody can. */
    fun active(channel: String): String? = pick(state.value, channel)?.first

    /** The channels [n] goes to now. */
    fun channelsFor(n: Notification): List<String> {
        val s = settings()
        val wanted = n.channels.ifEmpty { s.route(n.level) }
        return if (n.level != NotificationLevel.URGENT && s.quiet(minuteOfDay())) wanted - NotificationChannels.OS else wanted
    }

    override fun notify(title: String, body: String) {
        notify(Notification(title = title, body = body))
    }

    override fun notify(notification: Notification): String = deliver(notification).notification.id

    /** Posts [notification]; see the class comment for the rules. */
    fun deliver(notification: Notification): Delivery {
        val now = clock()
        val n = notification.copy(
            id = notification.id.ifBlank { newId() },
            at = if (notification.at > 0) notification.at else now,
            source = notification.source.ifBlank { source },
        )
        val cfg = settings()
        val windowMs = cfg.dedupeWindowSec * MILLIS_PER_SECOND
        var repeat = false
        state.update { s ->
            val key = n.dedupeKey?.takeIf { it.isNotBlank() }
            val last = key?.let { s.lastByKey[it] }
            val sameId = s.history.any { it.notification.id == n.id }
            val sameKey = last != null && windowMs > 0 && now - last.first < windowMs
            repeat = sameId || sameKey
            when {
                sameId -> s
                sameKey -> s.copy(
                    lastByKey = s.lastByKey + (key!! to (now to last!!.second)),
                    history = s.history.map { r -> if (r.notification.id == last.second) r.copy(repeats = r.repeats + 1) else r },
                )
                else -> s.copy(lastByKey = if (key != null) s.lastByKey + (key to (now to n.id)) else s.lastByKey)
            }
        }
        if (repeat) {
            publishHistory()
            return Delivery(n, deduped = true)
        }
        val shown = LinkedHashMap<String, String>()
        val channels = channelsFor(n)
        for (channel in channels) {
            val by = showOn(channel, n)
            if (by != null) shown[channel] = by
            else if (channel != NotificationChannels.IN_APP && NotificationChannels.IN_APP !in channels && NotificationChannels.IN_APP !in shown) {
                showOn(NotificationChannels.IN_APP, n)?.let { shown[NotificationChannels.IN_APP] = it }
            }
        }
        state.update { s -> s.copy(history = (listOf(Record(n, shown)) + s.history).take(cfg.historyMax.coerceAtLeast(1))) }
        publishHistory()
        return Delivery(n, deduped = false, shownOn = shown)
    }

    override fun dismiss(id: String) {
        var record: Record? = null
        state.update { s ->
            record = s.history.firstOrNull { it.notification.id == id }
            s.copy(history = s.history.map { if (it.notification.id == id) it.copy(dismissed = true) else it })
        }
        publishHistory()
        val r = record ?: return
        val s = state.value
        r.shownOn.forEach { (channel, by) ->
            val p = if (by == NotificationChannels.CORE) s.defaults[channel] else s.presenters.lastOrNull { it.id == by && channel in it.channels }?.presenter
            runCatching { p?.dismiss(id) }
        }
    }

    /** Clears the history (the inbox's "Clear"). */
    fun clearHistory() {
        state.update { it.copy(history = emptyList()) }
        publishHistory()
    }

    /** Action [actionId] of notification [notificationId] was pressed: tells every listener (a throwing one is skipped). */
    fun action(notificationId: String, actionId: String) {
        val n = state.value.history.firstOrNull { it.notification.id == notificationId }?.notification
        val event = NotificationActionEvent(notificationId, actionId, n?.source.orEmpty())
        state.value.listeners.forEach { (_, l) -> runCatching { l(event) } }
    }

    override fun onAction(listener: (NotificationActionEvent) -> Unit): AutoCloseable {
        var mine = 0L
        state.update { s -> mine = s.seq + 1; s.copy(listeners = s.listeners + (mine to listener), seq = mine) }
        return AutoCloseable { state.update { s -> s.copy(listeners = s.listeners.filterNot { it.first == mine }) } }
    }

    /** Shows [n] on [channel]: the chosen presenter, then the core default; the answer is who showed it, or null. */
    private fun showOn(channel: String, n: Notification): String? {
        val s = state.value
        val (id, presenter) = pick(s, channel) ?: return null
        val ok = try {
            presenter.show(n)
        } catch (e: Throwable) {
            if (id != NotificationChannels.CORE) {
                state.update { it.copy(faulted = it.faulted + id) }
                runCatching { onFault(id, channel, e) }
            }
            false
        }
        if (ok) return id
        if (id == NotificationChannels.CORE) return null
        val fallback = state.value.defaults[channel] ?: return null
        return if (runCatching { fallback.show(n) }.getOrDefault(false)) NotificationChannels.CORE else null
    }

    private fun pick(s: State, channel: String): Pair<String, NotificationPresenter>? {
        val core = s.defaults[channel]?.let { NotificationChannels.CORE to it }
        val chosen = settings().presenter(channel)
        if (chosen == NotificationChannels.CORE) return core
        val candidates = s.presenters.filter { channel in it.channels && it.id !in s.faulted }
        val entry = if (chosen.isBlank()) candidates.maxByOrNull { it.seq } else candidates.lastOrNull { it.id == chosen }
        return entry?.let { it.id to it.presenter } ?: core
    }

    private fun publishHistory() {
        _history.value = state.value.history
    }

    /** Ids `n1`, `n2`, ... when the host gives no generator. */
    class SequentialIds(private val prefix: String = "n") {
        private val n = MutableStateFlow(0L)
        fun next(): String {
            var v = 0L
            n.update { v = it + 1; v }
            return "$prefix$v"
        }
    }

    companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}
