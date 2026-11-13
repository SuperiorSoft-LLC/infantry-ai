// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.roles.daemon

import com.superiorsoftllc.infantry.daemon.api.plugins.LoadedModule
import com.superiorsoftllc.infantry.plugin.api.CardDeclaration
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.RoleDeclaration
import com.superiorsoftllc.infantry.protocol.RoleSave
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ROLE-PLUGIN-1: roles a module declares: seed if absent, never overwrite an owner edit, disabled while the module is gone. */
class PluginRolesTest {
    private val dir: File = Files.createTempDirectory("plugin-roles").toFile()
    private var loaded: Set<String>? = setOf("Acme_News")
    private val service = RoleService(File(dir, "roles")) { loaded }

    @AfterTest
    fun cleanup() { dir.deleteRecursively() }

    private val news = RoleDeclaration(id = "news-agent", name = "News Agent", persona = "Fetch headlines.", color = "#112233", cards = listOf("news"), tools = listOf("news.headlines"))

    private fun module(id: String, vararg roles: RoleDeclaration, cards: List<String> = emptyList(), folder: File? = null) =
        LoadedModule(id, "community", ModuleManifest(name = id, version = "1.0.0", roles = roles.toList(), cards = cards.map { CardDeclaration(it) }), folder)

    @Test
    fun seedsOnInstallAndMarksThePlugin() {
        val out = PluginRoles.sync(service, listOf(module("Acme_News", news)))
        assertEquals(listOf("news-agent"), out.written)
        val role = service.role("news-agent")!!
        assertEquals("News Agent", role.name)
        assertEquals("Acme_News", role.plugin)
        assertEquals("Fetch headlines.", role.persona)
        assertEquals(listOf("news"), role.cards)
        assertEquals(listOf("news.headlines"), role.tools)
        assertFalse(role.disabled)
        assertFalse(role.edited)
        assertTrue(PluginRoles.sync(service, listOf(module("Acme_News", news))).written.isEmpty(), "a second sync writes nothing")
    }

    @Test
    fun ownerEditSurvivesReinstallAndUpdate() {
        PluginRoles.sync(service, listOf(module("Acme_News", news)))
        service.save(RoleSave(id = "news-agent", name = "My News", persona = "My own notes."))
        val edited = service.role("news-agent")!!
        assertTrue(edited.edited)
        assertEquals("Acme_News", edited.plugin, "an edit keeps the plugin mark")
        assertEquals(listOf("news"), edited.cards, "null preferences keep the stored ones")
        // Uninstall, then reinstall a NEWER declaration: the owner's edit stays.
        loaded = emptySet()
        assertTrue(service.role("news-agent")!!.disabled)
        loaded = setOf("Acme_News")
        PluginRoles.sync(service, listOf(module("Acme_News", news.copy(persona = "Version 2 notes."))))
        val after = service.role("news-agent")!!
        assertEquals("My News", after.name)
        assertEquals("My own notes.", after.persona)
        assertFalse(after.disabled)
    }

    @Test
    fun anUneditedCopyFollowsPluginUpdates() {
        PluginRoles.sync(service, listOf(module("Acme_News", news)))
        val out = PluginRoles.sync(service, listOf(module("Acme_News", news.copy(persona = "Version 2 notes."))))
        assertEquals(listOf("news-agent"), out.written)
        assertEquals("Version 2 notes.", service.role("news-agent")!!.persona)
    }

    @Test
    fun uninstallLeavesTheRoleDisabledNotDeleted() {
        PluginRoles.sync(service, listOf(module("Acme_News", news)))
        loaded = setOf("Other")
        val role = service.role("news-agent")
        assertTrue(role != null && role.disabled)
        assertTrue(service.all().first { it.id == "news-agent" }.disabled)
        loaded = null
        assertFalse(service.role("news-agent")!!.disabled, "an unknown loaded set never disables")
    }

    @Test
    fun neverTakesAnOwnerOrBuiltInOrRemovedId() {
        service.save(RoleSave(id = "news-agent", name = "Owner's", persona = "Mine."))
        PluginRoles.sync(service, listOf(module("Acme_News", news, news.copy(id = "backend"))))
        assertEquals("Mine.", service.role("news-agent")!!.persona)
        assertEquals("", service.role("news-agent")!!.plugin)
        assertEquals("", service.role("backend")!!.plugin, "a built-in id is never taken by a plugin")

        // A plugin role the owner deletes is not seeded back.
        PluginRoles.sync(service, listOf(module("Acme_Two", news.copy(id = "two-agent"))))
        assertEquals("Acme_Two", service.role("two-agent")!!.plugin)
        assertNull(service.delete("two-agent", emptySet()))
        PluginRoles.sync(service, listOf(module("Acme_Two", news.copy(id = "two-agent"))))
        assertNull(service.role("two-agent"))
    }

    @Test
    fun badDeclarationIsReportedNotThrown() {
        val out = PluginRoles.sync(service, listOf(module("Acme_Bad", RoleDeclaration(id = "Bad Id"), news)))
        assertEquals(1, out.problems.size)
        assertEquals(listOf("news-agent"), out.written)
    }

    @Test
    fun personaFileIsReadOnlyInsideTheModuleFolder() {
        val folder = File(dir, "Acme_News").apply { mkdirs() }
        File(folder, "roles").mkdirs()
        File(folder, "roles/news.md").writeText("Persona from a file.")
        File(dir, "outside.md").writeText("secret")
        assertEquals("Persona from a file.", PluginRoles.persona("roles/news.md", folder))
        assertEquals("../outside.md", PluginRoles.persona("../outside.md", folder))
        assertEquals("plain text", PluginRoles.persona("plain text", folder))
    }

    @Test
    fun briefingNamesOnlyLoadedPreferences() {
        val role = Role(id = "hybrid", name = "Hybrid", color = "#000000", persona = "Be useful.", cards = listOf("news", "ticker"), tools = listOf("infantry_ticket_get", "gone_tool"))
        val text = PluginRoles.briefing(role, tools = setOf("infantry_ticket_get"), cards = setOf("news"))
        assertTrue(text.startsWith("Be useful.\n\n"))
        assertTrue("infantry_ticket_get" in text && "card:news" in text)
        assertFalse("gone_tool" in text || "ticker" in text, text)
        assertEquals("Be useful.", PluginRoles.briefing(role, emptySet(), emptySet()), "nothing loaded = the persona alone")
    }

    @Test
    fun offeredReadsCardsFromLoadedModules() {
        val (cards, _) = PluginRoles.offered(listOf(module("Acme_News", cards = listOf("news"))))
        assertEquals(setOf("news"), cards)
    }

    @Test
    fun hybridPreferencesRoundTripThroughTheFile() {
        service.save(RoleSave(id = "hybrid", name = "Hybrid", persona = "x", cards = listOf("news", "hello"), tools = listOf("infantry_ticket_get")))
        val back = RoleService(File(dir, "roles")).role("hybrid")!!
        assertEquals(listOf("news", "hello"), back.cards)
        assertEquals(listOf("infantry_ticket_get"), back.tools)
        service.save(RoleSave(id = "hybrid", name = "Hybrid", persona = "x", cards = emptyList()))
        assertEquals(emptyList(), service.role("hybrid")!!.cards, "an empty list clears")
        assertEquals(listOf("infantry_ticket_get"), service.role("hybrid")!!.tools, "null keeps")
    }
}
