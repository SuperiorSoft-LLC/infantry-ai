// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.calendar.daemon

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.storage.PluginStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class JdbcStorage(private val c: Connection) : PluginStorage {
    override fun migrate(version: Int, sql: List<String>) = sql.forEach { c.createStatement().use { s -> s.execute(it) } }
    override fun query(sql: String, args: List<Any?>): List<Map<String, Any?>> = c.prepareStatement(sql).use { ps ->
        args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
        ps.executeQuery().use { rs ->
            val cols = (1..rs.metaData.columnCount).map { rs.metaData.getColumnLabel(it) }
            generateSequence { if (rs.next()) cols.associateWith { k -> rs.getObject(k) } else null }.toList()
        }
    }
    override fun execute(sql: String, args: List<Any?>): Int = c.prepareStatement(sql).use { ps ->
        args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
        ps.executeUpdate()
    }
    override fun <T> transaction(block: () -> T): T = block()
}

private class FakeCall(
    override val arguments: JsonObject,
    override val caller: Caller = Caller(owner = true),
    override val clientSessionId: String? = null,
    override val clientRole: String? = null,
) : ToolCall {
    override fun request(type: String, data: String, sessionId: String?, timeoutMs: Long): Frame = error("no frame in this test")
}

class CalendarTest {
    private val file = File.createTempFile("calendar", ".db").also { it.deleteOnExit() }
    private val conn = DriverManager.getConnection("jdbc:sqlite:${file.path}")
    private val db = JdbcStorage(conn)
    private var now = 1_700_000_000_000L // 2023-11-14T22:13:20Z
    private val zone = ZoneId.of("UTC")
    private val changes = mutableListOf<CalendarChange>()
    private val store = CalendarStore(db) { now }.also { it.install() }
    private val service = CalendarService(store, { CalendarLimits(titleMax = 30, notesMax = 100, listMax = 3, eventsKeep = 3) }, { zone }, { now }, { it != "gone" }, triggers = { FakeTriggers() }) { changes += it }
    private val creators = CreatorOf { if (it == "sess-1") "agent-1" else null }

    @AfterTest fun close() { conn.close() }

    private fun call(tool: com.superiorsoftllc.infantry.daemon.api.DaemonTool, call: FakeCall) = tool.call(call)
    private fun args(vararg p: Pair<String, Any>) = buildJsonObject {
        p.forEach { (k, v) -> when (v) { is Boolean -> put(k, v); is Number -> put(k, v); else -> put(k, v.toString()) } }
    }
    private fun parse(r: com.superiorsoftllc.infantry.daemon.api.ToolResult) = Json.parseToJsonElement(r.text)

    @Test fun installCreatesBothTablesAndIsIdempotent() {
        store.install()
        val names = db.query("SELECT name FROM sqlite_master WHERE type IN ('table','index') AND name LIKE 'infantry_calendar_%'").map { it["name"] }
        assertTrue("infantry_calendar_items" in names && "infantry_calendar_events" in names)
        assertTrue("infantry_calendar_items_day" in names && "infantry_calendar_items_status_remind" in names && "infantry_calendar_items_project_day" in names)
    }

    @Test fun storeRoundTripListUpdateDelete() {
        val a = service.add(ItemInput(title = "Dentist", date = "2023-11-20", time = "09:30", kind = "reminder"), CalendarCreator.USER)
        val b = service.add(ItemInput(title = "Taxes", date = "2023-11-21"), CalendarCreator("agent", "agent-9", "s9", "pm"))
        assertEquals(listOf(a.id, b.id), store.list().map { it.id })
        assertEquals(listOf(b.id), store.list(ItemFilter(from = "2023-11-21")).map { it.id })
        assertEquals(listOf(b.id), store.list(ItemFilter(createdBy = "agent-9")).map { it.id })
        assertEquals(listOf(a.id), store.list(ItemFilter(createdBy = "user")).map { it.id })
        val up = service.update(a.id, ItemInput(title = "Dentist (moved)", time = "10:00"), "user")
        assertEquals("Dentist (moved)", up.title)
        assertEquals("2023-11-20", up.day)
        assertEquals(listOf("created", "edited"), store.events(a.id).map { it.kind })
        assertTrue(service.delete(a.id)); assertNull(store.get(a.id)); assertTrue(store.events(a.id).isEmpty())
        assertTrue(changes.any { it.op == "deleted" })
    }

    @Test fun reminderRingsAtItsTimeOrRemindBeforeOrRemindAt() {
        val at = service.add(ItemInput(kind = "reminder", title = "x", date = "2023-11-20", time = "09:30"), CalendarCreator.USER)
        val before = service.add(ItemInput(kind = "reminder", title = "y", date = "2023-11-20", time = "09:30", remindBefore = 15), CalendarCreator.USER)
        val abs = service.add(ItemInput(kind = "reminder", title = "z", date = "2023-11-20", time = "09:30", remindAt = "2023-11-20T08:00"), CalendarCreator.USER)
        assertEquals(at.startAt, at.remindAt)
        assertEquals(at.startAt!! - 15 * 60_000, before.remindAt)
        assertEquals(java.time.LocalDateTime.parse("2023-11-20T08:00").atZone(zone).toInstant().toEpochMilli(), abs.remindAt)
        now = abs.remindAt!!
        assertEquals(listOf(abs.id), store.dueReminders(now).map { it.id }.filter { it == abs.id })
    }

    @Test fun inMinutesAndAllDay() {
        val t = service.add(ItemInput(kind = "reminder", title = "soon", inMinutes = 10), CalendarCreator.USER)
        assertEquals(now + 600_000, t.startAt); assertEquals("2023-11-14", t.day); assertEquals(false, t.allDay)
        val d = service.add(ItemInput(title = "someday", date = "2023-12-01"), CalendarCreator.USER)
        assertTrue(d.allDay); assertNull(d.dueAt); assertEquals("2023-12-01", d.day)
        val r = service.add(ItemInput(kind = "reminder", title = "all day", date = "2023-12-01"), CalendarCreator.USER)
        assertNotNull(r.remindAt) // rings at the default morning hour
    }

    @Test fun validationRefusals() {
        assertFailsWith<CalendarRefusal> { service.add(ItemInput(title = ""), CalendarCreator.USER) }
        assertFailsWith<CalendarRefusal> { service.add(ItemInput(title = "x".repeat(31)), CalendarCreator.USER) }
        assertFailsWith<CalendarRefusal> { service.add(ItemInput(title = "x", notes = "n".repeat(101)), CalendarCreator.USER) }
        assertFailsWith<CalendarRefusal> { service.add(ItemInput(title = "x", kind = "meeting"), CalendarCreator.USER) }
        assertFailsWith<CalendarRefusal> { service.add(ItemInput(title = "x", date = "tomorrow"), CalendarCreator.USER) }
        assertFailsWith<CalendarRefusal> { service.add(ItemInput(kind = "reminder", title = "x", target = "everyone"), CalendarCreator.USER) }
        assertFailsWith<CalendarRefusal> { service.add(ItemInput(kind = "reminder", title = "x", target = "session:gone"), CalendarCreator.USER) }
        assertFailsWith<CalendarRefusal> { service.add(ItemInput(title = "x", target = "role:dev"), CalendarCreator.USER) } // a task has no target
        assertEquals(0, store.list().size)
    }

    @Test fun listIsCappedByTheLimit() {
        repeat(5) { service.add(ItemInput(title = "t$it", date = "2023-11-2$it"), CalendarCreator.USER) }
        assertEquals(3, service.list(ItemFilter()).size)
    }

    @Test fun historyKeepsOnlyTheLastN() {
        val i = service.add(ItemInput(title = "x"), CalendarCreator.USER)
        repeat(5) { service.update(i.id, ItemInput(title = "x$it"), "user") }
        assertEquals(3, store.events(i.id).size)
    }

    @Test fun toolsTakeCreatedByFromTheCaller() {
        val add = CalendarAddTool(service, creators)
        // an agent session
        val r = parse(call(add, FakeCall(args("title" to "from agent", "kind" to "reminder", "inMinutes" to 5, "target" to "role:pm"), Caller(owner = false, agent = true), "sess-1", "fullstack"))).jsonObject
        val by = r["createdBy"]!!.jsonObject
        assertEquals("agent", by["kind"]!!.jsonPrimitive.content)
        assertEquals("agent-1", by["agent"]!!.jsonPrimitive.content)
        assertEquals("sess-1", by["session"]!!.jsonPrimitive.content)
        assertEquals("fullstack", by["role"]!!.jsonPrimitive.content)
        assertEquals("role:pm", r["target"]!!.jsonPrimitive.content)
        // a person at the CLI (no session)
        val u = parse(call(add, FakeCall(args("title" to "from me")))).jsonObject
        assertEquals("user", u["createdBy"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
        // a caller-supplied createdBy is not a thing: extra argument is ignored, never trusted
        val forged = parse(call(add, FakeCall(args("title" to "forge", "createdBy" to "user"), Caller(owner = false, agent = true), "sess-1", "dev"))).jsonObject
        assertEquals("agent", forged["createdBy"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
    }

    @Test fun toolsListGetUpdateCompleteTimer() {
        val id = parse(call(CalendarTimerTool(service, creators), FakeCall(args("title" to "tea", "minutes" to 3)))).jsonObject["id"]!!.jsonPrimitive.content
        val list = parse(call(CalendarListTool(service), FakeCall(args()))).jsonArray
        assertEquals(1, list.size)
        val got = parse(call(CalendarGetTool(service), FakeCall(args("id" to id)))).jsonObject
        assertEquals(1, got["history"]!!.jsonArray.size)
        val upd = parse(call(CalendarUpdateTool(service, creators), FakeCall(args("id" to id, "title" to "coffee")))).jsonObject
        assertEquals("coffee", upd["title"]!!.jsonPrimitive.content)
        val done = parse(call(CalendarCompleteTool(service, creators), FakeCall(args("id" to id)))).jsonObject
        assertEquals("done", done["status"]!!.jsonPrimitive.content)
        val cancelled = parse(call(CalendarCompleteTool(service, creators), FakeCall(args("id" to id, "status" to "cancelled")))).jsonObject
        assertEquals("cancelled", cancelled["status"]!!.jsonPrimitive.content)
        val miss = call(CalendarGetTool(service), FakeCall(args("id" to "cal-nope")))
        assertTrue(miss.isError)
        assertTrue(call(CalendarTimerTool(service, creators), FakeCall(args("title" to "no minutes"))).isError)
    }

    @Test fun everyWriteBroadcastsAChange() {
        val i = service.add(ItemInput(title = "x", date = "2023-11-30"), CalendarCreator.USER)
        service.update(i.id, ItemInput(date = "2023-12-02"), "user")
        service.complete(i.id, null, "user")
        service.delete(i.id)
        assertEquals(listOf("added", "updated", "status", "deleted"), changes.map { it.op })
        assertEquals(listOf("2023-11-30", "2023-12-02"), changes[1].days)
    }

    @Test fun clearWipesEverything() {
        service.add(ItemInput(title = "a"), CalendarCreator.USER); service.add(ItemInput(title = "b"), CalendarCreator.USER)
        assertEquals(2, service.clear())
        assertTrue(store.list().isEmpty())
    }
}
