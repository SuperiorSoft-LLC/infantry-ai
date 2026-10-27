package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.SessionPaneAction
import com.superiorsoftllc.agenthq.ui.model.sessionPaneAction
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.superiorsoftllc.agenthq.ui.net.ImageUploadOutcome
import com.superiorsoftllc.agenthq.ui.protocol.ImageUploadWire
import com.superiorsoftllc.agenthq.ui.shell.PickedImage
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import com.superiorsoftllc.agenthq.ui.model.isSlashCommand
import com.superiorsoftllc.agenthq.ui.model.HarnessSwitch
import com.superiorsoftllc.agenthq.ui.model.AgentView
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.isBusy
import com.superiorsoftllc.agenthq.ui.model.insertIntoPrompt
import com.superiorsoftllc.agenthq.ui.net.CommandResult
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.protocol.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// S4c: the chat's own state stays in core (the window owns one, Shell.chat; the Assistant one per session); the panel that draws it is app UI.
/**
 * CHAT-PANEL-1: everything one chat owns that is NOT the daemon's data: the draft, the images/files tagged into it, the last
 * upload refusal, the New-agent mode, the open Switch agent picker and the composer's focus requester. The host decides
 * the lifetime: the main window keeps one for the whole window (its draft follows the selection, as it always did), a
 * popup or a future popout/split pane keeps one per session (see [rememberChatPanelState]). Two panels never share one.
 */
class ChatPanelState {
    var draft by mutableStateOf("")
    var attachments by mutableStateOf(listOf<ChatAttachment>())
        private set
    var attachError by mutableStateOf<String?>(null)
    var spawnMode by mutableStateOf(false)
    var switchAsk by mutableStateOf<SwitchAgentAsk?>(null)
    val focus: FocusRequester = FocusRequester()

    val attachBusy: Boolean get() = attachments.any { it.path == null }

    fun openSwitch(sessionId: String, harness: String? = null) {
        spawnMode = false
        switchAsk = SwitchAgentAsk(sessionId, harness, (switchAsk?.seq ?: 0) + 1)
    }

    /** ATTACH-INLINE-1: the composer's text changed; a tag deleted from it releases its attachment. */
    fun edit(text: String) {
        draft = text
        attachments = ComposerTags.prune(text, attachments)
    }

    /** Text inserted from outside (a saved prompt, a skill) and the box focused. */
    fun insert(text: String) {
        draft = insertIntoPrompt(draft, text)
        focus.requestFocus()
    }

    fun removeAttachment(path: String) {
        attachments.firstOrNull { it.path == path }?.let { a -> edit(ComposerTags.strip(draft, a.token)) }
    }

    fun wire(text: String): String = ComposerTags.toPrompt(text, attachments)

    /**
     * IMG-2 / ATTACH-INLINE-1: give each picked file a tag NOW (the box inserts the returned tokens at its caret), then
     * upload it into the project the message goes to: an image as purpose `chat`, any other file as purpose `file`.
     * Over the limit (images 10 MB, other files 20 MB) or refused by the daemon: an inline error, and no tag.
     */
    fun attach(picked: List<PickedImage>, controller: Controller, scope: CoroutineScope, agent: AgentView?, state: AppState): String {
        if (picked.isEmpty()) return ""
        val projectId = (if (spawnMode) null else agent?.session?.projectId?.takeIf { it.isNotBlank() }) ?: state.activeProjectId
        attachError = null
        val tagged = mutableListOf<Pair<ChatAttachment, PickedImage>>()
        for (file in picked) {
            val limit = if (file.isImage) ImageUploadWire.MAX_BYTES else CHAT_FILE_MAX_BYTES
            if (file.size > limit) { attachError = "${file.fileName}: the file is over the ${limit / (1024 * 1024)} MB limit"; continue }
            val token = ComposerTags.nextToken(draft + tagged.joinToString("") { it.first.token.toString() }, attachments)
                ?: run { attachError = "${file.fileName}: too many attachments in one message"; null } ?: continue
            val a = ChatAttachment(token, file.fileName, file.isImage, file.bytes)
            attachments = attachments + a
            tagged += a to file
        }
        if (tagged.isNotEmpty()) scope.launch {
            for ((a, file) in tagged) {
                val r = if (file.isImage) controller.uploadChatImage(projectId, file.fileName, file.mimeType, file.bytes)
                else controller.uploadChatFile(projectId, file.fileName, file.mimeType, file.bytes)
                when (r) {
                    // A tag deleted while it uploaded stays released: its file is simply never referenced.
                    is ImageUploadOutcome.Stored -> attachments = attachments.map { if (it.token == a.token) it.stored(r.image.path) else it }
                    is ImageUploadOutcome.Refused -> {
                        attachError = "${file.fileName}: ${r.reason}"
                        edit(ComposerTags.strip(draft, a.token))
                    }
                }
            }
        }
        return tagged.joinToString("") { it.first.token.toString() }
    }

    fun submit(controller: Controller, scope: CoroutineScope, agent: AgentView?) {
        val typed = draft.trim()
        // ATTACH-INLINE-1: each tag becomes its `[image: ...]` / `[file: ...]` reference where it stands; a `/command` line
        // never carries them (it is not a prompt).
        val text = if (isSlashCommand(ComposerTags.strip(typed))) ComposerTags.strip(typed).trim() else ComposerTags.toPrompt(typed, attachments)
        scope.launch {
            val harness = if (spawnMode || agent == null) null else HarnessSwitch.parse(text)
            val r: CommandResult = when {
                // CMD-HARNESS-1: a client command, never a prompt: it swaps the agent's harness.
                harness != null -> controller.switchHarness(agent!!.id, harness)
                spawnMode || agent == null -> controller.spawn(text)
                // GAP-006b: typing first into a ticket worker that ended before its brief landed starts THAT ticket's
                // configured worker, then sends the message to it; never a blank instance, never a refusal.
                !isBusy(agent) && agent.session.providerSessionId == null && agent.session.state != SessionState.IDLE ->
                    (sessionPaneAction(agent.session, controller.state.value.tickets) as? SessionPaneAction.Start)
                        ?.let { controller.startSession(it.plan, text) } ?: controller.prompt(agent.id, text)
                agent.session.resumable && agent.session.state in ENDED_STATES -> controller.resume(agent.id, text)
                isBusy(agent) -> controller.queueMessage(agent.id, text)
                else -> controller.prompt(agent.id, text)
            }
            if (r is CommandResult.Sent || r is CommandResult.Queued) {
                edit("")
                attachError = null
                // A `/model` or `/effort` in the New agent box only sets the next spawn's selection: stay in the spawn box.
                if (!(spawnMode && isSlashCommand(text))) spawnMode = false
            }
        }
    }

    /** Ctrl+R: resume the ended session with the draft as its prompt. */
    fun resume(controller: Controller, scope: CoroutineScope, agent: AgentView) {
        scope.launch {
            if (controller.resume(agent.id, ComposerTags.toPrompt(draft, attachments).trim()) is CommandResult.Sent) edit("")
        }
    }
}

/** A [ChatPanelState] owned by the calling composition and keyed by [sessionId]: another session gets a fresh one. */
@Composable
fun rememberChatPanelState(sessionId: String?): ChatPanelState = remember(sessionId) { ChatPanelState() }

