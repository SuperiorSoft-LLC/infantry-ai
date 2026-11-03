package com.superiorsoftllc.infantry.plugins.official.chatfeed

import com.superiorsoftllc.infantry.plugin.api.ImplementationDeclaration
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.PanelDeclaration
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.ModuleSource
import com.superiorsoftllc.infantry.ui.plugin.PanelProvider
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar

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
