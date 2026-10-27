package com.superiorsoftllc.agenthq.ui.plugin

import com.superiorsoftllc.agenthq.plugin.api.Events
import com.superiorsoftllc.agenthq.plugin.api.PluginEvent
import com.superiorsoftllc.agenthq.ui.protocol.Ticket

/**
 * HOOKS-1: the events the window publishes besides `tab.opened` and `session.selected`, as pure functions so each one
 * is tested without a window. App.kt publishes them on [TabFactory.events].
 */
object HostEvents {
    /**
     * `modules.loaded`, once the factory has bound every module (its observers are subscribed by then):
     * `modules` = the loaded module names in load order, comma-separated; `count` = how many.
     */
    fun modulesLoaded(factory: TabFactory): PluginEvent =
        PluginEvent(Events.MODULES_LOADED, mapOf("modules" to factory.modules.joinToString(",") { it.manifest.name }, "count" to factory.modules.size.toString()))

    /**
     * `ticket.changed`, one per ticket that differs between two consecutive board lists: `id`, `status`, `title` and
     * `change` (`created`, `updated` or `removed`). The first list a window gets (from empty: the connect-time board)
     * is the baseline and publishes nothing.
     */
    fun ticketChanges(before: List<Ticket>, after: List<Ticket>): List<PluginEvent> {
        if (before.isEmpty()) return emptyList()
        val old = before.associateBy { it.id }
        val now = after.associateBy { it.id }
        fun event(t: Ticket, change: String) = PluginEvent(Events.TICKET_CHANGED, mapOf("id" to t.id, "status" to t.status, "title" to t.title, "change" to change))
        return after.mapNotNull { t ->
            when (val o = old[t.id]) {
                null -> event(t, "created")
                t -> null
                else -> event(t, "updated")
            }
        } + before.filter { it.id !in now }.map { event(it, "removed") }
    }
}
