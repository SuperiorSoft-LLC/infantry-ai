// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.notes.daemon

import com.superiorsoftllc.infantry.daemon.api.SessionInfo
import com.superiorsoftllc.infantry.daemon.api.knowledge.BriefingBudget
import com.superiorsoftllc.infantry.daemon.api.knowledge.KnowledgeEntry
import com.superiorsoftllc.infantry.daemon.api.plugins.LoadedModule
import com.superiorsoftllc.infantry.plugin.api.KnowledgeDeclaration
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PK-2: the notes, the briefing answer and the live notices of [PluginKnowledgeProvider], over fakes. */
class PluginKnowledgeProviderTest {
    private val notes = LinkedHashMap<String, String>()
    private var modules = emptyList<LoadedModule>()
    private var sessions = emptyList<SessionInfo>()
    private val sent = ArrayList<Triple<String, String, String>>()
    private val timers = ArrayList<() -> Unit>()
    private var wakes = false
    private var live = true

    private fun session(id: String, role: String?, state: String = "IDLE", project: String? = "p1", neverRun: Boolean = false) =
        SessionInfo(id, project, role, null, false, 0, state, neverRun, null, 1)

    private fun module(id: String, version: String, vararg k: KnowledgeDeclaration, file: Pair<String, String>? = null): LoadedModule {
        val dir = java.nio.file.Files.createTempDirectory("pk2").toFile()
        file?.let { java.io.File(dir, it.first).also { f -> f.parentFile.mkdirs() }.writeText(it.second) }
        return LoadedModule(id, "official", ModuleManifest(name = id, version = version, knowledge = k.toList()), dir)
    }

    private fun decl(id: String, summary: String, tools: List<String> = emptyList(), roles: List<String> = emptyList(), file: String = "", projects: List<String> = emptyList()) =
        KnowledgeDeclaration(id, summary, file, tools, roles, emptyList(), projects)

    private val provider = PluginKnowledgeProvider(PluginKnowledgeProvider.Ports(
        saveNote = { n, d, b -> notes[n] = "$d\n$b" },
        deleteNote = { notes.remove(it) },
        noteNames = { notes.keys.toList() },
        loaded = { modules },
        readFile = { m, f -> m.dir?.let { java.io.File(it, f).takeIf { x -> x.isFile }?.readText() } },
        sessions = { sessions },
        send = { id, s, b -> sent += Triple(id, s, b); true },
        notifyLive = { live },
        noticeWakes = { wakes },
        coalesceMs = { 50 },
        schedule = { _, block -> timers += block },
    ))

    private fun fire() { val t = timers.toList(); timers.clear(); t.forEach { it() } }

    @Test
    fun aDeclaredEntryBecomesANoteAndABriefingSection() {
        modules = listOf(module("Acme_Cal", "1.0.0", decl("cal", "Calendar: add reminders.", listOf("acme_add"), file = "k/cal.md"), file = "k/cal.md" to "## Use\nCall acme_add."))
        provider.reconcile()
        val note = assertNotNull(notes["plugin:Acme_Cal/cal"])
        assertTrue("Call acme_add." in note && "acme_add" in note)
        val section = assertNotNull(provider.section("fullstack", "p1", BriefingBudget()))
        assertTrue("plugin:Acme_Cal/cal" in section)
        assertTrue(sent.isEmpty(), "the first reconcile (the start) tells no one")
    }

    @Test
    fun anUnloadedModulesNoteIsRemovedAndLiveSessionsAreToldOnce() {
        modules = listOf(module("Acme_Cal", "1.0.0", decl("cal", "Calendar.", listOf("acme_add"))))
        provider.reconcile()
        sessions = listOf(session("s1", "pm", "BUSY"))
        modules = emptyList()
        provider.reconcile(); provider.reconcile()
        assertNull(notes["plugin:Acme_Cal/cal"])
        fire()
        assertEquals(1, sent.size)
        assertTrue("removed; do not call: acme_add" in sent[0].third)
        assertNull(provider.section("pm", "p1", BriefingBudget()))
    }

    @Test
    fun anUpgradeRefreshesTheNoteAndSendsOneCoalescedNotice() {
        modules = listOf(module("Acme_Cal", "1.0.0", decl("cal", "Calendar v1.", listOf("a"))))
        provider.reconcile()
        sessions = listOf(session("s1", "pm", "BUSY"))
        modules = listOf(module("Acme_Cal", "1.1.0", decl("cal", "Calendar v2.", listOf("a")), decl("extra", "Extra tool.", listOf("b"))))
        provider.reconcile()
        assertTrue("Calendar v2." in notes.getValue("plugin:Acme_Cal/cal"))
        assertEquals(1, timers.size, "one timer for the burst")
        fire()
        assertEquals(1, sent.size, "one notice per session")
        val body = sent[0].third
        assertTrue("New tools available" in body && "infantry memory show plugin:Acme_Cal/extra" in body && "infantry tool <name>" in body)
    }

    @Test
    fun aNoticeGoesOnlyToSessionsInScope() {
        modules = emptyList(); provider.reconcile()
        sessions = listOf(session("pm", "pm", "BUSY"), session("qa", "qa", "BUSY"), session("other", "pm", "BUSY", project = "p2"), session("fresh", "pm", "BUSY", neverRun = true), session("dead", "pm", "FAILED"))
        modules = listOf(module("Acme_Cal", "1.0.0", decl("cal", "Calendar.", listOf("a"), roles = listOf("pm"), projects = listOf("p1"))))
        provider.reconcile(); fire()
        assertEquals(listOf("pm"), sent.map { it.first })
    }

    @Test
    fun anIdleSessionIsNotWokenButGetsTheNoticeWithItsNextTurn() {
        modules = emptyList(); provider.reconcile()
        sessions = listOf(session("s1", "pm", "IDLE"))
        modules = listOf(module("Acme_Cal", "1.0.0", decl("cal", "Calendar.", listOf("a"))))
        provider.reconcile(); fire()
        assertTrue(sent.isEmpty(), "no wake by default")
        provider.onTurnStarted("s1")
        assertEquals(1, sent.size)
        provider.onTurnStarted("s1")
        assertEquals(1, sent.size, "delivered once")
    }

    @Test
    fun theSettingCanWakeAnIdleSessionOrSilenceTheNotices() {
        modules = emptyList(); provider.reconcile()
        sessions = listOf(session("s1", "pm", "IDLE"))
        wakes = true
        modules = listOf(module("Acme_Cal", "1.0.0", decl("cal", "Calendar.", listOf("a"))))
        provider.reconcile(); fire()
        assertEquals(1, sent.size)
        live = false
        modules = emptyList(); provider.reconcile(); fire()
        assertEquals(1, sent.size, "notifyLive off: no notice")
        assertFalse(notes.containsKey("plugin:Acme_Cal/cal"), "the note still follows the module")
    }

    @Test
    fun aRunTimeContributionIsANoteAndClosingItRemovesIt() {
        modules = listOf(module("Acme_Srv", "1.0.0"))
        provider.reconcile()
        val h = provider.contribute("Acme_Srv", KnowledgeEntry("servers", "You can reach: a, b.", module = "ignored"))
        assertTrue(notes.containsKey("plugin:Acme_Srv/servers"))
        h.close()
        assertFalse(notes.containsKey("plugin:Acme_Srv/servers"))
    }

    @Test
    fun aStaleNoteFromAnEarlierRunIsPrunedAtStart() {
        notes["plugin:Gone_Mod/x"] = "old"
        notes["my-note"] = "keep"
        modules = emptyList()
        provider.reconcile()
        assertEquals(setOf("my-note"), notes.keys)
    }
}
