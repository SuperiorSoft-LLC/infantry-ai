// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugin.api

import com.superiorsoftllc.infantry.plugin.host.PluginJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PK-1: module.json `knowledge` parses, validates and scopes. */
class KnowledgeDeclarationTest {
    private val manifest = """
        { "name": "Acme_Cal", "version": "1.2.0",
          "files": ["knowledge"],
          "knowledge": [
            { "id": "calendar", "summary": "Calendar: add reminders (infantry_calendar_add).", "file": "knowledge/calendar.md",
              "tools": ["infantry_calendar_add"], "roles": ["*"], "skipRoles": ["qa"], "projects": [] },
            { "id": "calendar-assistant", "summary": "Tell the user about the Calendar tab.", "roles": ["assistant"] }
          ] }
    """.trimIndent()

    @Test
    fun theKnowledgeArrayParsesWithDefaults() {
        val m = PluginJson.decodeFromString(ModuleManifest.serializer(), manifest)
        assertEquals(2, m.knowledge.size)
        val cal = m.knowledge[0]
        assertEquals("knowledge/calendar.md", cal.file)
        assertEquals(listOf("infantry_calendar_add"), cal.tools)
        assertEquals(listOf("qa"), cal.skipRoles)
        val asst = m.knowledge[1]
        assertEquals("", asst.file)
        assertEquals(emptyList(), asst.projects)
        assertTrue(KnowledgeDeclarations.problems(m.knowledge).isEmpty(), KnowledgeDeclarations.problems(m.knowledge).toString())
    }

    @Test
    fun aManifestWithoutKnowledgeHasNone() {
        assertEquals(emptyList(), PluginJson.decodeFromString(ModuleManifest.serializer(), """{ "name": "A_B", "version": "1.0.0" }""").knowledge)
    }

    @Test
    fun badEntriesAreReportedAndDropped() {
        val bad = listOf(
            KnowledgeDeclaration("Bad Id", "s"),
            KnowledgeDeclaration("blank", " "),
            KnowledgeDeclaration("twoline", "a\nb"),
            KnowledgeDeclaration("escape", "s", file = "../secret.md"),
            KnowledgeDeclaration("abs", "s", file = "/etc/x.md"),
            KnowledgeDeclaration("exe", "s", file = "knowledge/x.sh"),
            KnowledgeDeclaration("tool", "s", tools = listOf("has space")),
            KnowledgeDeclaration("role", "s", roles = listOf("Not_A_Role!")),
            KnowledgeDeclaration("skip", "s", skipRoles = listOf("*")),
            KnowledgeDeclaration("ok", "fine"),
            KnowledgeDeclaration("ok", "duplicate"),
        )
        for (d in bad.dropLast(2)) assertFalse(KnowledgeDeclarations.problems(d).isEmpty(), "expected a problem for ${d.id}")
        assertTrue(KnowledgeDeclarations.problems(bad).any { "declared twice" in it })
        assertEquals(listOf(KnowledgeDeclaration("ok", "fine")), KnowledgeDeclarations.valid(bad))
    }

    @Test
    fun scopeFollowsRolesSkipRolesAndProjects() {
        val every = KnowledgeDeclaration("a", "s")
        assertTrue(every.appliesTo("pm", "p1") && every.appliesTo(null, null))
        val star = KnowledgeDeclaration("b", "s", roles = listOf("*"), skipRoles = listOf("QA"))
        assertTrue(star.appliesTo("pm", null))
        assertFalse(star.appliesTo("qa", null))
        val asst = KnowledgeDeclaration("c", "s", roles = listOf("assistant"))
        assertTrue(asst.appliesTo("Assistant", null))
        assertFalse(asst.appliesTo("pm", null))
        assertFalse(asst.appliesTo(null, null))
        val p1 = KnowledgeDeclaration("d", "s", projects = listOf("p1"))
        assertTrue(p1.appliesTo("pm", "p1"))
        assertFalse(p1.appliesTo("pm", "p2"))
        assertFalse(p1.appliesTo("pm", null))
        assertTrue(KnowledgeDeclarations.roleSpecific(listOf("pm"), "pm"))
        assertFalse(KnowledgeDeclarations.roleSpecific(listOf("*"), "pm"))
    }
}
