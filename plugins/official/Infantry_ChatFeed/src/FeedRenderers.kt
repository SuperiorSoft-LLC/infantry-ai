package com.superiorsoftllc.agenthq.plugins.official.chatfeed

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.superiorsoftllc.agenthq.ui.LocalTabFactory
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.Mono
import com.superiorsoftllc.agenthq.ui.Palette
import com.superiorsoftllc.agenthq.ui.LocalChatMarkdown
import com.superiorsoftllc.agenthq.ui.ChatImageLines
import com.superiorsoftllc.agenthq.ui.model.ChatItem
import com.superiorsoftllc.agenthq.ui.model.LineKind
import com.superiorsoftllc.agenthq.ui.model.OutputLine
import com.superiorsoftllc.agenthq.ui.model.chatLineBlocks
import com.superiorsoftllc.agenthq.ui.plugin.RowItem
import com.superiorsoftllc.agenthq.ui.plugin.RowRenderer
import com.superiorsoftllc.agenthq.ui.plugin.TabFactory
import com.superiorsoftllc.agenthq.ui.shell.LocalPlatformHost

/**
 * KINDS-1: the registry the feed draws its rows from, keyed by kind: `UiRegistrar.implementation(FEED_RENDERERS, kind, RowRenderer)`,
 * declared under `implementations` in the plugin's module.json. Infantry_ChatFeed registers today's rows here itself; any plugin
 * adds the renderer of a kind of its own the same way.
 */
const val FEED_RENDERERS = "chat.feed.renderers"

/** The renderer of [kind] from the loaded modules, or the generic row (its text) when nobody registered one. */
internal fun feedRendererOf(factory: TabFactory, kind: String): RowRenderer =
    factory.implementations.get(FEED_RENDERERS, kind) as? RowRenderer ?: GenericRow

/** Whether the row [item] sits on the agent's side of the feed, by the renderer of its kind. */
internal fun agentSideOf(factory: TabFactory): (ChatItem) -> Boolean = { feedRendererOf(factory, it.kind).agentSide }

/** The row of a kind with no renderer: its text, never a crash. */
internal val GenericRow: RowRenderer = RowRenderer(agentSide = false) { row ->
    val text = when (val i = row.item) {
        is ChatItem.Line -> i.line.text
        is ChatItem.Reasoning -> i.lines.joinToString("\n") { it.text }
        is ChatItem.Contribution -> i.item.text.ifBlank { i.item.label }
    }
    Text(text, color = Palette.Muted, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).testTag(GENERIC_ROW_TAG))
}

/** Test tag on the generic row. */
const val GENERIC_ROW_TAG = "feed-generic-row"

private fun RowItem.line(): OutputLine = when (val i = item) {
    is ChatItem.Line -> i.line
    is ChatItem.Reasoning -> i.lines.first()
    is ChatItem.Contribution -> OutputLine(LineKind.TEXT, i.item.text, i.item.at)
}

/** The human's own echo: their disc, the origin label and a card (IMG-2: `[image: ...]` lines show as thumbnails). */
internal val UserRow: RowRenderer = RowRenderer(agentSide = false) { row ->
    val line = row.line()
    val (said, images) = ChatImageLines.split(line.text)
    SpeakerRow({ OriginAvatar(line.origin, CHAT_AVATAR_SIZE) }) {
        Column {
            OriginLabel(line.origin)
            CommsCard(commsCardStyle(line.origin), line.at) {
                if (said.isNotBlank() || images.isEmpty()) Text(said, color = Palette.Accent, fontFamily = Mono, fontSize = 12.sp)
                ChatSentImages(images)
            }
        }
    }
}

/** The agent's markdown reply in a comms card. */
internal val TextRow: RowRenderer = RowRenderer(agentSide = true) { row ->
    val line = row.line()
    CommsCard(AgentReplyCardStyle, line.at) {
        val md = LocalChatMarkdown.current
        val host = LocalPlatformHost.current
        val (shown, images) = if ("![" in line.text) ChatMarkdownImages.split(line.text) { host.fileExists(it) == true } else line.text to emptyList()
        if (shown.isNotBlank() || images.isEmpty()) ChatBlocks(chatLineBlocks(line.kind, shown, md.engine).orEmpty(), md.renderers)
        ChatSentImages(images)
    }
}

/** The agent's thinking: a run of reasoning lines (or one alone). */
internal val ReasoningRow: RowRenderer = RowRenderer(agentSide = true) { row ->
    ReasoningBlock(when (val i = row.item) { is ChatItem.Reasoning -> i.lines; else -> listOf(row.line()) })
}

internal val StatusRow: RowRenderer = RowRenderer(agentSide = true) { row -> Text("· ${row.line().text}", color = Palette.Muted, fontFamily = Mono, fontSize = 11.sp) }
internal val ErrorRow: RowRenderer = RowRenderer(agentSide = true) { row -> Text("! ${row.line().text}", color = Palette.Danger, fontFamily = Mono, fontSize = 12.sp) }
internal val FinishedRow: RowRenderer = RowRenderer(agentSide = true) { row -> Text("— ${row.line().text}", color = Palette.Info, fontFamily = Mono, fontSize = 11.sp, modifier = Modifier.padding(vertical = 4.dp)) }
/** UI-LOG-1: stderr and process-exit log lines -- muted monospace. */
internal val LogRow: RowRenderer = RowRenderer(agentSide = true) { row -> Text(row.line().text, color = Palette.Muted, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp)) }
/** UX-WRITE-1: a file write is a change box, inline in the turn where it happened. */
internal val WriteRow: RowRenderer = RowRenderer(agentSide = true) { row -> ChangeBoxLine(row.line().text) }

/** SCRIPT-VIS-1b: a line a plugin put in the chat without a turn (an automation run). */
internal val AutomationRow: RowRenderer = RowRenderer(agentSide = false) { row -> ContributionRow((row.item as ChatItem.Contribution).item) }

/** Today's rows, by kind: what this plugin registers under [FEED_RENDERERS] (and declares in module.json `implementations`). */
internal val DefaultFeedRenderers: Map<String, RowRenderer> = linkedMapOf(
    "user" to UserRow, "text" to TextRow, "reasoning" to ReasoningRow, "status" to StatusRow, "error" to ErrorRow,
    "finished" to FinishedRow, "log" to LogRow, "write" to WriteRow, "automation" to AutomationRow,
)
