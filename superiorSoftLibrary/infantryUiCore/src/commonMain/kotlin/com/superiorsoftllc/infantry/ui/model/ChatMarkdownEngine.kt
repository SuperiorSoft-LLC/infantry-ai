// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.model

/** What a [ChatMarkdownEngine] can produce; the UI may use it to hide controls or choose fallbacks. */
enum class ChatMarkdownFeature { HEADINGS, LISTS, QUOTES, RULES, CODE, TABLES, INLINE }

/**
 * CHAT-MD-ENGINE: the seam between assistant text and what the chat draws. The chat only ever calls this interface;
 * the built-in hand-written parser is just the default implementation. Pure Kotlin (no JVM or Compose types) so every
 * client, wasm included, can host an engine, e.g. one backed by a multiplatform markdown library.
 */
interface ChatMarkdownEngine {
    /** Stable key stored in the per-device setting. */
    val id: String

    /** Name shown in Configuration > Appearance. */
    val label: String

    /** Parse [text] into blocks; [streaming] is true while the reply is still arriving, so a half-received construct must degrade quietly. */
    fun parse(text: String, streaming: Boolean = false): List<ChatBlock>

    fun supports(feature: ChatMarkdownFeature): Boolean
}

/** The default engine: the hand-written parser in ChatMarkdown.kt. */
object BuiltInChatMarkdownEngine : ChatMarkdownEngine {
    const val ID = "builtin"
    override val id: String = ID
    override val label: String = "Built-in"
    override fun parse(text: String, streaming: Boolean): List<ChatBlock> = parseChatMarkdown(text, streaming)
    override fun supports(feature: ChatMarkdownFeature): Boolean = true
}

/** The key a renderer override is registered under: the block's type name; a [ChatBlock.Custom] uses its own `type`. */
fun chatBlockType(block: ChatBlock): String = when (block) {
    is ChatBlock.Paragraph -> "paragraph"
    is ChatBlock.Code -> "code"
    is ChatBlock.BulletList -> "list"
    is ChatBlock.Heading -> "heading"
    is ChatBlock.Quote -> "quote"
    is ChatBlock.Table -> "table"
    ChatBlock.Rule -> "rule"
    is ChatBlock.Custom -> block.type
}

/**
 * Run [engine]; an engine that fails must never take the chat down, so that message is shown with the built-in engine
 * instead. Anything it throws counts (a StackOverflowError from a plugin too) except OutOfMemoryError, which is
 * not recoverable here and propagates. CARD-1: a `card:<type>` fence in the result becomes a card block ([cardBlocks]).
 */
fun parseChatMarkdownSafely(engine: ChatMarkdownEngine, text: String, streaming: Boolean = false): List<ChatBlock> =
    cardBlocks(
        try {
            engine.parse(text, streaming)
        } catch (e: Throwable) {
            if (isUnrecoverableError(e)) throw e
            BuiltInChatMarkdownEngine.parse(text, streaming)
        },
        streaming,
    )

/**
 * The engines a user can pick between. The built-in engine is always present and is the fallback for an unknown or
 * stale id, so a removed plugin engine never leaves the chat without one.
 */
class ChatMarkdownRegistry {
    private val byId = LinkedHashMap<String, ChatMarkdownEngine>().apply { put(BuiltInChatMarkdownEngine.id, BuiltInChatMarkdownEngine) }

    /** Add (or replace, same id) an engine; the built-in id cannot be replaced. */
    fun register(engine: ChatMarkdownEngine) {
        if (engine.id != BuiltInChatMarkdownEngine.id) byId[engine.id] = engine
    }

    fun unregister(id: String) {
        if (id != BuiltInChatMarkdownEngine.id) byId.remove(id)
    }

    fun available(): List<ChatMarkdownEngine> = byId.values.toList()

    /** The engine for [id]; the built-in one when [id] is null or unknown. */
    fun resolve(id: String?): ChatMarkdownEngine = id?.let { byId[it] } ?: BuiltInChatMarkdownEngine
}

/** Where the chat finds its engines: one registry per process; plugins and the app register into it at startup. */
object ChatMarkdown {
    val engines = ChatMarkdownRegistry()
}

/** The per-device pref key of the chosen engine id. */
const val CHAT_MARKDOWN_PREF_KEY = "chatMarkdownEngine"

const val CHAT_MARKDOWN_TITLE = "Markdown engine"
const val CHAT_MARKDOWN_APPLIES_TO = "Applies to: how assistant replies are formatted in every chat on this device (headings, lists, code, tables). Remembered across launches."
