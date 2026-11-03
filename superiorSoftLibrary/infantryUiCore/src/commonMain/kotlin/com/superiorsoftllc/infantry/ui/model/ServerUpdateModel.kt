// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** SERVER-UPDATE-4 (contract 1.110.0): the daemon's `GET /update/status` and `POST /update/check` body. */
@Serializable
data class ServerUpdateStatusWire(
    val version: String = "",
    val contract: String = "",
    val distribution: String = "",
    val canSelfUpdate: Boolean = false,
    val enabled: Boolean = false,
    val disabledReason: String? = null,
    val state: String = "idle",
    val available: ServerUpdateOfferWire? = null,
    val lastChecked: Long? = null,
    val error: String? = null,
    val instructions: String? = null,
    val runningAgents: Int = 0,
    val staged: String? = null,
)

@Serializable
data class ServerUpdateOfferWire(val version: String = "", val notes: String = "")

/** The daemon's `POST /update/apply` request and reply. */
@Serializable
data class UpdateApplyRequestWire(val confirm: Boolean = false)

@Serializable
data class UpdateApplyReplyWire(val result: String = "", val version: String? = null, val running: Int? = null, val message: String? = null)

/** SERVER-UPDATE-4: an answer from the daemon's update routes. [Unsupported] = a daemon older than contract 1.110.0 (no such route). */
sealed interface UpdateApiResult<out T> {
    data class Ok<T>(val value: T) : UpdateApiResult<T>
    data object Unsupported : UpdateApiResult<Nothing>
    data class Failed(val message: String) : UpdateApiResult<Nothing>
}

/** SERVER-UPDATE-4: the three update calls over the existing remote connection (HTTP on the daemon's own port). */
interface ServerUpdateApi {
    suspend fun status(): UpdateApiResult<ServerUpdateStatusWire>
    suspend fun check(): UpdateApiResult<ServerUpdateStatusWire>
    suspend fun apply(confirm: Boolean): UpdateApiResult<UpdateApplyReplyWire>
}

/** The newest daemon contract this build speaks; a daemon below it lacks features, one above it is newer than this app. */
object ClientContract {
    const val EXPECTED = "1.110.0"
}

/** SERVER-UPDATE-4: when a client and a daemon differ enough to warn about. Patch-only differences do not. */
object ServerVersionCheck {
    private fun parts(v: String?): List<Int>? =
        v?.trim()?.removePrefix("v")?.substringBefore('-')?.split('.')?.map { it.toIntOrNull() ?: return null }?.takeIf { it.size >= 2 }

    private fun cmp(a: List<Int>, b: List<Int>): Int = compareValuesBy(a, b, { it[0] }, { it[1] })

    /**
     * A one-line warning, or null when the pair is fine. [clientVersion] may be null (a host that cannot tell); then only
     * the contract is compared. Compares major.minor only.
     */
    fun mismatch(clientVersion: String?, serverVersion: String?, serverContract: String?, clientContract: String = ClientContract.EXPECTED): String? {
        val sc = parts(serverContract)
        val cc = parts(clientContract)
        if (sc != null && cc != null) {
            if (sc[0] != cc[0]) return "This server speaks contract $serverContract and this app expects $clientContract: they are not compatible. Update whichever is older."
            if (cmp(sc, cc) < 0) return "This server (contract $serverContract) is older than this app expects ($clientContract): some features will not work. Update the server."
        }
        val cv = parts(clientVersion)
        val sv = parts(serverVersion)
        if (cv != null && sv != null) {
            val c = cmp(cv, sv)
            if (c > 0) return "This app ($clientVersion) is newer than the server ($serverVersion). Update the server."
            if (c < 0) return "This server ($serverVersion) is newer than this app ($clientVersion). Update the app."
        }
        return null
    }
}

/** SERVER-UPDATE-4: what the Server section shows. */
data class ServerUpdateView(
    val status: ServerUpdateStatusWire? = null,
    val phase: Phase = Phase.IDLE,
    /** False once a daemon answered 404: it predates the update routes. */
    val supported: Boolean = true,
    val message: String? = null,
    /** The "update anyway?" question, set between the button press and the answer. */
    val confirm: Confirm? = null,
) {
    enum class Phase { IDLE, CHECKING, APPLYING, RESTARTING }

    data class Confirm(val version: String, val running: Int) {
        val question: String
            get() = if (running > 0) "$running agent${if (running == 1) "" else "s"} running, update anyway?" else "Update the server to $version?"
    }

    val busy: Boolean get() = phase != Phase.IDLE
    val updateAvailable: String? get() = status?.available?.version?.takeIf { it.isNotEmpty() }
    val canUpdate: Boolean get() = supported && status?.canSelfUpdate == true && updateAvailable != null && !busy
}

/**
 * SERVER-UPDATE-4: the Server section's logic (HEADLESS-1: no UI here). The UI shows [view] and calls [refresh], [check],
 * [requestUpdate] / [confirmUpdate] / [cancelConfirm] and, when the connection comes back, [onReconnected].
 *
 * The update never starts without the confirm step; a daemon that cannot update itself (Docker) offers no button, only
 * the instructions it sent.
 */
class ServerUpdateState(private val api: ServerUpdateApi, private val scope: CoroutineScope) {
    private val _view = MutableStateFlow(ServerUpdateView())
    val view: StateFlow<ServerUpdateView> = _view.asStateFlow()
    private var expected: String? = null

    /** DEMO-FIX-1: false under a restricted (read-only) daemon policy, where `GET /update/status` is refused (403): no call is made. */
    var available: Boolean = true

    fun refresh() { if (available) scope.launch { load(checkNow = false) } }

    fun check() { scope.launch { _view.update { it.copy(phase = ServerUpdateView.Phase.CHECKING, message = null) }; load(checkNow = true) } }

    private suspend fun load(checkNow: Boolean) {
        val result = if (checkNow) api.check() else api.status()
        _view.update { v ->
            when (result) {
                is UpdateApiResult.Ok -> v.copy(status = result.value, supported = true, phase = ServerUpdateView.Phase.IDLE, message = result.value.error)
                UpdateApiResult.Unsupported -> v.copy(status = null, supported = false, phase = ServerUpdateView.Phase.IDLE, message = null)
                is UpdateApiResult.Failed -> v.copy(phase = ServerUpdateView.Phase.IDLE, message = result.message)
            }
        }
    }

    /** The Update button: ask first. With agents working the question names them. */
    fun requestUpdate() {
        val v = _view.value
        if (!v.canUpdate) return
        _view.update { it.copy(confirm = ServerUpdateView.Confirm(v.updateAvailable.orEmpty(), v.status?.runningAgents ?: 0)) }
    }

    fun cancelConfirm() { _view.update { it.copy(confirm = null) } }

    /** The user said yes: stage the update. A daemon under the launcher restarts itself; the connection then drops and returns. */
    fun confirmUpdate() {
        val target = _view.value.confirm?.version ?: return
        _view.update { it.copy(confirm = null, phase = ServerUpdateView.Phase.APPLYING, message = null) }
        scope.launch {
            val result = api.apply(confirm = true)
            _view.update { v ->
                when (result) {
                    is UpdateApiResult.Ok -> {
                        val reply = result.value
                        when (reply.result) {
                            "staged" -> {
                                val restarting = reply.message?.startsWith("restarting") == true
                                if (restarting) expected = reply.version ?: target
                                v.copy(
                                    phase = if (restarting) ServerUpdateView.Phase.RESTARTING else ServerUpdateView.Phase.IDLE,
                                    message = if (restarting) "Updating to ${reply.version ?: target}: the server is restarting" else reply.message ?: "Staged $target; restart the server to run it",
                                )
                            }
                            "agents_running" -> v.copy(phase = ServerUpdateView.Phase.IDLE, confirm = ServerUpdateView.Confirm(target, reply.running ?: 1))
                            else -> v.copy(phase = ServerUpdateView.Phase.IDLE, message = reply.message ?: reply.result)
                        }
                    }
                    UpdateApiResult.Unsupported -> v.copy(phase = ServerUpdateView.Phase.IDLE, supported = false)
                    is UpdateApiResult.Failed -> v.copy(phase = ServerUpdateView.Phase.IDLE, message = result.message)
                }
            }
        }
    }

    /** The link came back (after the restart, or any reconnect): read the status again and say whether the new version is running. */
    fun onReconnected() {
        if (!available) return
        scope.launch {
            val result = api.status()
            _view.update { v ->
                if (result !is UpdateApiResult.Ok) return@update v
                val now = result.value
                val want = expected
                expected = null
                v.copy(
                    status = now, supported = true, phase = ServerUpdateView.Phase.IDLE,
                    message = when {
                        want == null -> v.message
                        want == now.version -> "Updated: the server now runs ${now.version}"
                        else -> "The server came back on ${now.version}, not $want (it may have rolled back)"
                    },
                )
            }
        }
    }
}
