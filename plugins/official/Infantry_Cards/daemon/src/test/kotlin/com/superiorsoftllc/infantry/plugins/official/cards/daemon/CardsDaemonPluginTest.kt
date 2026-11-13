// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.cards.daemon

import com.superiorsoftllc.infantry.daemon.api.plugins.LoadedModule
import com.superiorsoftllc.infantry.plugin.api.CardDeclaration
import com.superiorsoftllc.infantry.plugin.api.CardImages
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CardsDaemonPluginTest {
    private fun module(id: String, vararg cards: CardDeclaration, dir: File? = null) =
        LoadedModule(id, "community", ModuleManifest(name = id, version = "1.0.0", cards = cards.toList()), dir)

    @Test
    fun manifestCardsParseFromModuleJson() {
        val m = Json { ignoreUnknownKeys = true }.decodeFromString(ModuleManifest.serializer(), """
            {"name":"Acme_X","version":"1.0.0","cards":[{"type":"news","summary":"Headlines","spec":"cards/news.md","images":{"hosts":["*.gstatic.com"]}}]}
        """.trimIndent())
        assertEquals(listOf(CardDeclaration("news", "Headlines", "cards/news.md", CardImages(listOf("*.gstatic.com")))), m.cards)
    }

    @Test
    fun listIsOneLinePerTypeFirstDeclarationWinsAndBadTypesAreSkipped() {
        val mods = listOf(
            module("Acme_A", CardDeclaration("hello", "Hello card"), CardDeclaration("Bad Type", "x")),
            module("Acme_B", CardDeclaration("hello", "dupe"), CardDeclaration("news", "Headlines")),
        )
        val cards = CardsDaemonPlugin.list(mods)["cards"] as JsonArray
        assertEquals(listOf("hello:Acme_A:Hello card", "news:Acme_B:Headlines"), cards.map { c ->
            val o = c.jsonObject
            listOf("type", "module", "summary").joinToString(":") { o[it]!!.jsonPrimitive.content }
        })
    }

    @Test
    fun showGivesInlineSpecAndFenceAndUnknownTypeNamesTheInstalledOnes() {
        val mods = listOf(module("Acme_A", CardDeclaration("hello", "Hello card", "Body: {\"title\":\"...\",\"text\":\"...\"}")))
        val shown = CardsDaemonPlugin.show(mods, "card:hello", 1000)
        assertEquals("```card:hello", shown["fence"]!!.jsonPrimitive.content)
        assertEquals("Body: {\"title\":\"...\",\"text\":\"...\"}", shown["spec"]!!.jsonPrimitive.content)
        val e = assertFailsWith<IllegalArgumentException> { CardsDaemonPlugin.show(mods, "news", 1000) }
        assertTrue("installed: hello" in e.message.orEmpty(), e.message)
        assertFailsWith<IllegalArgumentException> { CardsDaemonPlugin.show(mods, " ", 1000) }
    }

    @Test
    fun specFileIsReadFromTheModuleFolderCappedAndNeverOutsideIt() {
        val dir = Files.createTempDirectory("cards").toFile()
        try {
            File(dir, "cards").mkdirs()
            File(dir, "cards/news.md").writeText("News spec ".repeat(10))
            File(dir.parentFile, "outside-${dir.name}.md").apply { writeText("secret") }.deleteOnExit()
            val mods = listOf(module("Acme_N", CardDeclaration("news", "Headlines", "cards/news.md"), CardDeclaration("evil", "Evil", "../outside-${dir.name}.md"), dir = dir))
            assertEquals("News spec ".repeat(10), CardsDaemonPlugin.show(mods, "news", 1000)["spec"]!!.jsonPrimitive.content)
            assertTrue(CardsDaemonPlugin.show(mods, "news", 20)["spec"]!!.jsonPrimitive.content.endsWith("(spec cut at 20 bytes)"))
            val evil = CardsDaemonPlugin.show(mods, "evil", 1000)["spec"]!!.jsonPrimitive.content
            assertTrue("secret" !in evil && "is not in Acme_N's folder" in evil, evil)
        } finally {
            dir.deleteRecursively()
        }
    }
}
