// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.updater

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Contract 1.110.0: the body of `POST /update/apply`. [confirm] accepts updating while agents are working. */
@Serializable
data class UpdateApplyRequest(val confirm: Boolean = false)

/** Contract 1.110.0: the body of a refused or finished `POST /update/apply`. */
@Serializable
data class UpdateApplyReply(
    val result: String,
    val version: String? = null,
    val running: Int? = null,
    val message: String? = null,
)

/** One answer of the update surface: HTTP status + JSON body. Kept free of daemon types so it is testable alone. */
data class RouteAnswer(val status: Int, val json: String)

/**
 * Contract 1.110.0: the update surface of the daemon (UPDATER-1: served by Infantry_Updater's daemon half, behind the access
 * policy like every route but `/health`, so a client needs the bearer token to see the status, let alone start an update).
 *
 *  - `GET  /update/status`  the [ServerUpdateStatus], no network.
 *  - `POST /update/check`   ask the release source now; answers the status.
 *  - `POST /update/apply`   `{"confirm":false}`; 200 `staged`, 409 `agents_running` / `unsupported` / `nothing_to_apply`, 502 `failed`.
 *
 * Applying stages the new version; the restart is not done here.
 */
object UpdateRoutes {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun status(service: ServerUpdateService) = RouteAnswer(200, json.encodeToString(ServerUpdateStatus.serializer(), service.status()))

    fun check(service: ServerUpdateService) = RouteAnswer(200, json.encodeToString(ServerUpdateStatus.serializer(), service.check()))

    fun apply(service: ServerUpdateService, body: String): RouteAnswer {
        val request = runCatching { json.decodeFromString(UpdateApplyRequest.serializer(), body.ifBlank { "{}" }) }.getOrNull()
            ?: return reply(400, UpdateApplyReply("bad_request", message = "malformed update apply payload"))
        return when (val result = service.apply(request.confirm)) {
            is ApplyResult.Staged -> reply(200, UpdateApplyReply("staged", version = result.version, message = if (service.restartsAfterStaging) "restarting into ${result.version}" else "restart the daemon to run ${result.version}"))
            ApplyResult.NothingToApply -> reply(409, UpdateApplyReply("nothing_to_apply", message = "no newer version is known; check first"))
            is ApplyResult.AgentsRunning -> reply(409, UpdateApplyReply("agents_running", running = result.running, message = "${result.running} agents are working; send confirm=true to update anyway"))
            is ApplyResult.Unsupported -> reply(409, UpdateApplyReply("unsupported", message = result.instructions))
            is ApplyResult.Failed -> reply(502, UpdateApplyReply("failed", message = result.message))
        }
    }

    private fun reply(status: Int, body: UpdateApplyReply) = RouteAnswer(status, json.encodeToString(UpdateApplyReply.serializer(), body))
}
