package com.superiorsoftllc.agenthq.ui.net

import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.CompactScreen
import com.superiorsoftllc.agenthq.ui.model.PanelSide
import com.superiorsoftllc.agenthq.ui.model.HarnessPickerAsk
import com.superiorsoftllc.agenthq.ui.model.CliLocationsSetting
import com.superiorsoftllc.agenthq.ui.model.ToolApprovalSetting
import com.superiorsoftllc.agenthq.ui.model.LifecycleSetting
import com.superiorsoftllc.agenthq.ui.model.UserPortrait
import kotlinx.serialization.json.JsonPrimitive
import com.superiorsoftllc.agenthq.ui.model.withChangesBranch
import com.superiorsoftllc.agenthq.ui.model.withChangesView
import com.superiorsoftllc.agenthq.ui.model.withChangesWorktree
import com.superiorsoftllc.agenthq.ui.model.followWorkerBranch
import com.superiorsoftllc.agenthq.ui.model.QueueSend
import com.superiorsoftllc.agenthq.ui.model.QueuedPrompt
import com.superiorsoftllc.agenthq.ui.model.interruptBlocker
import com.superiorsoftllc.agenthq.ui.model.isSlashCommand
import com.superiorsoftllc.agenthq.ui.model.SpawnSlash
import com.superiorsoftllc.agenthq.ui.model.spawnSlash
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.superiorsoftllc.agenthq.ui.model.isBusy
import com.superiorsoftllc.agenthq.ui.model.SWITCH_WAIT_FOR_TURN
import com.superiorsoftllc.agenthq.ui.model.queuedFor
import com.superiorsoftllc.agenthq.ui.model.withQueue
import com.superiorsoftllc.agenthq.ui.model.contractAtLeast
import com.superiorsoftllc.agenthq.ui.model.withEventsAwaiting
import com.superiorsoftllc.agenthq.ui.model.ConnState
import com.superiorsoftllc.agenthq.ui.model.HistoryRequest
import com.superiorsoftllc.agenthq.ui.model.historyRequest
import com.superiorsoftllc.agenthq.ui.model.promptBlocker
import com.superiorsoftllc.agenthq.ui.model.reduce
import com.superiorsoftllc.agenthq.ui.model.selectProject
import com.superiorsoftllc.agenthq.ui.model.resyncRequests
import com.superiorsoftllc.agenthq.ui.model.NoteScope
import com.superiorsoftllc.agenthq.ui.model.withMemoryList
import com.superiorsoftllc.agenthq.ui.model.withPromptsRequested
import com.superiorsoftllc.agenthq.ui.model.withSkillsRequested
import com.superiorsoftllc.agenthq.ui.model.withTraitsRequested
import com.superiorsoftllc.agenthq.ui.model.TraitDraft
import com.superiorsoftllc.agenthq.ui.model.parsePromptTags
import com.superiorsoftllc.agenthq.ui.model.withMemorySearch
import com.superiorsoftllc.agenthq.ui.model.withNotice
import com.superiorsoftllc.agenthq.ui.model.withPendingSpawn
import com.superiorsoftllc.agenthq.ui.model.withTicketList
import com.superiorsoftllc.agenthq.ui.model.withUserLine
import com.superiorsoftllc.agenthq.ui.protocol.AgentProfile
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import com.superiorsoftllc.agenthq.ui.protocol.ImageStored
import com.superiorsoftllc.agenthq.ui.protocol.ImageUploadWire
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64
import com.superiorsoftllc.agenthq.ui.model.PickedModel
import com.superiorsoftllc.agenthq.ui.model.NoticeKind
import com.superiorsoftllc.agenthq.ui.model.HarnessSwitch
import com.superiorsoftllc.agenthq.ui.model.spawnOptionsData
import com.superiorsoftllc.agenthq.ui.protocol.NodeSpec
import com.superiorsoftllc.agenthq.ui.protocol.PodSubmitRequest
import com.superiorsoftllc.agenthq.ui.protocol.ReplayRequest
import com.superiorsoftllc.agenthq.ui.protocol.RoleBindingSave
import com.superiorsoftllc.agenthq.ui.protocol.RunInFlight
import com.superiorsoftllc.agenthq.ui.protocol.RoleSave
import com.superiorsoftllc.agenthq.ui.protocol.SessionRecord
import com.superiorsoftllc.agenthq.ui.protocol.SessionState
import com.superiorsoftllc.agenthq.ui.protocol.SwapExecutor
import com.superiorsoftllc.agenthq.ui.protocol.SwapSessionMode
import com.superiorsoftllc.agenthq.ui.protocol.WireRequests
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Clock

/** Shown (and returned as a refusal) when the connected daemon predates the project git frames (contract 1.36.0). */
const val PROJECT_GIT_UNSUPPORTED = "This daemon does not support the project git view (needs contract 1.36.0)."
const val PIPELINE_UNSUPPORTED = "This daemon does not support the pipeline view (needs contract 1.99.0)."
const val PROJECT_GIT_BRANCHES_UNSUPPORTED = "This daemon does not support the branch view (needs contract 1.65.0)."
const val PROJECT_GIT_CHECKOUT_UNSUPPORTED = "This daemon cannot switch branches (needs contract 1.74.0)."
const val PROJECT_GIT_WORKTREES_UNSUPPORTED = "This daemon cannot list worktrees (needs contract 1.88.0)."
const val PROJECT_BASE_UNSUPPORTED = "This daemon cannot set a base branch for new tasks (needs contract 1.81.0)."

/** Why a command was not sent. Always shown to the human: a silent no-op is the worst failure here. */
sealed interface CommandResult {
    data object Sent : CommandResult
    data class Refused(val reason: String) : CommandResult

    /** A message held for a busy agent: [position] is its place in that agent's queue (1 = next to send). */
    data class Queued(val position: Int) : CommandResult
}

/** IMG-4: what one `image_upload` came to: where the daemon [Stored] the image, or why it [Refused] it. */
sealed interface ImageUploadOutcome {
    data class Stored(val image: ImageStored) : ImageUploadOutcome
    data class Refused(val reason: String) : ImageUploadOutcome
}

/**
 * Owns the [AppState] and turns human intent into protocol frames.
 *
 * It names no provider: it speaks `prompt` / `new` / `resume` / `kill` / `pod_*` / `replay_request` and
 * nothing else. A follow-up message names the session it continues (the `prompt` frame's `sessionId`),
 * never "whatever this socket was last talking to", because the daemon's per-connection "active session"
 * is lost on reconnect and continuing the wrong agent is the easy mistake.
 *
 * Talking to an agent (contract 1.11.0): opening an agent asks the daemon for that session's retained
 * stream (`replay_request`), so the conversation so far -- both sides, via `turn` -- is on screen; a
 * reconnect to the same daemon process asks again after each cursor to fill what streamed meanwhile.
 */
class Controller(
    private val scope: CoroutineScope,
    val client: DaemonClient = DaemonClient(scope),
    /** FLAKE-10: how long [addAgent] waits for the daemon to confirm `agent_save` before refusing. Tests on a loaded machine pass a longer budget. */
    private val agentConfirmMs: Long = 10_000,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _spoken = kotlinx.coroutines.flow.MutableSharedFlow<com.superiorsoftllc.agenthq.ui.voice.SpokenNotice>(extraBufferCapacity = 32)

    /** VOICE-1 (contract 1.104.0): every `spoken` push, in order. The shell decides what to say and show. */
    val spoken: kotlinx.coroutines.flow.SharedFlow<com.superiorsoftllc.agenthq.ui.voice.SpokenNotice> = _spoken

    init {
        // BUG-FLAKE-4: UNDISPATCHED, so the link's CURRENT info is applied here, in the constructor, before the frame
        // collector below can reduce anything. Dispatched, the two raced: under load `ready` could be reduced first
        // and this collector's initial value (IDLE on a fresh link) then overwrote the CONNECTED link the daemon had
        // just confirmed -- isLive false for good, with no later info emission to repair it.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.info.collect { info ->
                _state.update { s ->
                    val cur = s.connection
                    // The client never reports CONNECTED (only `ready` does, via the reducer), so a late
                    // CONNECTING emission must not downgrade a link the daemon already confirmed.
                    if (cur.isLive && info.state == ConnState.CONNECTING && cur.url == info.url) s
                    else s.copy(
                        connection = info.copy(
                            lastFrameAt = cur.lastFrameAt,
                            contract = cur.contract,
                            daemonProvider = cur.daemonProvider,
                            // Kept across a drop: the next `ready` compares against it to tell a reconnect
                            // from a restarted daemon (contract 1.11.0).
                            instance = cur.instance,
                            historySince = cur.historySince,
                            // BUILD-STAMP-1: set by `ready`, like the contract; a link-state change keeps it.
                            daemonBuild = cur.daemonBuild,
                        ),
                    )
                }
            }
        }
        scope.launch {
            // ONE coroutine sees every frame in order, so `ready` -> `sessions` is observed as sent.
            var resyncPending = false
            client.frames.collect { env ->
                val before = _state.value
                _state.update { reduce(it, env, Clock.System.now().toEpochMilliseconds()) }
                sendQueuedAtRest(before, _state.value)
                answerImageUpload(env)
                if (env.type == Envelope.SPOKEN) {
                    runCatching { json.decodeFromString(com.superiorsoftllc.agenthq.ui.voice.SpokenNotice.serializer(), env.data) }
                        .getOrNull()?.let { _spoken.tryEmit(it) }
                }
                when (env.type) {
                    // A mail event can change who is blocked; ask rather than guess.
                    Envelope.MAIL -> client.send(Envelope(Envelope.MAIL_WAITING))
                    Envelope.READY -> {
                        resyncPending = true
                        // The board and the triggers load once the daemon says which contract it speaks, so an
                        // older daemon is never sent a frame it does not know (it would answer with an error).
                        val contract = _state.value.connection.contract
                        if (contractAtLeast(contract, 1, 5)) client.send(WireRequests.ticketList())
                        if (contractAtLeast(contract, 1, 14)) client.send(Envelope(Envelope.TRIGGER_LIST))
                        if (contractAtLeast(contract, 1, 8)) client.send(WireRequests.projectList())
                        if (contractAtLeast(contract, 1, 33)) client.send(WireRequests.commandList())
                        // U2: the plan-limit meters (1.59.0); the daemon also pushes them after each run's usage.
                        if (contractAtLeast(contract, 1, 59)) client.send(Envelope(Envelope.LIMITS))
                        // BUG-M2-11e: the one session-lifecycle setting, shown in the Configuration modal (1.68.0).
                        if (contractAtLeast(contract, LifecycleSetting.SINCE_MAJOR, LifecycleSetting.SINCE_MINOR)) {
                            client.send(WireRequests.settingsGet(LifecycleSetting.KEY))
                        }
                        // USER-PORTRAIT: the owner's picture (1.107.0); an unset key is an error on a single-key get, so ask for all.
                        if (contractAtLeast(contract, UserPortrait.SINCE_MAJOR, UserPortrait.SINCE_MINOR)) {
                            client.send(WireRequests.settingsGet(""))
                        }
                        // CLIPATH-1: where each CLI is launched from, shown in the Configuration modal (1.73.0).
                        if (contractAtLeast(contract, CliLocationsSetting.SINCE_MAJOR, CliLocationsSetting.SINCE_MINOR)) {
                            client.send(WireRequests.cliLocations())
                        }
                        // HARNESS-INSTALL-1: the daemon's own check of each harness CLI (1.100.0).
                        if (contractAtLeast(contract, 1, 102)) {
                            client.send(WireRequests.harnessInstallStatus())
                        }
                        // BUG-PERM-1: the global Tool approval, shown in the Configuration modal (1.76.0).
                        if (contractAtLeast(contract, ToolApprovalSetting.SINCE_MAJOR, ToolApprovalSetting.SINCE_MINOR)) {
                            client.send(WireRequests.toolApproval())
                        }
                        // SECRETS-1: the keystore's set / not set state, shown in the Configuration modal (1.100.0).
                        if (contractAtLeast(contract, 1, 102)) client.send(WireRequests.secretStatus())
                    }
                    // The sidebar shows the active project's workspaces, including the one chosen by default.
                    Envelope.PROJECTS -> _state.value.activeProjectId?.let { client.send(WireRequests.workspaceList(it)) }
                    // M2-7: the project this window just created is selected once the daemon has minted it.
                    Envelope.PROJECT -> {
                        val name = pendingCreatedProject
                        val made = _state.value.projects.firstOrNull { p -> p.name == name && before.projects.none { it.id == p.id } }
                        if (name != null && made != null) {
                            pendingCreatedProject = null
                            val dir = pendingCreatedDirectory; pendingCreatedDirectory = ""
                            selectProject(made.id)
                            if (dir.isNotEmpty()) saveProjectDirectory(made.id, dir)
                        }
                    }
                    // The snapshot after `ready` says which sessions exist now: fill what the drop missed.
                    Envelope.SESSIONS -> if (resyncPending) {
                        resyncPending = false
                        val s = _state.value
                        resyncRequests(s, s.historyRequested).forEach { sendReplay(it) }
                    }
                }
            }
        }
        scope.launch {
            client.opened.collect {
                // Resync everything the daemon does not replay on its own after (re)connect.
                client.send(Envelope(Envelope.MAIL_WAITING))
                // The Role picker / Role detail data and the attention history (contracts 1.12.0, 1.4.0, 1.3.0, 1.9.0).
                client.send(Envelope(Envelope.ROLE_LIST))
                client.send(Envelope(Envelope.ROLE_BINDING_LIST))
                // TRAITS-T8: the trait library and every role's trait list (contracts 1.48.0 / 1.49.0). An old daemon
                // answers `unknown frame type`, which the model swallows: the editor then shows an empty library.
                _state.update { it.withTraitsRequested() }
                client.send(WireRequests.traitList())
                client.send(WireRequests.roleTraitsList())
                client.send(Envelope(Envelope.AGENT_LIST))
                client.send(Envelope(Envelope.PROVIDERS))
                client.send(Envelope(Envelope.MODELS))
                client.send(Envelope(Envelope.ATTENTION_LIST))
                client.send(Envelope(Envelope.PROVIDER_STATES))
                _state.value.pods.keys.forEach { client.send(Envelope(Envelope.POD_QUERY, it)) }
            }
        }
        scope.launch {
            // Opening an agent (or a reconnect, or its record entering the history window) asks for its history.
            state.map { s ->
                val since = s.connection.historySince
                listOf(s.selectedId, s.connection.isLive, s.connection.instance, since != null && (s.selected?.session?.lastHeartbeat ?: -1) >= since)
            }.distinctUntilChanged().collect { requestSelectedHistory() }
        }
        scope.launch {
            // TASK-BRANCH-FOLLOW: opening an in-progress task worker (or that worker moving branch) points the Changes panel at its branch.
            var previous = _state.value
            state.collect { s ->
                val prev = previous
                previous = s
                _state.update { it.followWorkerBranch(prev) }
            }
        }
    }

    fun connect(url: String, token: String?) = client.connect(url, token)

    fun disconnect() = client.disconnect()

    /** SWITCH-1: the "Reconnect now" button. */
    fun reconnectNow() = client.reconnectNow()

    fun select(id: String?) = _state.update { it.copy(selectedId = id) }

    /** PANEL-TOGGLE-1: the title-bar toggle for one side panel (hide it if shown, show it if hidden). */
    fun togglePanel(side: PanelSide) = _state.update { it.copy(panels = it.panels.toggle(side)) }

    /** PANEL-TOGGLE-1: show one side panel if it is hidden; called first by anything that targets that panel. */
    fun revealPanel(side: PanelSide) = _state.update { it.copy(panels = it.panels.reveal(side)) }

    /** MOBILE-LAYOUT-1: in a compact window, show one pane (list, output or right panel) through the same panel flags. */
    fun showCompactScreen(screen: CompactScreen) = _state.update { it.copy(panels = it.panels.showCompact(screen)) }

    /**
     * MOBILE-LAYOUT-2: the window is now [compact] (below 600dp) or not, after a resize or a rotation. Crossing the
     * breakpoint swaps the panel flags with the other window class's parked ones; the same class changes nothing.
     */
    fun setCompactWindow(compact: Boolean) = _state.update { it.copy(panels = it.panels.forWindow(compact)) }

    /**
     * GIT VIEW: ask the daemon for ONE session's worktree status (changed files included). The answer is a
     * `worktree` frame the reducer merges. Silent on a refusal: this runs on a timer while the pane is open,
     * and a dropped connection is already shown by the connection chip, not by a notice every few seconds.
     */
    suspend fun refreshWorktree(sessionId: String): CommandResult = send(WireRequests.worktreeOf(sessionId))

    /**
     * CP-4(c): ask the daemon which `/` commands match [typed] (the text after the slash) for [sessionId]'s provider.
     * The answer is a `command_catalog` frame the reducer keeps. Silent on a refusal: it fires on every keystroke
     * while the human types a command, and the connection chip already shows a dropped link. With no session (spawn
     * mode) [provider] is the one picked for the new agent; null asks for the daemon's default.
     */
    suspend fun requestCommands(sessionId: String?, typed: String, provider: String? = null): CommandResult =
        send(WireRequests.commandCatalog(sessionId, typed, provider))

    /**
     * ASSISTANT-1 (contract 1.115.0): ask the daemon for the installation-wide Assistant. The daemon creates it on the first
     * request and answers with its `session` frame (it then shows up as [AppState.assistant]); later asks answer the same one.
     */
    suspend fun openAssistant(): CommandResult =
        if (!_state.value.connection.hasAssistant) report(CommandResult.Refused("this daemon is too old for the Assistant: update Infantry AI on the server (needs contract 1.115.0)"))
        else send(Envelope(Envelope.ASSISTANT, ""))

    private suspend fun send(env: Envelope): CommandResult =
        if (!_state.value.connection.isLive) {
            CommandResult.Refused("not connected to a daemon (${_state.value.connection.state.name.lowercase()}) - nothing was sent")
        } else if (client.send(env)) CommandResult.Sent else CommandResult.Refused("the connection dropped while sending - nothing was sent")

    private fun report(r: CommandResult): CommandResult {
        if (r is CommandResult.Refused) _state.update { it.withNotice(Clock.System.now().toEpochMilliseconds(), r.reason, null, NoticeKind.ERROR) }
        return r
    }

    /** Pick the on-screen session's history request and mark it asked, atomically, then send it. */
    private suspend fun requestSelectedHistory() {
        var picked: HistoryRequest? = null
        _state.update { s ->
            picked = historyRequest(s, s.historyRequested)
            picked?.let { s.copy(historyRequested = s.historyRequested + it.sessionId) } ?: s
        }
        picked?.let { sendReplay(it) }
    }

    private suspend fun sendReplay(r: HistoryRequest) {
        client.send(
            Envelope(
                Envelope.REPLAY_REQUEST,
                json.encodeToString(ReplayRequest.serializer(), ReplayRequest(r.afterSequence)),
                r.sessionId,
            ),
        )
    }

    /**
     * Spawn: `new` then `prompt`, so the daemon cuts a fresh session instead of continuing the active one.
     * The session it creates is selected when it appears, so the reply streams where the human is looking.
     */
    suspend fun spawn(prompt: String): CommandResult {
        if (prompt.isBlank()) return report(CommandResult.Refused("a new agent needs a task"))
        // A leading "/" is a command, never a task prompt (CP-4(e)): it is resolved here and never spawns.
        if (isSlashCommand(prompt)) return spawnBoxCommand(prompt)
        _state.update { it.withPendingSpawn() }
        val r = send(Envelope(Envelope.NEW, spawnOptionsData(_state.value.pickedModel, _state.value.pickedEffort, _state.value.spawnRole, _state.value.activeProjectId))).let { if (it is CommandResult.Sent) send(Envelope(Envelope.PROMPT, prompt)) else it }
        if (r is CommandResult.Refused) _state.update { it.copy(pendingSpawn = null) }
        return report(r)
    }

    /**
     * CP-4(e): a `/` line in the New agent box. Reads the picked provider's full command catalog from the daemon, then
     * applies `/model` and `/effort` as the next spawn's selection (answers [CommandResult.Sent], nothing is spawned),
     * refuses `/compact` (no session yet) and any unknown command with the known list. No `new` or `prompt` frame is sent.
     */
    private suspend fun spawnBoxCommand(text: String): CommandResult {
        val provider = _state.value.pickedModel?.provider
        val asked = send(WireRequests.commandCatalog(null, "", provider))
        if (asked is CommandResult.Refused) return report(asked)
        val answer = withTimeoutOrNull(5_000) {
            _state.map { it.slash }.first { it != null && it.sessionId == null && it.answer.prefix == "" && (provider == null || it.answer.provider == provider) }
        }?.answer ?: return report(CommandResult.Refused("could not read the command list from the daemon: ${text.trim()} was not run and no agent was spawned"))
        return when (val outcome = spawnSlash(text, answer)) {
            is SpawnSlash.Refuse -> report(CommandResult.Refused(outcome.reason))
            is SpawnSlash.SetModel -> {
                _state.update { it.copy(pickedModel = PickedModel(outcome.provider, outcome.model)).withNotice(Clock.System.now().toEpochMilliseconds(), "the next agent will run model ${outcome.model} on ${outcome.provider}", null) }
                CommandResult.Sent
            }
            is SpawnSlash.SetEffort -> {
                _state.update { it.copy(pickedEffort = outcome.level).withNotice(Clock.System.now().toEpochMilliseconds(), "the next agent will start with effort ${outcome.level}", null) }
                CommandResult.Sent
            }
        }
    }

    /**
     * CP-1b: re-read the provider/model catalog each time New agent opens, so a model added to a provider config
     * after connecting shows without a reconnect. The cached list stays on screen until the `models` frame replaces it.
     * Silent on a refusal (the connection chip already shows a dropped link).
     */
    suspend fun refreshModelCatalog(): CommandResult {
        val providers = send(Envelope(Envelope.PROVIDERS))
        if (providers is CommandResult.Refused) return providers
        return send(Envelope(Envelope.MODELS))
    }

    /** U2 (contract 1.59.0): ask for the plan-limit meters again; the daemon re-reads a provider at most once per 5 minutes. */
    suspend fun refreshLimits(): CommandResult = report(send(Envelope(Envelope.LIMITS)))

    /**
     * STATS-2: ask for [sessionId]'s statistics (contract 1.92.0); the answer is a `session_stats` frame the reducer
     * keeps. Silent on a refusal: the Stats tab polls every few seconds and the connection chip already shows a dropped
     * link. An older daemon is never sent the frame (it would answer with an error on every poll).
     */
    suspend fun requestSessionStats(sessionId: String): CommandResult =
        if (!contractAtLeast(_state.value.connection.contract, 1, 92)) CommandResult.Refused("this daemon does not report session statistics (contract 1.92.0)")
        else send(WireRequests.sessionStatsGet(sessionId))

    /**
     * BUG-M2-11e: store [minutes] as the daemon's `session.keepAliveMinutes` (0 = off). The shown value changes only when
     * the daemon answers `settings`, so a refused value (an `error` frame, shown as a notice) never looks applied.
     */
    suspend fun setKeepAliveMinutes(minutes: Int): CommandResult {
        if (minutes !in 0..LifecycleSetting.MAX_MINUTES) {
            return report(CommandResult.Refused("keep-alive must be 0..${LifecycleSetting.MAX_MINUTES} minutes"))
        }
        if (!contractAtLeast(_state.value.connection.contract, LifecycleSetting.SINCE_MAJOR, LifecycleSetting.SINCE_MINOR)) {
            return report(CommandResult.Refused("this daemon has no session lifecycle setting (needs contract 1.68.0)"))
        }
        return report(send(WireRequests.settingsSet(LifecycleSetting.KEY, JsonPrimitive(minutes))))
    }

    /**
     * USER-PORTRAIT: store [png] (already downscaled) as the daemon's `user.portrait`; null resets to the bundled default.
     * The shown portrait changes only when the daemon answers `settings`.
     */
    suspend fun setUserPortrait(png: ByteArray?): CommandResult {
        if (!contractAtLeast(_state.value.connection.contract, UserPortrait.SINCE_MAJOR, UserPortrait.SINCE_MINOR)) {
            return report(CommandResult.Refused("this daemon cannot store a portrait (needs contract 1.107.0)"))
        }
        return report(send(WireRequests.settingsSet(UserPortrait.KEY, JsonPrimitive(png?.let(UserPortrait::encode) ?: ""))))
    }

    /**
     * HARNESS-INSTALL-1: ask the DAEMON to check its harness CLIs ([checkLatest] also asks npm for the newest version). The
     * client never inspects its own disk: the same call works from the desktop, Android and the browser.
     */
    suspend fun checkHarnesses(checkLatest: Boolean = false): CommandResult {
        if (!contractAtLeast(_state.value.connection.contract, 1, 102)) {
            return report(CommandResult.Refused("this daemon has no harness installer (needs contract 1.102.0)"))
        }
        return report(send(WireRequests.harnessInstallStatus(checkLatest)))
    }

    /** HARNESS-INSTALL-1: ask the DAEMON to install ([update] = refresh outdated too) [ids] (empty = every missing one) on its own machine. */
    suspend fun installHarnesses(ids: List<String>, update: Boolean): CommandResult {
        if (!contractAtLeast(_state.value.connection.contract, 1, 102)) {
            return report(CommandResult.Refused("this daemon has no harness installer (needs contract 1.102.0)"))
        }
        _state.value = _state.value.copy(harnessInstallBusy = true, harnessInstallLog = emptyList())
        return report(send(WireRequests.harnessInstall(ids, update))).also {
            if (it !is CommandResult.Sent) _state.value = _state.value.copy(harnessInstallBusy = false)
        }
    }

    /**
     * CLIPATH-1: set CLI [id]'s executable to [path] (null/blank = clear back to the default location / PATH). The shown
     * row changes only when the daemon answers `cli_locations`; a refused path comes back as an `error` notice.
     */
    suspend fun setCliLocation(id: String, path: String?): CommandResult {
        if (!contractAtLeast(_state.value.connection.contract, CliLocationsSetting.SINCE_MAJOR, CliLocationsSetting.SINCE_MINOR)) {
            return report(CommandResult.Refused("this daemon has no CLI locations setting (needs contract 1.73.0)"))
        }
        val result = report(send(WireRequests.cliLocationSet(id, path?.trim()?.takeIf { it.isNotEmpty() })))
        // The path decides default-managed vs user-managed, so the daemon re-checks the harness CLIs against it.
        if (result is CommandResult.Sent && contractAtLeast(_state.value.connection.contract, 1, 102)) send(WireRequests.harnessInstallStatus())
        return result
    }

    /**
     * PROMPT-2: answer an open prompt. The card stays until the daemon's `prompt_resolved` closes it (on every client).
     */
    suspend fun answerPrompt(response: com.superiorsoftllc.agenthq.ui.protocol.PromptResponseWire): CommandResult =
        report(send(WireRequests.promptResponse(response)))

    /** ASK-1: pick (or override) an owner question; the window closes when the daemon's `question` push says so. */
    suspend fun answerQuestion(answer: com.superiorsoftllc.agenthq.ui.protocol.QuestionAnswerWire): CommandResult =
        report(send(WireRequests.questionAnswer(answer)))

    /** ASK-1: withdraw an owner question; the daemon mails the agent that no answer is coming. */
    suspend fun cancelQuestion(questionId: String): CommandResult = report(send(WireRequests.questionCancel(questionId)))

    /** ASK-1: "Later": hide the question window in this window only; the questions stay open (the pill brings them back). */
    fun dismissQuestions() {
        _state.update { s -> s.copy(dismissedQuestions = s.dismissedQuestions + s.openQuestions.map { it.id }) }
    }

    /** ASK-1: bring the question window back (the "questions waiting" pill). */
    fun showQuestions() {
        _state.update { it.copy(dismissedQuestions = emptySet()) }
    }

    /** PROMPT-2: hide a question card in this window only (Esc); the prompt stays open on the daemon until its timeout. */
    fun dismissPrompt(promptId: String) {
        _state.update { it.copy(dismissedPrompts = it.dismissedPrompts + promptId) }
    }

    /**
     * BUG-PERM-1: set the global Tool approval. It applies to every session's next run (a session spawned with its own
     * value keeps it); the shown value changes only when the daemon answers `tool_approval`.
     */
    suspend fun setToolApproval(mode: String): CommandResult {
        if (!contractAtLeast(_state.value.connection.contract, ToolApprovalSetting.SINCE_MAJOR, ToolApprovalSetting.SINCE_MINOR)) {
            return report(CommandResult.Refused("this daemon has no Tool approval setting (needs contract 1.76.0)"))
        }
        return report(send(WireRequests.toolApprovalSet(mode)))
    }

    /** SECRETS-1: store [value] under [name] in the daemon's keystore (add or replace). The value is never kept here. */
    suspend fun setSecret(name: String, value: String): CommandResult = secretFrame(WireRequests.secretSet(name, value))

    suspend fun clearSecret(name: String): CommandResult = secretFrame(WireRequests.secretClear(name))

    /** SECRETS-1: copy the older environment variable of [name] into the keystore (daemon side; no file is deleted). */
    suspend fun importSecret(name: String): CommandResult = secretFrame(WireRequests.secretImport(name))

    private suspend fun secretFrame(frame: Envelope): CommandResult {
        if (!contractAtLeast(_state.value.connection.contract, 1, 102)) {
            return report(CommandResult.Refused("this daemon has no keystore (needs contract 1.102.0)"))
        }
        return report(send(frame))
    }

    /** PICKER 2/2: the next spawn runs on [picked] (null = the daemon's defaults). No frame: `spawn` carries it in `new`. */
    fun pickModel(picked: PickedModel?) {
        _state.update { it.copy(pickedModel = picked) }
    }

    /**
     * The next turn of a conversation: the daemon continues THIS session's provider conversation. Refused up
     * front, with the reason, while the agent is still answering or when there is nothing to continue.
     */
    suspend fun prompt(sessionId: String, text: String): CommandResult {
        if (text.isBlank()) return report(CommandResult.Refused("nothing to send"))
        _state.value.agents[sessionId]?.let { a -> promptBlocker(a)?.let { return report(CommandResult.Refused(it)) } }
        val r = send(Envelope(Envelope.PROMPT, text, sessionId))
        // A leading "/" is a command, never a prompt (CP-4): the daemon answers it with a result or an error, so it is
        // not echoed into the chat as something the user said to the agent.
        if (r is CommandResult.Sent && !isSlashCommand(text)) echo(sessionId, text)
        return report(r)
    }

    /**
     * Resume a dead/finished session WITH its context. Refused up front, with the reason, when the
     * daemon's own rule says there is nothing to resume -- the same rule as `SessionRecord.resumable`.
     */
    suspend fun resume(sessionId: String, text: String): CommandResult {
        val a = _state.value.agents[sessionId] ?: return report(CommandResult.Refused("unknown session"))
        if (!a.session.resumable) return report(CommandResult.Refused(resumeBlocker(a.session)))
        val prompt = text.ifBlank { RESUME_DEFAULT_PROMPT }
        val r = send(Envelope(Envelope.RESUME, "$sessionId\n$prompt"))
        if (r is CommandResult.Sent) echo(sessionId, prompt)
        return report(r)
    }

    /**
     * Interrupt: stop the CURRENT turn of [sessionId] with the daemon's INTERRUPT frame. Refused, with the reason,
     * for an unknown or non-busy agent. Nothing local changes: the busy-to-idle transition shows only when the
     * daemon's own session frame reports it.
     */
    suspend fun interrupt(sessionId: String): CommandResult {
        val a = _state.value.agents[sessionId] ?: return report(CommandResult.Refused("unknown session"))
        interruptBlocker(a)?.let { return report(CommandResult.Refused(it)) }
        return report(send(Envelope(Envelope.INTERRUPT, sessionId)))
    }

    /**
     * Hold [text] for a BUSY agent: it joins that agent's queue in this window and is sent as the next turn once the
     * current one has come to rest ([sendQueuedAtRest]). It is not echoed into the transcript while it waits, so a
     * queued message can never read as sent. Refused for an agent that is not busy: send it with [prompt] instead.
     */
    fun queueMessage(sessionId: String, text: String): CommandResult {
        if (text.isBlank()) return report(CommandResult.Refused("nothing to queue"))
        val a = _state.value.agents[sessionId] ?: return report(CommandResult.Refused("unknown session"))
        if (!isBusy(a)) return report(CommandResult.Refused("the agent is not busy: send the message directly"))
        var position = 0
        val now = Clock.System.now().toEpochMilliseconds()
        _state.update { s ->
            val items = s.queuedFor(sessionId) + QueuedPrompt(text, now)
            position = items.size
            s.withQueue(sessionId, items)
        }
        return CommandResult.Queued(position)
    }

    /**
     * The turn of a queued agent has just ended (it went from a run in flight to rest): send the OLDEST queued
     * message as its next turn. One per transition, so the queue drains in order, one turn at a time. A message
     * the daemon will not take yet (no provider session id) stays queued and visible. ticket-b5aa01bc: from contract
     * 1.96.0 it goes out with a `requestId` and waits in [AppState.queueSending], so the daemon's busy refusal (another
     * run, e.g. mail, started first) puts it back at the front of the queue for the next turn end instead of losing it.
     */
    /** The `requestId`s of queued sends, unique in this window. */
    private var queueSends = 0

    private suspend fun sendQueuedAtRest(before: AppState, after: AppState) {
        for (sessionId in after.queued.keys) {
            val agent = after.agents[sessionId] ?: continue
            val wasRunning = before.agents[sessionId]?.let { isBusy(it) } ?: false
            if (!wasRunning || isBusy(agent) || agent.session.state != SessionState.IDLE) continue
            if (promptBlocker(agent) != null) continue
            val requestId = if (contractAtLeast(after.connection.contract, RunInFlight.SINCE_MAJOR, RunInFlight.SINCE_MINOR)) {
                "queue-${++queueSends}"
            } else null
            var head: QueuedPrompt? = null
            _state.update { s ->
                val items = s.queuedFor(sessionId)
                head = items.firstOrNull()
                val sent = head
                if (sent == null) s
                else s.withQueue(sessionId, items.drop(1)).let { q ->
                    if (requestId == null) q else q.copy(queueSending = q.queueSending + (requestId to QueueSend(sessionId, sent)))
                }
            }
            val next = head ?: continue
            val r = send(Envelope(Envelope.PROMPT, next.text, sessionId, requestId = requestId))
            if (r is CommandResult.Sent) {
                echo(sessionId, next.text)
            } else {
                // Not sent (the link dropped): it goes back to the front, still visibly queued, and the reason is shown.
                _state.update { s ->
                    s.copy(queueSending = if (requestId == null) s.queueSending else s.queueSending - requestId)
                        .withQueue(sessionId, listOf(next) + s.queuedFor(sessionId))
                }
                report(r)
            }
        }
    }

    /** A 1.11.0 daemon echoes the turn on the stream (to every window); only an older one needs a local echo. */
    private fun echo(sessionId: String, text: String) {
        if (_state.value.connection.echoesTurns) return
        _state.update { it.withUserLine(sessionId, text, Clock.System.now().toEpochMilliseconds()) }
    }

    /**
     * Start a session AS an agent profile (Role detail -> Start): `new {"agentId":...}` then the first
     * prompt. The daemon resolves the profile now (provider, model, role) and puts its instructions ahead
     * of the prompt; the session it creates is selected when it appears.
     */
    suspend fun spawnAs(agentId: String, prompt: String): CommandResult {
        if (prompt.isBlank()) return report(CommandResult.Refused("the session needs a first message"))
        _state.update { it.withPendingSpawn() }
        val options = buildJsonObject { put("agentId", agentId) }.toString()
        val r = send(Envelope(Envelope.NEW, options)).let { if (it is CommandResult.Sent) send(Envelope(Envelope.PROMPT, prompt)) else it }
        if (r is CommandResult.Refused) _state.update { it.copy(pendingSpawn = null) }
        return report(r)
    }

    /** Contract 1.40.0: set what [roleId] runs on (blank/null clears a pin). Future sessions only; the daemon pushes the stored binding to every window. */
    suspend fun saveRoleBinding(roleId: String, provider: String?, model: String?, effort: String?): CommandResult =
        report(send(Envelope(Envelope.ROLE_BINDING_SET, json.encodeToString(RoleBindingSave.serializer(), RoleBindingSave(roleId, provider?.ifBlank { null }, model?.trim()?.ifEmpty { null }, effort?.ifBlank { null })))))

    /**
     * ROLE-1c: the New agent box spawns as [roleId] (null = no role). The role's binding becomes the DEFAULT
     * selection -- [AppState.pickedModel] / [AppState.pickedEffort] -- which the model picker can still override
     * for this spawn. A binding with a provider but no model seeds the provider (the model stays the provider's default) and the effort.
     */
    fun pickSpawnRole(roleId: String?) {
        _state.update { s ->
            val b = roleId?.let { s.roleBindings[it] }
            s.copy(
                spawnRole = roleId,
                pickedModel = b?.let { PickedModel.fromBinding(it) },
                pickedEffort = b?.effort,
            )
        }
    }

    /** Contract 1.12.0: create or override a role; the daemon pushes the stored role to every window. */
    /** ROLE-ICON-1: [icon] null keeps the stored icon, "" clears it; it reaches only a daemon on contract >= 1.93.0. */
    suspend fun saveRole(id: String, name: String, color: String?, persona: String, icon: String? = null, description: String? = null): CommandResult =
        report(send(WireRequests.roleSave(
            RoleSave(id.trim(), name.trim(), color?.trim()?.takeIf { it.isNotEmpty() }, persona, icon?.trim(), description?.trim()),
            _state.value.connection.sendsRoleIcon, _state.value.connection.sendsRoleEdit,
        )))

    /** ROLE-EDIT-1: delete a custom role, or reset a customised bundled one; the daemon refuses while a live session has it. */
    suspend fun deleteRole(roleId: String): CommandResult = report(send(WireRequests.roleDelete(roleId)))

    /** Contract 1.4.0: create (no id) or edit a profile; the daemon answers `agent` with the stored profile. */
    suspend fun saveProfile(profile: AgentProfile): CommandResult = saveAgent(profile)

    /**
     * Role-first S8: create (no id) or edit an agent, carrying its effort from contract 1.46.0 (below it the key is not
     * sent). The daemon answers `agent` with the stored profile, or an `error` the reducer keeps as [AppState.agentError].
     */
    suspend fun saveAgent(profile: AgentProfile): CommandResult {
        _state.update { it.copy(agentError = null) }
        return report(send(WireRequests.agentSave(profile, _state.value.connection.sendsAgentEffort)))
    }

    /**
     * Role-first S8 (section 6): add a persistent agent of [role] to the active project -- `agent_save` -- and, when a
     * [task] is typed, start its first session as that agent: `new {"agentId","role"}` then `prompt`. A null [executor]
     * saves NO override, so the agent inherits the role default (and a later rebind still reaches it). [name] defaults
     * to the role's name. Refused, with nothing started, when the daemon refuses the save or does not answer.
     */
    suspend fun addAgent(role: String, executor: SwapExecutor?, task: String?, name: String? = null): CommandResult {
        val s = _state.value
        val projectId = s.activeProjectId ?: return report(CommandResult.Refused("pick a project first: an agent belongs to one project"))
        val agentName = name?.trim()?.ifEmpty { null } ?: s.roles.firstOrNull { it.id == role }?.name ?: role
        val known = s.profiles.mapNotNull { it.id }.toSet()
        val profile = AgentProfile(
            name = agentName,
            role = role,
            provider = executor?.provider?.ifBlank { null },
            model = executor?.model?.trim()?.ifEmpty { null },
            projectId = projectId,
            effort = executor?.effort?.ifBlank { null },
        )
        val saved = saveAgent(profile)
        if (saved is CommandResult.Refused) return saved
        // The `agent` answer goes to this window only; the stored profile is the first new id with this name and role.
        val outcome = withTimeoutOrNull(agentConfirmMs) {
            _state.map { st ->
                st.agentError?.let { Result.failure<AgentProfile>(IllegalStateException(it)) }
                    ?: st.profiles.firstOrNull { it.id != null && it.id !in known && it.name == agentName && it.role == role }?.let { Result.success(it) }
            }.first { it != null }
        } ?: return report(CommandResult.Refused("the daemon did not confirm the new agent: no session was started"))
        // A daemon refusal is already on screen (the reducer's notice); answer it without a second notice.
        val created = outcome.getOrElse { return CommandResult.Refused(it.message ?: "agent_save refused") }
        if (task.isNullOrBlank()) return CommandResult.Sent
        _state.update { it.withPendingSpawn() }
        val options = buildJsonObject {
            put("agentId", created.id)
            // Contract 1.43.0: the session's role travels with it; an older daemon resolves it from the profile.
            if (contractAtLeast(_state.value.connection.contract, 1, 43)) put("role", role)
        }.toString()
        val r = send(Envelope(Envelope.NEW, options)).let { if (it is CommandResult.Sent) send(Envelope(Envelope.PROMPT, task)) else it }
        if (r is CommandResult.Refused) _state.update { it.copy(pendingSpawn = null) }
        return report(r)
    }

    /**
     * Contract 1.47.0: move [agentId] onto [provider] / [model] / [effort] (blank = inherit), deciding its session by
     * [session]. The daemon answers every window with `agent_swapped`, which the reducer applies; a refusal is an
     * `error` kept as [AppState.agentError], and nothing changes here. Refused locally below 1.47.0.
     */
    suspend fun swapAgent(agentId: String, provider: String, model: String?, effort: String?, session: SwapSessionMode = SwapSessionMode.AUTO): CommandResult {
        if (!_state.value.connection.swapsAgents) return report(CommandResult.Refused("this daemon cannot swap an agent's executor (needs contract 1.47.0) - nothing was sent"))
        if (provider.isBlank()) return report(CommandResult.Refused("a swap needs a harness"))
        _state.update { it.copy(agentError = null) }
        return report(send(WireRequests.agentSwap(agentId, provider.trim(), model, effort, session)))
    }

    /**
     * SWAP-AGENT-1: `/harness <id>` on [sessionId] opens the one Switch agent picker with that harness pre-selected
     * ([AppState.harnessPickerAsk]); it switches nothing by itself, so a partial pick can never be applied. A harness
     * that cannot be picked (unavailable, or not one the daemon reports) is refused here as a notice.
     */
    suspend fun switchHarness(sessionId: String, harness: String): CommandResult {
        val s = _state.value
        if (sessionId !in s.agents) return report(CommandResult.Refused("this session no longer exists"))
        HarnessSwitch.refusal(s, harness)?.let { return report(CommandResult.Refused(it)) }
        _state.update { it.copy(harnessPickerAsk = HarnessPickerAsk(sessionId, harness.trim(), (it.harnessPickerAsk?.seq ?: 0) + 1)) }
        return CommandResult.Sent
    }

    /**
     * SWAP-AGENT-1: the Switch agent picker's Apply. [sessionId] keeps its id, tab and transcript and runs as [role]
     * (null = no role) on [picked]'s harness and model from its next turn. A saved agent's session goes through
     * `agent_swap` (session auto), and its role is the agent's own, so a different role is refused; a session with no
     * agent goes through `session_swap`, carrying the role when it changes (contract 1.86.0). Refused while a turn is in
     * flight ([SWITCH_WAIT_FOR_TURN]). The success notice waits for the daemon's confirmation.
     */
    suspend fun switchAgent(sessionId: String, role: String?, picked: PickedModel): CommandResult {
        val s = _state.value
        val a = s.agents[sessionId] ?: return report(CommandResult.Refused("this session no longer exists"))
        if (isBusy(a)) return report(CommandResult.Refused(SWITCH_WAIT_FOR_TURN))
        s.providers.firstOrNull { it.id == picked.provider && !it.available }?.let {
            return report(CommandResult.Refused(HarnessSwitch.unavailable(it.id, it.unavailableReason)))
        }
        val current = (a.session.role ?: a.role)?.takeIf { it.isNotBlank() }
        val wanted = role?.trim()?.takeIf { it.isNotEmpty() }
        val roleChanges = wanted != current
        val agentId = a.session.agentId?.takeIf { it.isNotBlank() }
        if (agentId != null) {
            if (roleChanges) {
                val name = s.profiles.firstOrNull { it.id == agentId }?.name ?: agentId
                return report(CommandResult.Refused("$name is a saved agent: its role is set in its profile. Start a new agent (Ctrl+N) to run another role."))
            }
            return sendSwitch(HarnessSwitch.Plan(sessionId, agentId, picked.provider, picked.model))
        }
        if (roleChanges && !s.connection.swapsRoles) {
            return report(CommandResult.Refused("This version of Infantry AI can't switch this session's role. Update Infantry AI, or start a new agent (Ctrl+N) with that role."))
        }
        return sendSwitch(HarnessSwitch.Plan(sessionId, null, picked.provider, picked.model, role = if (roleChanges) wanted.orEmpty() else null))
    }

    private suspend fun sendSwitch(plan: HarnessSwitch.Plan): CommandResult {
        val agentId = plan.agentId
        if (agentId != null) {
            _state.update { it.copy(pendingHarnessSwitch = it.pendingHarnessSwitch + (agentId to plan.harness)) }
            val r = swapAgent(agentId, plan.harness, plan.model, null, SwapSessionMode.AUTO)
            if (r is CommandResult.Refused) _state.update { it.copy(pendingHarnessSwitch = it.pendingHarnessSwitch - agentId) }
            return r
        }
        // UX-HARNESS-2: a session with no agent is switched in place; nothing is saved as an agent.
        if (!_state.value.connection.swapsSessions) {
            return report(CommandResult.Refused("This version of Infantry AI can't switch this session's provider. Update Infantry AI, or start a new session on the provider you want."))
        }
        _state.update { it.copy(pendingHarnessSwitch = it.pendingHarnessSwitch + (plan.sessionId to plan.harness), sessionSwitchError = null) }
        val r = report(send(WireRequests.sessionSwap(plan.sessionId, plan.harness.trim(), plan.model, plan.role)))
        if (r is CommandResult.Refused) _state.update { it.copy(pendingHarnessSwitch = it.pendingHarnessSwitch - plan.sessionId) }
        return r
    }

    /** Contract 1.4.0: delete a profile; the daemon answers `agents` with what is left. */
    suspend fun deleteProfile(id: String): CommandResult = report(send(Envelope(Envelope.AGENT_DELETE, id)))

    /** Contract 1.9.0: acknowledge an attention item; the daemon pushes the updated item to every window. */
    suspend fun ackAttention(id: String): CommandResult = report(send(Envelope(Envelope.ATTENTION_ACK, id)))

    // --- the scrum board (contracts 1.5.0 / 1.6.0) -------------------------------------------------

    /**
     * Create a ticket in `todo`. [agentId] is a profile id or `role:<role>`; null lets the daemon assign the
     * first profile's role, and "" leaves it unassigned. [epicId] (M2-14) files it under that epic; null or blank
     * sends none. TICKET-EXEC-UI: [provider] / [model] pin the ticket's executor; null or blank sends none (inherit).
     * The daemon pushes the stored ticket to every window.
     */
    suspend fun createTicket(
        title: String, description: String, ticketType: String?, agentId: String?, epicId: String? = null,
        provider: String? = null, model: String? = null,
    ): CommandResult {
        if (title.isBlank()) return report(CommandResult.Refused("a ticket needs a title"))
        val body = buildJsonObject {
            put("title", title.trim())
            if (description.isNotBlank()) put("description", description.trim())
            if (!ticketType.isNullOrBlank()) put("ticketType", ticketType)
            if (agentId != null) put("agentId", agentId)
            if (!epicId.isNullOrBlank()) put("epicId", epicId)
            if (!provider.isNullOrBlank()) put("provider", provider.trim())
            if (!model.isNullOrBlank()) put("model", model.trim())
            // UI-BOARD-PROJ: a ticket created from the Board joins the project the shell is scoped to.
            activeProjectId?.let { put("projectId", it) }
        }
        return report(send(Envelope(Envelope.TICKET_SAVE, body.toString())))
    }

    /**
     * M2-17: edit ticket [id] with `ticket_save`, sending only what changed: a null [title] / [description] / [epicId]
     * is left out (the daemon keeps it); an empty [description] clears it, and an empty [epicId] files the ticket under
     * no epic (contract 1.60.0; refused locally below it, where the daemon would silently keep the epic). The daemon
     * pushes the stored ticket to every window, or answers `ticket_save refused: ...`, kept as [AppState.ticketError].
     * TICKET-EXEC-UI: [agentId], [provider] and [model] follow the same rule: null is left out, "" clears the pin
     * (unassigned / inherit), else the new value (contract 1.6.0, unchanged on the wire).
     */
    suspend fun editTicket(
        id: String, title: String? = null, description: String? = null, epicId: String? = null,
        agentId: String? = null, provider: String? = null, model: String? = null,
        images: List<String>? = null,
    ): CommandResult {
        if (title != null && title.isBlank()) return report(CommandResult.Refused("a ticket needs a title"))
        if (images != null && !contractAtLeast(_state.value.connection.contract, 1, 84)) {
            return report(CommandResult.Refused("this daemon cannot attach images to tickets (needs contract 1.84.0) - nothing was sent"))
        }
        if (epicId != null && epicId.isBlank() && !_state.value.connection.clearsTicketEpic) {
            return report(CommandResult.Refused("this daemon cannot clear a ticket's epic (needs contract 1.60.0) - nothing was sent"))
        }
        val body = buildJsonObject {
            put("id", id)
            if (title != null) put("title", title.trim())
            if (description != null) put("description", description.trim())
            if (epicId != null) put("epicId", epicId.trim())
            if (agentId != null) put("agentId", agentId.trim())
            if (provider != null) put("provider", provider.trim())
            if (model != null) put("model", model.trim())
            // IMG-4: the full list replaces the stored one; [] removes every image.
            if (images != null) put("images", kotlinx.serialization.json.JsonArray(images.map { JsonPrimitive(it) }))
        }
        _state.update { it.copy(ticketError = null) }
        return report(send(Envelope(Envelope.TICKET_SAVE, body.toString())))
    }

    /**
     * IMG-4: upload [bytes] as a ticket image of [projectId] (`image_upload`, purpose `ticket`) and wait for the
     * daemon's answer, which goes to this connection only: [ImageUploadOutcome.Stored] carries the relPath to save on
     * the ticket. Uploads run one at a time, so each `image` / `image_upload refused` answer is the pending one's.
     */
    suspend fun uploadTicketImage(projectId: String?, fileName: String, mimeType: String, bytes: ByteArray): ImageUploadOutcome {
        fun refused(reason: String) = ImageUploadOutcome.Refused(reason)
        if (projectId.isNullOrBlank()) return refused("the ticket has no project to store images in")
        if (!contractAtLeast(_state.value.connection.contract, 1, 84)) {
            return refused("this daemon cannot attach images to tickets (needs contract 1.84.0) - nothing was sent")
        }
        return uploadImage(projectId, ImageUploadWire.PURPOSE_TICKET, fileName, mimeType, bytes)
    }

    /**
     * IMG-2: upload [bytes] as a chat image of [projectId] (`image_upload`, purpose `chat`, contract 1.83.0). The
     * [ImageUploadOutcome.Stored] path is the absolute file the agent is told to read (`[image: <path>]`).
     */
    suspend fun uploadChatImage(projectId: String?, fileName: String, mimeType: String, bytes: ByteArray): ImageUploadOutcome {
        if (projectId.isNullOrBlank()) return ImageUploadOutcome.Refused("pick a project first: chat images are stored in its directory")
        if (!contractAtLeast(_state.value.connection.contract, 1, 83)) {
            return ImageUploadOutcome.Refused("this daemon cannot take images (needs contract 1.83.0) - nothing was sent")
        }
        return uploadImage(projectId, ImageUploadWire.PURPOSE_CHAT, fileName, mimeType, bytes)
    }

    /**
     * ATTACH-INLINE-1: upload [bytes] as a chat FILE of any type (`image_upload`, purpose `file`, contract 1.89.0, 20 MB).
     * The [ImageUploadOutcome.Stored] path is the absolute file the agent is told to read (`[file: <path>]`).
     */
    suspend fun uploadChatFile(projectId: String?, fileName: String, mimeType: String, bytes: ByteArray): ImageUploadOutcome {
        if (projectId.isNullOrBlank()) return ImageUploadOutcome.Refused("pick a project first: chat files are stored in its directory")
        if (!contractAtLeast(_state.value.connection.contract, 1, 89)) {
            return ImageUploadOutcome.Refused("this daemon cannot take files other than images (needs contract 1.89.0) - nothing was sent")
        }
        return uploadImage(projectId, ImageUploadWire.PURPOSE_FILE, fileName, mimeType, bytes)
    }

    private suspend fun uploadImage(projectId: String, purpose: String, fileName: String, mimeType: String, bytes: ByteArray): ImageUploadOutcome {
        fun refused(reason: String) = ImageUploadOutcome.Refused(reason)
        if (bytes.isEmpty()) return refused("the file is empty")
        val limit = ImageUploadWire.maxBytes(purpose)
        if (bytes.size > limit) return refused("the file is over the ${limit / (1024 * 1024)} MB limit")
        val body = ImageUploadWire(projectId, purpose, fileName, mimeType, Base64.encode(bytes))
        return imageUploadLock.withLock {
            val answer = CompletableDeferred<ImageUploadOutcome>()
            pendingImageUpload = answer
            try {
                if (!client.send(Envelope(Envelope.IMAGE_UPLOAD, json.encodeToString(ImageUploadWire.serializer(), body)))) {
                    return@withLock refused("not connected to the daemon")
                }
                withTimeoutOrNull(IMAGE_UPLOAD_TIMEOUT_MS) { answer.await() } ?: refused("the daemon did not answer the upload")
            } finally {
                pendingImageUpload = null
            }
        }
    }

    private val imageUploadLock = Mutex()
    @kotlin.concurrent.Volatile
    private var pendingImageUpload: CompletableDeferred<ImageUploadOutcome>? = null

    /** IMG-4: settle the pending upload with this frame, when it is the daemon's answer to one. */
    private fun answerImageUpload(env: Envelope) {
        val pending = pendingImageUpload ?: return
        when {
            env.type == Envelope.IMAGE -> pending.complete(
                runCatching { ImageUploadOutcome.Stored(json.decodeFromString(ImageStored.serializer(), env.data)) }
                    .getOrElse { ImageUploadOutcome.Refused("the daemon's answer could not be read") },
            )
            env.type == Envelope.ERROR && env.sessionId == null && env.data.startsWith(Envelope.IMAGE_UPLOAD + " ") ->
                pending.complete(ImageUploadOutcome.Refused(env.data.removePrefix(Envelope.IMAGE_UPLOAD + " ")))
        }
    }

    /**
     * Move a ticket to a column. Moving to `in_progress` hands it to its assigned agent: the daemon starts the
     * session (or answers queued / held / refused, which the reducer turns into a notice). [position] places it in
     * the new column (0 = top); a hand-out to `in_progress` ignores it.
     */
    suspend fun moveTicket(id: String, status: String, wontFix: Boolean? = null, position: Int? = null): CommandResult {
        val body = buildJsonObject {
            put("id", id)
            put("status", status)
            if (wontFix != null) put("wontFix", wontFix)
            if (position != null) put("position", position)
        }
        return report(send(Envelope(Envelope.TICKET_MOVE, body.toString())))
    }

    /** Reorder inside the ticket's own column (no status): [position] 0 is the top. Ticket order is priority. */
    suspend fun reorderTicket(id: String, position: Int): CommandResult {
        val body = buildJsonObject {
            put("id", id)
            put("position", position)
        }
        return report(send(Envelope(Envelope.TICKET_MOVE, body.toString())))
    }

    suspend fun deleteTicket(id: String): CommandResult = report(send(Envelope(Envelope.TICKET_DELETE, id)))

    /** Start the top startable todo ticket; the daemon answers `error` when nothing can start. */
    suspend fun startNextTicket(): CommandResult = report(send(Envelope(Envelope.TICKET_START_NEXT)))

    // --- triggers (contract 1.14.0) ---------------------------------------------------------------

    /**
     * Create (no [id]) or edit a manual `start_next` trigger. [agentId] limits it to one profile or `role:<role>`
     * (null = any ticket). [coalesceMs] 0 starts at once; the daemon's own default window is two minutes.
     */
    suspend fun saveTrigger(id: String?, name: String, agentId: String?, coalesceMs: Long, ceilingRuns: Int): CommandResult {
        if (name.isBlank()) return report(CommandResult.Refused("a trigger needs a name"))
        val body = buildJsonObject {
            if (id != null) put("id", id)
            put("name", name.trim())
            put("kind", "manual")
            put("coalesceMs", coalesceMs)
            put("ceilingRuns", ceilingRuns)
            putJsonObject("action") {
                put("kind", "start_next")
                if (!agentId.isNullOrBlank()) put("agentId", agentId)
            }
        }
        return report(send(Envelope(Envelope.TRIGGER_SAVE, body.toString())))
    }

    /** TRIGGER-NUDGE (contract 1.105.0): save the trigger form as drafted -- any kind, action, interval and project. */
    internal suspend fun saveTrigger(id: String?, draft: com.superiorsoftllc.agenthq.ui.TriggerDraft): CommandResult {
        draft.problem()?.let { return report(CommandResult.Refused(it)) }
        return report(send(Envelope(Envelope.TRIGGER_SAVE, draft.toSave(id).toString())))
    }

    suspend fun fireTrigger(id: String): CommandResult = report(send(Envelope(Envelope.TRIGGER_FIRE, id)))

    /** Re-arm a tripped (or re-enable a disabled) trigger. This is the only way out of `tripped`. */
    suspend fun armTrigger(id: String): CommandResult = report(send(Envelope(Envelope.TRIGGER_ARM, id)))

    suspend fun disableTrigger(id: String): CommandResult = report(send(Envelope(Envelope.TRIGGER_DISABLE, id)))

    suspend fun deleteTrigger(id: String): CommandResult = report(send(Envelope(Envelope.TRIGGER_DELETE, id)))

    /** Ask for a trigger's event rows; the answer replaces what the window holds for it. */
    suspend fun loadTriggerEvents(id: String): CommandResult {
        _state.update { it.withEventsAwaiting(id) }
        return report(send(Envelope(Envelope.TRIGGER_EVENTS, id)))
    }

    // --- projects & workspaces (contracts 1.8.0 / 1.18.0) ------------------------------------------

    /** The project the shell is scoped to (also `state.activeProjectId`); UI-SHELL-2 hangs `epic_list` off it. */
    val activeProjectId: String? get() = _state.value.activeProjectId

    /**
     * Scope the shell to [projectId] and ask the daemon for that project's workspaces
     * (`workspace_list`), and its board (`ticket_list`). An id
     * the daemon never listed is refused up front and changes nothing.
     */
    suspend fun selectProject(projectId: String): CommandResult {
        if (_state.value.projects.none { it.id == projectId }) return report(CommandResult.Refused("unknown project"))
        _state.update { it.selectProject(projectId) }
        val workspaces = report(send(WireRequests.workspaceList(projectId)))
        if (workspaces is CommandResult.Refused) return workspaces
        // UI-BOARD-PROJ: refresh the selected project's board; the scoped answer merges into the model.
        _state.update { it.withTicketList(projectId) }
        return report(send(WireRequests.ticketList(projectId)))
    }

    /** Create a project (`project_save`); the daemon answers a `project` frame, or an `error` that names the refusal. */
    /** M2-7: the name of a project [createProject] asked for, selected when its `project` frame arrives. */
    private var pendingCreatedProject: String? = null
    private var pendingCreatedDirectory: String = ""

    /**
     * M2-7 (New Project modal): create a project (the same `project_save` as [saveProject]) and, once the daemon
     * answers with the new project, select it (the same [selectProject] the picker uses).
     */
    suspend fun createProject(name: String, directory: String = ""): CommandResult {
        if (name.isBlank()) return report(CommandResult.Refused("a project needs a name"))
        pendingCreatedProject = name.trim()
        pendingCreatedDirectory = directory.trim()
        val r = saveProject(name)
        if (r !is CommandResult.Sent) pendingCreatedProject = null
        return r
    }

    /** Rename project [projectId] (an edit `project_save`); the daemon answers the updated `project` frame, or a refusal. */
    suspend fun renameProject(projectId: String, name: String): CommandResult {
        if (name.isBlank()) return report(CommandResult.Refused("a project needs a name"))
        if (_state.value.projects.none { it.id == projectId }) return report(CommandResult.Refused("unknown project"))
        return report(send(WireRequests.projectRename(projectId, name.trim())))
    }

    suspend fun saveProject(name: String, goal: String? = null): CommandResult {
        if (name.isBlank()) return report(CommandResult.Refused("a project needs a name"))
        return report(send(WireRequests.projectSave(name.trim(), goal?.trim()?.takeIf { it.isNotEmpty() })))
    }

    /**
     * Set a project's directory, or clear it with `""` (`project_save` edit, contract 1.36.0). The daemon answers a
     * `project` frame carrying the saved directory, or an `error` `project_save refused: directory ...` that the
     * pane shows inline. An unknown project id is refused up front.
     */
    suspend fun saveProjectDirectory(projectId: String, directory: String): CommandResult {
        if (_state.value.projects.none { it.id == projectId }) return report(CommandResult.Refused("unknown project"))
        if (!supportsProjectGit()) return report(CommandResult.Refused(PROJECT_GIT_UNSUPPORTED))
        return report(send(WireRequests.projectSetDirectory(projectId, directory.trim())))
    }

    /** Ask for [projectId]'s working tree (`project_git_status`) and history (`project_git_log`); older daemons are sent nothing. */
    suspend fun loadProjectGit(projectId: String): CommandResult {
        if (!supportsProjectGit()) return report(CommandResult.Refused(PROJECT_GIT_UNSUPPORTED))
        if (_state.value.projects.none { it.id == projectId }) return report(CommandResult.Refused("unknown project"))
        val status = report(send(WireRequests.projectGitStatus(projectId)))
        if (status is CommandResult.Refused) return status
        return report(send(WireRequests.projectGitLog(projectId)))
    }

    /**
     * Ask for [projectId]'s working tree only (`project_git_status`, no history). The side panel polls this while it
     * is open, so it must stay cheaper than [loadProjectGit]; older daemons are sent nothing.
     */
    suspend fun loadProjectTree(projectId: String): CommandResult {
        if (!supportsProjectGit()) return report(CommandResult.Refused(PROJECT_GIT_UNSUPPORTED))
        if (_state.value.projects.none { it.id == projectId }) return report(CommandResult.Refused("unknown project"))
        return report(send(WireRequests.projectGitStatus(projectId)))
    }

    /**
     * Ask for one changed file's diff (`project_git_diff`), on the index side the file was listed under. [worktree]
     * (UX-CHANGES-1) reads it in that worktree; it needs contract 1.88.0, and is never sent to an older daemon.
     */
    suspend fun loadProjectDiff(projectId: String, path: String, staged: Boolean, worktree: String? = null): CommandResult {
        if (!supportsProjectGit()) return report(CommandResult.Refused(PROJECT_GIT_UNSUPPORTED))
        if (worktree != null && !supportsWorktreeView()) return report(CommandResult.Refused(PROJECT_GIT_WORKTREES_UNSUPPORTED))
        if (path.isBlank()) return report(CommandResult.Refused("pick a changed file first"))
        return report(send(WireRequests.projectGitDiff(projectId, path, staged, worktree)))
    }

    /**
     * UX-CHANGES-1: ask for [projectId]'s worktrees (`project_git_worktrees`, contract 1.88.0), naming the selected
     * session so the daemon marks (and opens the view on) the checkout it runs in. Refused below 1.88.0.
     */
    suspend fun loadProjectWorktrees(projectId: String, sessionId: String?): CommandResult {
        if (!supportsWorktreeView()) return report(CommandResult.Refused(PROJECT_GIT_WORKTREES_UNSUPPORTED))
        if (_state.value.projects.none { it.id == projectId }) return report(CommandResult.Refused("unknown project"))
        return report(send(WireRequests.projectGitWorktrees(projectId, sessionId?.takeIf { it.isNotBlank() })))
    }

    /** UX-CHANGES-1: ask for one worktree's branch, ahead/behind, changes and history (`project_git_worktree_status`). */
    suspend fun loadWorktreeStatus(projectId: String, path: String): CommandResult {
        if (!supportsWorktreeView()) return report(CommandResult.Refused(PROJECT_GIT_WORKTREES_UNSUPPORTED))
        if (path.isBlank()) return report(CommandResult.Refused("pick a worktree first"))
        return report(send(WireRequests.projectGitWorktreeStatus(projectId, path)))
    }

    /** UX-CHANGES-1: show the worktree at [path] in [projectId]'s Changes tab, for the selected session. UI-side only. */
    fun selectChangesWorktree(projectId: String, path: String) {
        _state.update { it.withChangesWorktree(projectId, path) }
    }

    /** True when the connected daemon speaks contract 1.88.0 or later (the worktree view frames). */
    fun supportsWorktreeView(): Boolean = contractAtLeast(_state.value.connection.contract, 1, 88)

    /** True when the connected daemon speaks contract 1.99.0 or later (the `pipeline_*` frames). */
    fun supportsPipeline(): Boolean = contractAtLeast(_state.value.connection.contract, 1, 99)

    /** Ask for [projectId]'s pipeline (`pipeline_show`); refused below contract 1.99.0. */
    suspend fun loadPipeline(projectId: String): CommandResult {
        if (!supportsPipeline()) return report(CommandResult.Refused(PIPELINE_UNSUPPORTED))
        return report(send(WireRequests.pipelineShow(projectId)))
    }

    /** Set [projectId]'s pipeline stages, or remove the pipeline when [stages] is null (`pipeline_config`). */
    suspend fun configurePipeline(projectId: String, stages: List<String>?, exclusiveCheck: Boolean = false): CommandResult {
        if (!supportsPipeline()) return report(CommandResult.Refused(PIPELINE_UNSUPPORTED))
        if (stages != null && stages.isEmpty()) return report(CommandResult.Refused("a pipeline needs at least one stage"))
        return report(send(WireRequests.pipelineConfigure(projectId, stages, exclusiveCheck)))
    }

    /** Ask for [projectId]'s local branches (`project_git_branches`); refused below contract 1.65.0. */
    suspend fun loadProjectBranches(projectId: String): CommandResult {
        if (!supportsBranchView()) return report(CommandResult.Refused(PROJECT_GIT_BRANCHES_UNSUPPORTED))
        if (_state.value.projects.none { it.id == projectId }) return report(CommandResult.Refused("unknown project"))
        return report(send(WireRequests.projectGitBranches(projectId)))
    }

    /** Ask what [branch] changed vs its (default) base (`project_git_branch_changes`); refused below 1.65.0. */
    suspend fun loadBranchChanges(projectId: String, branch: String): CommandResult {
        if (!supportsBranchView()) return report(CommandResult.Refused(PROJECT_GIT_BRANCHES_UNSUPPORTED))
        if (branch.isBlank()) return report(CommandResult.Refused("pick a branch first"))
        return report(send(WireRequests.projectGitBranchChanges(projectId, branch)))
    }

    /** Ask for one file's diff on [branch] vs its base (`project_git_branch_diff`); refused below 1.65.0. */
    suspend fun loadBranchDiff(projectId: String, branch: String, path: String): CommandResult {
        if (!supportsBranchView()) return report(CommandResult.Refused(PROJECT_GIT_BRANCHES_UNSUPPORTED))
        if (branch.isBlank() || path.isBlank()) return report(CommandResult.Refused("pick a branch file first"))
        return report(send(WireRequests.projectGitBranchDiff(projectId, branch, path)))
    }

    /**
     * BRANCH-SWITCH-1: switch [projectId]'s checked-out branch to [branch], or create it from the current HEAD and switch
     * when [create] (`project_git_checkout`). The daemon answers `project_git_checkout_result` -- a refusal (dirty tree,
     * branch held by another worktree, bad name) is that answer, shown inline -- and pushes fresh status after a switch.
     * Refused here below contract 1.74.0, for an unknown project and for a blank name.
     */
    suspend fun checkoutProjectBranch(projectId: String, branch: String, create: Boolean = false): CommandResult {
        if (!supportsBranchSwitch()) return report(CommandResult.Refused(PROJECT_GIT_CHECKOUT_UNSUPPORTED))
        if (_state.value.projects.none { it.id == projectId }) return report(CommandResult.Refused("unknown project"))
        val name = branch.trim()
        if (name.isEmpty()) return report(CommandResult.Refused("enter a branch name"))
        return report(send(WireRequests.projectGitCheckout(projectId, name, create)))
    }

    /**
     * BASE-1b: set the branch new ticket starts in [projectId] cut from (`project_base`, contract 1.81.0); null clears it
     * (the daemon's default, the checked-out HEAD). The daemon answers the whole `project`, or an `error`
     * `project_base refused: ...` when the branch does not resolve where tickets are cut. Refused here below 1.81.0, for an
     * unknown project and for a blank name.
     */
    suspend fun setProjectBaseBranch(projectId: String, branch: String?): CommandResult {
        if (!supportsProjectBase()) return report(CommandResult.Refused(PROJECT_BASE_UNSUPPORTED))
        if (_state.value.projects.none { it.id == projectId }) return report(CommandResult.Refused("unknown project"))
        val name = branch?.trim()
        if (name != null && name.isEmpty()) return report(CommandResult.Refused("pick a branch"))
        return report(send(WireRequests.projectBase(projectId, name)))
    }

    /**
     * LANE-CAPS: set how many agents of [role] may run at once in [projectId] (`project_config`, contract 1.111.0); 1 is the
     * default. The daemon answers (and pushes) the whole `project`. Lowering never stops a running agent.
     */
    suspend fun setLaneCap(projectId: String, role: String, cap: Int): CommandResult {
        if (!contractAtLeast(_state.value.connection.contract, 1, 111)) return report(CommandResult.Refused("This daemon cannot set agent caps (needs contract 1.111.0)."))
        if (_state.value.projects.none { it.id == projectId }) return report(CommandResult.Refused("unknown project"))
        if (cap < 1) return report(CommandResult.Refused("a cap is 1 or more"))
        return report(send(WireRequests.projectConfig(projectId, role, cap)))
    }

    /** True when the connected daemon speaks contract 1.81.0 or later (`project_base`). */
    fun supportsProjectBase(): Boolean = contractAtLeast(_state.value.connection.contract, 1, 81)

    /** BRANCH-VIEW-2: show [branch] in [projectId]'s Changes panel (null = the working tree). UI-side only. */
    fun selectChangesBranch(projectId: String, branch: String?) {
        _state.update { if (contractAtLeast(it.connection.contract, 1, 88)) it.withChangesView(projectId, branch) else it.withChangesBranch(projectId, branch) }
    }

    /** True when the connected daemon speaks contract 1.65.0 or later (the branch view frames). */
    fun supportsBranchView(): Boolean = contractAtLeast(_state.value.connection.contract, 1, 65)

    /** True when the connected daemon speaks contract 1.74.0 or later (`project_git_checkout`). */
    fun supportsBranchSwitch(): Boolean = contractAtLeast(_state.value.connection.contract, 1, 74)

    /** True when the connected daemon speaks contract 1.36.0 or later (the project git frames). */
    fun supportsProjectGit(): Boolean = contractAtLeast(_state.value.connection.contract, 1, 36)

    // --- in-app build runs (contract 1.33.0) ----------------------------------------------------------

    /** Start the saved command [commandId] (`run_start`); refused on a daemon older than 1.33.0. */
    suspend fun startRun(commandId: String): CommandResult {
        if (!contractAtLeast(_state.value.connection.contract, 1, 33)) {
            return report(CommandResult.Refused("this daemon does not support runs (needs contract 1.33.0)"))
        }
        if (commandId.isBlank()) return report(CommandResult.Refused("pick a command to run"))
        return report(send(WireRequests.runStart(commandId)))
    }

    /** True when the connected daemon speaks contract 1.94.0 or later (`command_save` / `command_delete`). */
    fun supportsCommandEdit(): Boolean = contractAtLeast(_state.value.connection.contract, 1, 94)

    /**
     * RUN-CMD-2: save (upsert) the command [id] (`command_save`). The daemon answers with the new `commands` list, or
     * an `error` that lands in [RunsState.commandError]; a previous refusal is cleared before sending.
     */
    suspend fun saveCommand(id: String, name: String, argv: List<String>, cwd: String?, graceMillis: Long): CommandResult {
        if (!supportsCommandEdit()) return CommandResult.Refused("this daemon cannot edit commands (needs contract 1.94.0)")
        if (id.isBlank()) return CommandResult.Refused("the command needs an id")
        if (argv.isEmpty()) return CommandResult.Refused("the command needs at least one argument (the program)")
        _state.update { it.copy(runs = it.runs.copy(commandError = null)) }
        return send(WireRequests.commandSave(id, name, argv, cwd, graceMillis))
    }

    /** RUN-CMD-2: delete the saved command [id] (`command_delete`); answered like [saveCommand]. */
    suspend fun deleteCommand(id: String): CommandResult {
        if (!supportsCommandEdit()) return CommandResult.Refused("this daemon cannot edit commands (needs contract 1.94.0)")
        if (id.isBlank()) return CommandResult.Refused("pick a command to delete")
        _state.update { it.copy(runs = it.runs.copy(commandError = null)) }
        return send(WireRequests.commandDelete(id))
    }

    /** Stop [runId] (`run_stop`); the daemon always answers with a `run_finished`. */
    suspend fun stopRun(runId: String, force: Boolean = false): CommandResult {
        if (!contractAtLeast(_state.value.connection.contract, 1, 33)) {
            return report(CommandResult.Refused("this daemon does not support runs (needs contract 1.33.0)"))
        }
        if (runId.isBlank()) return report(CommandResult.Refused("no run to stop"))
        return report(send(WireRequests.runStop(runId, force)))
    }

    // --- epics of the selected project and their tickets (contract 1.19.0, E1-d) ---------------------

    /**
     * Ask for [projectId]'s epics (`epic_list`) and its board (`ticket_list`, scoped like [selectProject]); the answers
     * replace the model's slices. E1-d: an epic's work is its tickets, so `story_list` is no longer asked for.
     */
    suspend fun loadEpics(projectId: String): CommandResult {
        val epics = report(send(WireRequests.epicList(projectId)))
        if (epics is CommandResult.Refused) return epics
        _state.update { it.withTicketList(projectId) }
        return report(send(WireRequests.ticketList(projectId)))
    }

    /** Create an epic inside [projectId] (`epic_save`); the daemon answers an `epic` frame. */
    suspend fun saveEpic(projectId: String?, name: String, goal: String? = null): CommandResult {
        if (projectId == null) return report(CommandResult.Refused("select a project first"))
        if (name.isBlank()) return report(CommandResult.Refused("an epic needs a name"))
        return report(send(WireRequests.epicSave(projectId, name.trim(), goal?.trim()?.takeIf { it.isNotEmpty() })))
    }

    /**
     * E1-d: "Add task" inside an epic. Creates a ticket in `todo` with `ticket_save{projectId, title, epicId}`, left
     * unassigned (`agentId: ""`, as the `story_save` shim does). The daemon pushes the stored `ticket` to every window.
     * Moving it is the board's own [moveTicket] (`ticket_move`).
     */
    suspend fun addEpicTask(projectId: String?, title: String, epicId: String): CommandResult {
        if (projectId == null) return report(CommandResult.Refused("select a project first"))
        if (title.isBlank()) return report(CommandResult.Refused("a task needs a title"))
        if (epicId.isBlank()) return report(CommandResult.Refused("a task added from an epic needs that epic"))
        val body = buildJsonObject {
            put("projectId", projectId)
            put("title", title.trim())
            put("epicId", epicId)
            put("agentId", "")
        }
        return report(send(Envelope(Envelope.TICKET_SAVE, body.toString())))
    }

    // --- prompt library (contract 1.44.0, P10-C) ------------------------------------------------

    /** Ask for the whole library (`prompt_list`); the daemon answers `prompts`. */
    suspend fun loadPrompts(): CommandResult {
        _state.update { it.withPromptsRequested() }
        val r = report(send(WireRequests.promptList()))
        if (r is CommandResult.Refused) _state.update { it.copy(promptsLoading = false) }
        return r
    }

    /** Save (create or update by name) a prompt; [tagsCsv] is the comma separated tag field. The daemon answers `prompt_item`. */
    suspend fun savePrompt(name: String, body: String, tagsCsv: String): CommandResult =
        report(send(WireRequests.promptSave(name.trim(), body, parsePromptTags(tagsCsv))))

    /** Delete a prompt by id; the daemon pushes the remaining `prompts`. */
    suspend fun deletePrompt(id: String): CommandResult = report(send(WireRequests.promptDelete(id)))

    // --- skills library (contract 1.45.0, P10-D) ------------------------------------------------

    /** Ask for the whole library (`skill_list`); the daemon answers `skills`. */
    suspend fun loadSkills(): CommandResult {
        _state.update { it.withSkillsRequested() }
        val r = report(send(WireRequests.skillList()))
        if (r is CommandResult.Refused) _state.update { it.copy(skillsLoading = false) }
        return r
    }

    /** Save (create or update by name) a skill; [tagsCsv] is the comma separated tag field. The daemon answers `skill_item`. */
    suspend fun saveSkill(name: String, description: String, body: String, tagsCsv: String): CommandResult =
        report(send(WireRequests.skillSave(name.trim(), description.trim(), body, parsePromptTags(tagsCsv))))

    /** Delete a skill by id; the daemon pushes the remaining `skills`. */
    suspend fun deleteSkill(id: String): CommandResult = report(send(WireRequests.skillDelete(id)))

    // --- trait library and role traits (contracts 1.48.0 / 1.49.0, TRAITS-T8) -------------------

    /** Ask for the whole library (`trait_list`) and every role's list (`role_traits_list`). */
    suspend fun loadTraits(): CommandResult {
        _state.update { it.withTraitsRequested() }
        val r = report(send(WireRequests.traitList()))
        if (r is CommandResult.Refused) _state.update { it.copy(traitsLoading = false) } else send(WireRequests.roleTraitsList())
        return r
    }

    /** Create or update (by id) a trait from the editor's draft; the daemon pushes `trait_item` to every window. */
    suspend fun saveTrait(draft: TraitDraft): CommandResult {
        _state.update { it.copy(traitError = null) }
        return report(send(WireRequests.traitSave(draft.toSave())))
    }

    /** Delete a trait by id; the daemon pushes the remaining `traits` (and refuses while a role or an agent binds it). */
    suspend fun deleteTrait(id: String): CommandResult = report(send(WireRequests.traitDelete(id)))

    /** Store [roleId]'s whole ordered trait list (`[]` clears); the daemon pushes `role_traits` to every window. */
    suspend fun setRoleTraits(roleId: String, traitIds: List<String>): CommandResult {
        _state.update { it.copy(traitError = null) }
        return report(send(WireRequests.roleTraitsSet(roleId, traitIds)))
    }

    // --- project memory of the selected project (contracts 1.20.0 / 1.21.0) -------------------------

    /**
     * Ask for [projectId]'s memory index (`memory_list`). The answer (`memory_notes`) names no project,
     * so the pending request is recorded first ([withMemoryList]); it replaces that project's notes slice.
     */
    suspend fun loadMemory(projectId: String, at: NoteScope = NoteScope.Project): CommandResult {
        _state.update { it.withMemoryList(projectId, at) }
        return report(send(WireRequests.memoryList(projectId, at)))
    }

    /** NOTES-1: delete one note of a scope (`memory_delete`); the daemon answers that scope's `memory_notes` index. */
    suspend fun deleteMemory(projectId: String, name: String, at: NoteScope = NoteScope.Project): CommandResult {
        _state.update { it.withMemoryList(projectId, at) }
        return report(send(WireRequests.memoryDelete(projectId, name, at)))
    }

    /**
     * Search [projectId]'s memory for [query] (`memory_search`). The answer (`memory_hits`) names neither
     * project nor query, so the pending request is recorded first ([withMemorySearch]) and becomes the
     * window's latest search once it lands. Refused, with the reason, when there is nothing to search.
     */
    suspend fun searchMemory(projectId: String?, query: String, at: NoteScope = NoteScope.Project): CommandResult {
        if (projectId == null) return report(CommandResult.Refused("select a project first"))
        if (query.isBlank()) return report(CommandResult.Refused("a search needs a query"))
        _state.update { it.withMemorySearch(projectId, query.trim(), at) }
        return report(send(WireRequests.memorySearch(projectId, query.trim(), at)))
    }

    /** Write a memory note inside [projectId] (`memory_save`); the daemon answers a `memory_note` frame. */
    suspend fun saveMemory(projectId: String?, name: String, description: String, body: String, at: NoteScope = NoteScope.Project): CommandResult {
        if (projectId == null) return report(CommandResult.Refused("select a project first"))
        if (name.isBlank()) return report(CommandResult.Refused("a note needs a name"))
        return report(send(WireRequests.memorySave(projectId, name.trim(), description.trim(), body, at)))
    }

    /**
     * Read one whole note of [projectId] (`memory_get`, contract 1.51.0). The `memory_notes` index withholds bodies,
     * so editing a note asks for it; the `memory_note` answer upserts it by name, body included.
     */
    suspend fun getMemory(projectId: String, name: String, at: NoteScope = NoteScope.Project): CommandResult =
        report(send(WireRequests.memoryGet(projectId, name, at)))

    suspend fun kill(sessionId: String, force: Boolean = false): CommandResult =
        report(send(Envelope(Envelope.KILL, if (force) "$sessionId force" else sessionId)))

    /**
     * AGENT-UX-11: permanently remove [sessionId]'s durable record. The daemon refuses an unknown id
     * or a currently-running (`live`) session with an `error` frame; on success it pushes a
     * refreshed `sessions` list, which the reducer uses to drop the row -- this method itself
     * returns only whether the daemon accepted the frame.
     */
    suspend fun deleteSession(sessionId: String): CommandResult =
        report(send(Envelope(Envelope.SESSION_DELETE, sessionId)))

    /**
     * BUG-M2-15b: bind the unbound [sessionId] to [projectId] (`session_project`). The daemon answers with a `session`
     * push carrying the new projectId, which re-scopes the agent lists; a refusal comes back as an `error` frame.
     */
    suspend fun assignSessionProject(sessionId: String, projectId: String): CommandResult =
        report(send(Envelope(Envelope.SESSION_PROJECT, buildJsonObject { put("sessionId", sessionId); put("projectId", projectId) }.toString())))

    /**
     * UI-AGENT-DEL: safely remove [sessionId] from the sidebar. `session_delete` refuses a
     * currently-running session, so a session with a run in flight is killed first (the daemon
     * processes the two frames in order and `kill` lands the record in EXITED before the delete
     * is read). An idle/history session is deleted directly.
     */
    suspend fun removeSession(sessionId: String): CommandResult {
        val running = _state.value.agents[sessionId]?.session?.state in setOf(
            SessionState.STARTING,
            SessionState.BUSY,
            SessionState.WAITING_INPUT,
        )
        if (running) {
            val killed = kill(sessionId)
            if (killed is CommandResult.Refused) return killed
        }
        return deleteSession(sessionId)
    }

    suspend fun submitPod(goal: String, nodes: List<NodeSpec>, maxConcurrency: Int = 1): CommandResult =
        report(
            send(
                Envelope(
                    Envelope.POD_SUBMIT,
                    json.encodeToString(PodSubmitRequest.serializer(), PodSubmitRequest(goal, nodes, maxConcurrency)),
                ),
            ),
        )

    suspend fun queryPod(podId: String): CommandResult = report(send(Envelope(Envelope.POD_QUERY, podId.trim())))

    suspend fun cancelPod(podId: String, force: Boolean = false): CommandResult =
        report(send(Envelope(Envelope.POD_CANCEL, if (force) "$podId force" else podId)))

    companion object {
        const val RESUME_DEFAULT_PROMPT = "Continue where you left off."
        /** IMG-4: how long an upload waits for the daemon's `image` answer. */
        const val IMAGE_UPLOAD_TIMEOUT_MS = 30_000L

        /** The exact reason `resumable` is false, in words, for the disabled control's tooltip. */
        fun resumeBlocker(s: SessionRecord): String = when {
            s.providerSessionId == null -> "nothing to resume yet: the provider has not reported a session id"
            s.state == SessionState.STARTING || s.state == SessionState.BUSY || s.state == SessionState.WAITING_INPUT ->
                "a run is in flight; resume applies once it has stopped"
            else -> "not resumable"
        }
    }
}
