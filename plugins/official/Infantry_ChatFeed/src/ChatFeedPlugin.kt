// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.chatfeed

import com.superiorsoftllc.infantry.plugin.api.ConfigDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.infantry.plugin.api.ImplementationDeclaration
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.PanelDeclaration
import com.superiorsoftllc.infantry.ui.model.CARD_ITEMS_DEFAULT
import com.superiorsoftllc.infantry.ui.model.CARD_STATE_BYTES_DEFAULT
import com.superiorsoftllc.infantry.ui.model.CARD_UPDATES_DEFAULT
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.ModuleSource
import com.superiorsoftllc.infantry.ui.plugin.PanelProvider
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar
import kotlinx.serialization.json.JsonPrimitive

/** The slot Infantry_Chat draws its feed in; this module contributes the default section of it. */
const val FEED_SLOT = "chat.feed"

/** The id of this module's section of [FEED_SLOT]. */
const val FEED_SECTION_ID = "infantry.chatfeed"

private fun feedPanel() = PanelProvider(id = FEED_SECTION_ID, panel = FEED_SLOT, order = 0, testTag = "chat-feed", fill = true) { FeedSection(it) }

/** Infantry_ChatFeed as a compiled-in module (web and Android, D9): the manifest mirrors module.json. */
val InfantryChatFeed = InfantryModule(
    ModuleManifest(
        name = "Infantry_ChatFeed", version = "1.0.0", author = "Infantry AI",
        description = "The chat feed: the session's messages, thinking, comms cards, finished lines and automation rows, drawn by kind.",
        panels = listOf(PanelDeclaration(FEED_SECTION_ID, FEED_SLOT)),
        // QA-FIX-C (ModuleConfigDriftTest): ACTIVITY-BOX-1 and CHAT-SCROLL-1 declared these in module.json only.
        config = listOf(
            ConfigDeclaration(
                node = FEED_SECTION_ID, group = "Activity box", tab = "Chat", order = 10, title = "Activity box",
                applies = "Applies to: the chat view's tool activity box.", keywords = listOf("activity", "tools", "collapse"),
                fields = listOf(
                    ConfigFieldDeclaration(ACTIVITY_AUTO_OPEN_KEY, "checkbox", "Open the activity box while an agent is running", JsonPrimitive(false), order = 1, scope = "device"),
                ),
            ),
            ConfigDeclaration(
                node = FEED_SECTION_ID, group = "Long chats", tab = "Chat", order = 20, title = "Long chats",
                applies = "Applies to: the chat transcript. A chat opens on its newest rows; older rows load as you scroll up.",
                keywords = listOf("scroll", "page", "history", "long", "performance"),
                fields = listOf(
                    ConfigFieldDeclaration(
                        CHAT_PAGE_SIZE_KEY, "number", "Rows a chat opens with (and loads per scroll-up)", JsonPrimitive(80),
                        min = 20.0, max = 2000.0, step = 10.0, order = 1, scope = "device",
                    ),
                ),
            ),
            // CARD-UPDATE-1: addressed cards (id + op) fold per session; the bounds are named settings.
            ConfigDeclaration(
                node = FEED_SECTION_ID, group = "Chat cards", tab = "Chat", order = 30, title = "Chat cards",
                applies = "Applies to: rich cards agents post in the chat. A card with an id draws once with its latest state; later updates to it fold in.",
                keywords = listOf("card", "cards", "update", "append", "ticker", "graph"),
                fields = listOf(
                    ConfigFieldDeclaration(CARD_UPDATE_LINE_KEY, "checkbox", "Show a muted line where an agent updated a card", JsonPrimitive(true), order = 1, scope = "device"),
                    ConfigFieldDeclaration(CARD_UPDATES_KEY, "number", "Most updates folded into one card", JsonPrimitive(CARD_UPDATES_DEFAULT), min = 1.0, max = 10000.0, step = 1.0, order = 2, scope = "device"),
                    ConfigFieldDeclaration(CARD_ITEMS_KEY_SETTING, "number", "Most items one card keeps (the oldest drop)", JsonPrimitive(CARD_ITEMS_DEFAULT), min = 1.0, max = 100000.0, step = 1.0, order = 3, scope = "device"),
                    ConfigFieldDeclaration(CARD_STATE_BYTES_KEY, "number", "Largest card state (bytes)", JsonPrimitive(CARD_STATE_BYTES_DEFAULT), min = 1024.0, max = 4194304.0, step = 1024.0, order = 4, scope = "device"),
                ),
            ),
        ),
        implementations = DefaultFeedRenderers.keys.map { ImplementationDeclaration(FEED_RENDERERS, it) },
    ),
    panels = listOf(feedPanel()),
    implementations = mapOf(FEED_RENDERERS to DefaultFeedRenderers),
)

/**
 * The entry of Infantry_ChatFeed (module.json `entry`): the feed section in the chat's feed slot, and today's row renderers
 * under [FEED_RENDERERS], registered through the same public registrar a third-party plugin uses.
 *  - Desktop loads it at run time from its signed jar: the loader takes it as a [UiPlugin].
 *  - Web and Android compile it in from the generated module list, which takes it as a [ModuleSource].
 */
class ChatFeedPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) {
        registrar.panel(feedPanel())
        for ((kind, renderer) in DefaultFeedRenderers) registrar.implementation(FEED_RENDERERS, kind, renderer)
    }

    override fun modules(): List<InfantryModule> = listOf(InfantryChatFeed)
}
