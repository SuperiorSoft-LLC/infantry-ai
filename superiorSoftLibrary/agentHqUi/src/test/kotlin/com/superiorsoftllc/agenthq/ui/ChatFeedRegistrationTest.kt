package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugins.official.chatfeed.ChatFeedPlugin
import com.superiorsoftllc.agenthq.plugins.official.chatfeed.InfantryChatFeed
import com.superiorsoftllc.agenthq.ui.plugin.ModuleSource
import kotlinx.serialization.json.Json
import java.io.File
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CHAT-FEED-SPLIT-1 (c): the feed is registered on every path. The compiled-in list (web, Android) is generated from
 * plugins/official/<name>/module.json `entry`; this pins that entry to the real ModuleSource, which yields the module, whose
 * manifest equals the file, and that the desktop service-loader path finds it too.
 */
class ChatFeedRegistrationTest {
    private val file: ModuleManifest = Json { ignoreUnknownKeys = true }.decodeFromString(
        ModuleManifest.serializer(), generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "plugins/official/Infantry_ChatFeed/module.json") }.first { it.isFile }.readText(),
    )

    @Test fun `module json's entry is the compiled-in source and yields the feed module`() {
        val entry = Class.forName(checkNotNull(file.entry)).getDeclaredConstructor().newInstance() as ModuleSource
        assertTrue(entry is ChatFeedPlugin)
        assertEquals(listOf(InfantryChatFeed), entry.modules())
    }

    @Test fun `the compiled manifest equals module json`() {
        assertEquals(file.panels, InfantryChatFeed.manifest.panels)
        assertEquals(file.implementations, InfantryChatFeed.manifest.implementations)
        assertEquals(file.name, InfantryChatFeed.manifest.name)
    }

    @Test fun `the desktop service loader finds it`() {
        assertTrue(ServiceLoader.load(ModuleSource::class.java).any { it is ChatFeedPlugin })
    }
}
