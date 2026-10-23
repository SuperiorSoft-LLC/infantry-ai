package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.update.ApplyResult
import com.superiorsoftllc.agenthq.update.ServerUpdateService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val updateJson = Json { ignoreUnknownKeys = true }

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

/**
 * Contract 1.110.0: the update surface of the headless daemon. Behind [installAccessPolicy] like every route but
 * `/health`, so a client needs the bearer token to see the status, let alone start an update.
 *
 *  - `GET  /update/status`  the [com.superiorsoftllc.agenthq.update.ServerUpdateStatus], no network.
 *  - `POST /update/check`   ask the release source now; answers the status.
 *  - `POST /update/apply`   `{"confirm":false}`; 200 `staged`, 409 `agents_running` / `unsupported` / `nothing_to_apply`, 502 `failed`.
 *
 * Network and disk work runs off the event loop. Applying stages the new version; the restart is not done here.
 */
fun Route.updateRoutes(service: ServerUpdateService) {
    get("/update/status") { call.respond(service.status()) }

    post("/update/check") { call.respond(withContext(Dispatchers.IO) { service.check() }) }

    post("/update/apply") {
        val request = runCatching { updateJson.decodeFromString(UpdateApplyRequest.serializer(), call.receiveText().ifBlank { "{}" }) }.getOrNull()
        if (request == null) {
            call.respond(HttpStatusCode.BadRequest, UpdateApplyReply("bad_request", message = "malformed update apply payload"))
            return@post
        }
        when (val result = withContext(Dispatchers.IO) { service.apply(request.confirm) }) {
            is ApplyResult.Staged -> call.respond(HttpStatusCode.OK, UpdateApplyReply("staged", version = result.version, message = if (service.restartsAfterStaging) "restarting into ${result.version}" else "restart the daemon to run ${result.version}"))
            ApplyResult.NothingToApply -> call.respond(HttpStatusCode.Conflict, UpdateApplyReply("nothing_to_apply", message = "no newer version is known; check first"))
            is ApplyResult.AgentsRunning -> call.respond(HttpStatusCode.Conflict, UpdateApplyReply("agents_running", running = result.running, message = "${result.running} agents are working; send confirm=true to update anyway"))
            is ApplyResult.Unsupported -> call.respond(HttpStatusCode.Conflict, UpdateApplyReply("unsupported", message = result.instructions))
            is ApplyResult.Failed -> call.respond(HttpStatusCode.BadGateway, UpdateApplyReply("failed", message = result.message))
        }
    }
}
