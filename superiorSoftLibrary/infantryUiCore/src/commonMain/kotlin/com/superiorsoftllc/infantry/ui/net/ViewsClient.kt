// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.net

import com.superiorsoftllc.infantry.plugin.api.ViewAccess
import com.superiorsoftllc.infantry.plugin.api.ViewInfo
import com.superiorsoftllc.infantry.plugin.api.ViewOrigin
import com.superiorsoftllc.infantry.plugin.api.ViewState
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * DV-2: [ViewAccess] over the daemon's view frames (DECL-UI-DESIGN 2.2): `plugin_views`, `plugin_view` (subscribe),
 * `plugin_view_close`, the `plugin_view_changed` push (re-fetch) and `plugin_tool_call` with `origin`. Names no plugin and no widget.
 * All state is touched under [lock]; the host calls [onFrame] for every inbound frame and [reopen] after each (re)connect.
 */
class ViewsClient(
    private val send: suspend (Envelope) -> Boolean,
    private val tool: suspend (String, JsonObject, JsonObject?) -> Result<String>,
    private val timeoutMs: Long = 20_000L,
) : ViewAccess {
    private class Open(val id: Int, val module: String, val view: String, val params: JsonObject, val out: Channel<ViewState>) {
        var lastRev = -1L
    }

    private val lock = Mutex()
    private var requests = 0
    private var opens = 0
    private val active = LinkedHashMap<Int, Open>()
    private val pendingFetch = HashMap<String, Open>()
    private val pendingList = HashMap<String, CompletableDeferred<Envelope>>()

    override suspend fun list(): List<ViewInfo> {
        val answer = CompletableDeferred<Envelope>()
        val id = lock.withLock { "views-list-${++requests}".also { pendingList[it] = answer } }
        try {
            if (!send(Envelope("plugin_views", "{}", requestId = id))) return emptyList()
            val env = withTimeoutOrNull(timeoutMs) { answer.await() } ?: return emptyList()
            if (env.type != "plugin_views_result") return emptyList()
            val arr = Json.parseToJsonElement(env.data).jsonObject["views"] as? JsonArray ?: return emptyList()
            return arr.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val module = o.str("module") ?: return@mapNotNull null
                val vid = o.str("id") ?: return@mapNotNull null
                ViewInfo(module, vid, o.str("title") ?: vid, o.str("place") ?: "none", (o["platforms"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        } finally {
            lock.withLock { pendingList.remove(id) }
        }
    }

    override fun open(module: String, view: String, params: JsonObject): Flow<ViewState> = channelFlow {
        val o = lock.withLock { Open(++opens, module, view, params, Channel(Channel.UNLIMITED)).also { active[it.id] = it } }
        send(ViewState.Loading)
        fetch(o)
        try {
            for (s in o.out) send(s)
        } finally {
            // Cancelled: close the subscription unless another open of the same view and params still needs it.
            withContext(NonCancellable) {
                val last = lock.withLock {
                    active.remove(o.id)
                    pendingFetch.values.removeAll { it === o }
                    active.values.none { it.module == module && it.view == view && it.params == params }
                }
                if (last) this@ViewsClient.send(Envelope("plugin_view_close", body(module, view, params).toString()))
            }
        }
    }

    override suspend fun act(origin: ViewOrigin, tool: String, args: JsonObject): Result<String> =
        this.tool(tool, args, JsonObject(mapOf("module" to JsonPrimitive(origin.module), "view" to JsonPrimitive(origin.view))))

    /** After a (re)connect: every open view asks again; the daemon's rev counter may have restarted, so an older one is accepted. */
    suspend fun reopen() {
        val all = lock.withLock { active.values.toList().onEach { it.lastRev = -1L } }
        all.forEach { fetch(it) }
    }

    /** Called for every inbound frame; ignores the ones that are not view frames. */
    suspend fun onFrame(env: Envelope) {
        when (env.type) {
            "plugin_views_result", Envelope.ERROR -> {
                val d = env.requestId?.let { id -> lock.withLock { pendingList[id] } }
                if (d != null) d.complete(env)
                else if (env.type == Envelope.ERROR) failFetch(env)
            }
            "plugin_view_result" -> answer(env)
            "plugin_view_changed" -> changed(env)
        }
    }

    private suspend fun failFetch(env: Envelope) {
        val o = lock.withLock { env.requestId?.let { pendingFetch.remove(it) } } ?: return
        o.out.trySend(ViewState.Error(env.data.lineSequence().firstOrNull().orEmpty().take(200)))
    }

    private suspend fun fetch(o: Open) {
        val id = lock.withLock { "view-${++requests}".also { pendingFetch[it] = o } }
        val data = JsonObject(body(o.module, o.view, o.params) + ("subscribe" to JsonPrimitive(true)))
        if (!send(Envelope("plugin_view", data.toString(), requestId = id))) {
            lock.withLock { pendingFetch.remove(id) }
            o.out.trySend(ViewState.Error("not connected to the daemon"))
        }
    }

    private suspend fun answer(env: Envelope) {
        val o = lock.withLock { env.requestId?.let { pendingFetch.remove(it) } } ?: return
        val r = runCatching { Json.parseToJsonElement(env.data).jsonObject }.getOrNull()
        if (r == null) { o.out.trySend(ViewState.Error("unreadable plugin_view_result")); return }
        val err = r.str("error")
        if (err != null) { o.out.trySend(ViewState.Error(err.lineSequence().first().take(200))); return }
        val rev = r["rev"]?.jsonPrimitive?.longOrNull ?: 0L
        val page = r["page"] as? JsonObject
        if (page == null) { o.out.trySend(ViewState.Error("plugin_view_result has no page")); return }
        val show = lock.withLock { if (rev < o.lastRev) false else { o.lastRev = rev; true } }
        if (show) o.out.trySend(ViewState.Page(rev, page))
    }

    private suspend fun changed(env: Envelope) {
        val c = runCatching { Json.parseToJsonElement(env.data).jsonObject }.getOrNull() ?: return
        val module = c.str("module") ?: return
        val view = c.str("view") ?: return
        val params = c["params"] as? JsonObject
        val hit = lock.withLock { active.values.filter { it.module == module && (view == "*" || it.view == view) && (params == null || it.params == params) } }
        hit.forEach { fetch(it) }
    }

    private fun body(module: String, view: String, params: JsonObject): JsonObject =
        JsonObject(mapOf("module" to JsonPrimitive(module), "view" to JsonPrimitive(view), "params" to params))

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
