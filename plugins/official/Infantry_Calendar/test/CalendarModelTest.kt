// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.calendar

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CAL-2: the Calendar tab's pure rules. */
class CalendarModelTest {
    private fun item(id: String, day: String, zone: String = "UTC", allDay: Boolean = true, at: Long? = null, status: String = "open", kind: String = "task") =
        CalItem(id, kind, id, status, day, allDay, zone, null, null, at, null, null, "user")

    @Test
    fun `an item stays on its own day whatever zone it was made in`() {
        // 2026-11-17 23:30 in Tokyo is 14:30Z the same day; 2026-11-17 01:00 in Los Angeles is 09:00Z. Both are the 17th by their own day.
        val tokyo = item("t", "2026-11-17", "Asia/Tokyo", false, 1_794_000_000_000L)
        val la = item("l", "2026-11-17", "America/Los_Angeles", false, 1_794_000_100_000L)
        val other = item("o", "2026-11-18")
        val m = CalendarModel.byDay(listOf(tokyo, la, other))
        assertEquals(setOf("t", "l"), m.getValue(LocalDate(2026, 11, 17)).map { it.id }.toSet())
        assertEquals(listOf("o"), m.getValue(LocalDate(2026, 11, 18)).map { it.id })
    }

    @Test
    fun `a day lists all-day items first, then by time`() {
        val late = item("late", "2026-11-17", allDay = false, at = 2_000L)
        val early = item("early", "2026-11-17", allDay = false, at = 1_000L)
        val all = item("all", "2026-11-17")
        assertEquals(listOf("all", "early", "late"), CalendarModel.byDay(listOf(late, early, all)).getValue(LocalDate(2026, 11, 17)).map { it.id })
    }

    @Test
    fun `dots cap at three with a plus-n`() {
        assertEquals(DayDots(emptyList(), 0), CalendarModel.dots(null))
        assertEquals(DayDots(listOf("task", "task"), 0), CalendarModel.dots(listOf(item("a", "d"), item("b", "d"))))
        val five = (1..5).map { item("i$it", "d", kind = if (it == 2) "reminder" else "task") }
        assertEquals(DayDots(listOf("task", "reminder", "task"), 2), CalendarModel.dots(five))
        assertEquals(listOf("task.closed"), CalendarModel.dots(listOf(item("x", "d", status = "done"))).kinds)
    }

    @Test
    fun `time label is the item's own zone`() {
        // 2026-11-17T03:00:00Z is 12:00 in Tokyo.
        val ms = 1_794_020_400_000L
        assertEquals("12:00", CalendarModel.timeLabel(item("a", "2026-11-17", "Asia/Tokyo", false, ms)))
        assertEquals("All day", CalendarModel.timeLabel(item("b", "2026-11-17")))
    }

    @Test
    fun `an item parses from the daemon json and a broken one is skipped`() {
        val json = kotlinx.serialization.json.Json.parseToJsonElement(
            """[{"id":"1","kind":"reminder","title":"T","status":"open","day":"2026-11-17","allDay":true,"zone":"UTC","target":"role:qa","createdBy":{"kind":"agent","role":"pm"},
               "history":[{"at":5,"kind":"created","by":"pm"}]},{"nope":1}]""",
        )
        val items = CalendarModel.parseItems(json)
        assertEquals(1, items.size)
        assertEquals("pm", items[0].createdBy)
        assertEquals("role:qa", items[0].target)
        assertEquals("created", items[0].history.single().kind)
    }

    @Test
    fun `a draft says what is wrong and builds the save body`() {
        assertNotNull(CalendarDraft().problem())
        val ok = CalendarDraft(title = "Call", date = "2026-11-17")
        assertNull(ok.problem())
        assertNotNull(ok.copy(date = "17/11").problem())
        assertNotNull(ok.copy(allDay = false, time = "25:00").problem())
        assertNotNull(ok.copy(target = "role:qa").problem(), "only a reminder has a target")
        assertNull(ok.copy(kind = "reminder", target = "role:qa").problem())
        val body = ok.copy(allDay = false, time = "09:30", remindAt = "2026-11-17T09:00").toArgs()
        assertTrue("\"time\":\"09:30\"" in body && "\"remindAt\":\"2026-11-17T09:00\"" in body && "\"id\"" !in body, body)
        assertTrue("\"target\":\"\"" in ok.copy(id = "x").toArgs(), "an edit clears the target with a blank")
    }
}
