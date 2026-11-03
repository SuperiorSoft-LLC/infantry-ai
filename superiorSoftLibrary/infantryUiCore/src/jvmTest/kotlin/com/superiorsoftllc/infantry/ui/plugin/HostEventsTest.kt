package com.superiorsoftllc.infantry.ui.plugin

import com.superiorsoftllc.infantry.plugin.api.Events
import com.superiorsoftllc.infantry.plugin.api.PluginEvent
import com.superiorsoftllc.infantry.plugins.core.CoreModules
import com.superiorsoftllc.infantry.ui.protocol.Ticket
import kotlin.test.Test
import kotlin.test.assertEquals

/** HOOKS-1: the `modules.loaded` and `ticket.changed` events the window publishes. */
class HostEventsTest {
    private fun ticket(id: String, status: String = "todo", title: String = id) = Ticket(id = id, title = title, status = status)

    @Test
    fun `modules loaded names every bound module in load order`() {
        val e = HostEvents.modulesLoaded(CoreModules.factory)
        assertEquals(Events.MODULES_LOADED, e.name)
        assertEquals(CoreModules.all.joinToString(",") { it.manifest.name }, e.data["modules"])
        assertEquals(CoreModules.all.size.toString(), e.data["count"])
    }

    @Test
    fun `the first board is the baseline and publishes nothing`() {
        assertEquals(emptyList(), HostEvents.ticketChanges(emptyList(), listOf(ticket("a"), ticket("b"))))
    }

    @Test
    fun `a created, an updated and a removed ticket each publish one event, an unchanged one none`() {
        val before = listOf(ticket("a"), ticket("b"), ticket("c"))
        val after = listOf(ticket("a"), ticket("b", status = "done"), ticket("d"))
        fun e(id: String, status: String, change: String) = PluginEvent(Events.TICKET_CHANGED, mapOf("id" to id, "status" to status, "title" to id, "change" to change))
        assertEquals(listOf(e("b", "done", "updated"), e("d", "todo", "created"), e("c", "todo", "removed")), HostEvents.ticketChanges(before, after))
    }
}
