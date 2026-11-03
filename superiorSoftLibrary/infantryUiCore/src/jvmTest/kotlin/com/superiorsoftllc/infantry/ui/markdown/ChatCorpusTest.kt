// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.markdown

import com.superiorsoftllc.infantry.ui.checkChatCorpusSample
import com.superiorsoftllc.infantry.ui.model.BuiltInChatMarkdownEngine
import com.superiorsoftllc.infantry.ui.model.ChatMarkdownRegistry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs every sample in src/commonTest/resources/chat-corpus through the built-in engine and the example plugin engine. */
class ChatCorpusTest {
    private fun samples(): List<File> {
        val dir = File(checkNotNull(javaClass.classLoader.getResource("chat-corpus")) { "chat-corpus resources missing" }.toURI())
        return dir.listFiles { f -> f.name.endsWith(".txt") && f.name != "README.txt" }!!.sortedBy { it.name }
    }

    @Test
    fun `every corpus sample renders cleanly with the built-in engine`() {
        val samples = samples()
        assertTrue(samples.size >= 9, "corpus is missing")
        val failures = samples.flatMap { f -> checkChatCorpusSample(f.readText(), BuiltInChatMarkdownEngine).map { "${f.name}: $it" } }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun `every corpus sample renders cleanly with the example plugin engine`() {
        val log = ArrayList<String>()
        val registry = ChatMarkdownRegistry()
        // A generous timeout: this test is about the output, not the 250 ms guard.
        MarkdownEnginePluginLoader(pluginDirWithExample(), registry, timeoutMs = 10_000, log = { log += it }).loadAll()
        val engine = registry.resolve("example-callout")
        assertEquals("example-callout", engine.id)
        val failures = samples().flatMap { f -> checkChatCorpusSample(f.readText(), engine).map { "${f.name}: $it" } }
        assertEquals(emptyList(), failures)
        assertEquals(emptyList(), log, "the plugin engine must never fail on a corpus sample (a failure would be hidden by the built-in fallback)")
    }
}
