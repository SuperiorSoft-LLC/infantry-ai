// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.chat

import com.superiorsoftllc.infantry.plugin.api.EventBus
import com.superiorsoftllc.infantry.plugin.api.PluginEvent
import com.superiorsoftllc.infantry.ui.model.insertIntoPrompt

/**
 * CHAT-VOICE-SEAMS-1: the extension contract Infantry_Chat offers other plugins (module.json `slots`, `events`, `config`).
 * Core supplies only the generic slot and event mechanisms; the names, arguments and payload below are Chat's.
 *
 *  - [COMPOSER_ACTIONS]: small blocks in the composer's tools row, beside attach. A section reads [ComposerArgs] from
 *    `LocalSlotArgs`.
 *  - [MESSAGE_ACTIONS]: per-message actions (shown on hover on desktop/web, on long-press on Android). A section reads
 *    [MessageArgs] from `LocalSlotArgs`. The feed (the `chat.feed` child) draws it on each message row.
 *  - [MESSAGE_HEADER]: small icons in each chat card's header row, left of the timestamp, on every card kind. A section reads
 *    [MessageArgs] (plus `kind`) from `LocalSlotArgs`; each icon should carry a tooltip.
 *  - [COMPOSER_INSERT_EVENT]: a plugin publishes it to put text into a draft ([ComposerInsert]).
 */
object ChatSlots {
    const val COMPOSER_ACTIONS = "chat.composer.actions"
    const val MESSAGE_ACTIONS = "chat.message.actions"
    const val MESSAGE_HEADER = "chat.message.header"
    const val COMPOSER_INSERT_EVENT = "chat.composer.insert"
    /** The config node Chat's own settings sit on (the centre column it fills). */
    const val CONFIG_NODE = CHAT_FILL_ID
    /** Off (the default): a [ComposerInsert] with `send = true` only fills the draft; the person reviews and sends it. */
    const val ALLOW_PLUGIN_SEND_KEY = "chat.allowPluginSend"
}

/** The [ChatSlots.COMPOSER_ACTIONS] arguments: the session (null in New agent mode), the draft text, whether a send is in flight. */
object ComposerArgs {
    const val SESSION_ID = "sessionId"
    const val DRAFT = "draft"
    const val SENDING = "sending"
}

/**
 * The [ChatSlots.MESSAGE_ACTIONS] arguments. [MESSAGE_ID] is the row's id in its session's transcript (stable while the
 * session lives); [AUTHOR] is `owner`, `agent`, `external` or `system`; [AUTHOR_ROLE] / [AUTHOR_AGENT] name the agent when
 * known; [TEXT] is the message as plain text (markdown stripped).
 */
object MessageArgs {
    const val SESSION_ID = "sessionId"
    const val MESSAGE_ID = "messageId"
    const val AUTHOR = "author"
    const val AUTHOR_ROLE = "authorRole"
    const val AUTHOR_AGENT = "authorAgent"
    const val TEXT = "text"
}

/**
 * One [ChatSlots.COMPOSER_INSERT_EVENT]: put [text] into the draft of [sessionId] (null = the focused composer, else the
 * selected session's). [replace] swaps the draft; otherwise the text is appended on a new line. [send] asks Chat to send
 * the draft right after; it is honoured only while [ChatSlots.ALLOW_PLUGIN_SEND_KEY] is on.
 *
 * Wire form ([PluginEvent.data], strings): `sessionId` (optional), `text`, `mode` (`append` | `replace`, default append),
 * `send` (`true` | `false`, default false).
 */
data class ComposerInsert(val sessionId: String?, val text: String, val replace: Boolean = false, val send: Boolean = false) {
    /** The draft after this insert. */
    fun applyTo(draft: String): String = if (replace) text else insertIntoPrompt(draft, text)

    fun toEvent(): PluginEvent = PluginEvent(
        ChatSlots.COMPOSER_INSERT_EVENT,
        buildMap {
            sessionId?.let { put("sessionId", it) }
            put("text", text)
            put("mode", if (replace) "replace" else "append")
            put("send", send.toString())
        },
    )

    companion object {
        /** The insert [event] carries, or null when it is another event or has no text. An unknown mode appends. */
        fun of(event: PluginEvent): ComposerInsert? {
            if (event.name != ChatSlots.COMPOSER_INSERT_EVENT) return null
            val text = event.data["text"] ?: return null
            return ComposerInsert(
                sessionId = event.data["sessionId"]?.takeIf { it.isNotBlank() },
                text = text,
                replace = event.data["mode"]?.trim()?.lowercase() == "replace",
                send = event.data["send"]?.trim()?.lowercase() == "true",
            )
        }
    }
}

/**
 * Routes each [ChatSlots.COMPOSER_INSERT_EVENT] on one window bus to exactly ONE open composer: the one showing the
 * event's session; with no session named, the focused composer, else the one showing the selected session, else the only one.
 * One router per bus (several [ChatPanel]s share it: the main window and the Assistant popup), subscribed while any
 * composer is open.
 */
internal class ComposerInsertRouter private constructor(private val bus: EventBus) {
    class Target(val sessionId: () -> String?, val focused: () -> Boolean, val selected: () -> String?, val apply: (ComposerInsert) -> Unit)

    private val targets = mutableListOf<Target>()
    private var unsubscribe: (() -> Unit)? = null

    fun add(t: Target): () -> Unit {
        targets += t
        if (unsubscribe == null) unsubscribe = bus.subscribe(ChatSlots.COMPOSER_INSERT_EVENT) { e -> ComposerInsert.of(e)?.let(::dispatch) }
        return {
            targets -= t
            if (targets.isEmpty()) { unsubscribe?.invoke(); unsubscribe = null; routers.remove(bus) }
        }
    }

    private fun dispatch(insert: ComposerInsert) {
        route(insert, targets.toList())?.apply?.invoke(insert)
    }

    companion object {
        private val routers = mutableMapOf<EventBus, ComposerInsertRouter>()

        fun of(bus: EventBus): ComposerInsertRouter = routers.getOrPut(bus) { ComposerInsertRouter(bus) }

        /** The one composer [insert] goes to among [targets] (see the class comment), or null: no open composer shows it. */
        fun route(insert: ComposerInsert, targets: List<Target>): Target? {
            insert.sessionId?.let { id -> return targets.firstOrNull { it.sessionId() == id } }
            return targets.firstOrNull { it.focused() }
                ?: targets.firstOrNull { t -> t.sessionId().let { it != null && it == t.selected() } }
                ?: targets.singleOrNull()
        }
    }
}
