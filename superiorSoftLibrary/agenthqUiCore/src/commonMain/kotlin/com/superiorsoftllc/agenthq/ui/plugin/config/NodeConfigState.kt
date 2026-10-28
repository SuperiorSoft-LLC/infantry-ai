package com.superiorsoftllc.agenthq.ui.plugin.config

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.superiorsoftllc.agenthq.plugin.api.ConfigSetBy
import com.superiorsoftllc.agenthq.plugin.api.DaemonReply
import com.superiorsoftllc.agenthq.plugin.api.NodeConfigEntry
import com.superiorsoftllc.agenthq.ui.plugin.DaemonLink
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** P1 step 2: the daemon's `node_config` routes (6s B); a failure is a [Result.failure] with a line for the view. */
interface NodeConfigApi {
    /** Seeds [entries] (only keys not stored yet) and answers every stored row. */
    suspend fun seed(entries: List<NodeConfigEntry>): Result<List<NodeConfigEntry>>
    /** Writes [entry] (null value = remove) and answers every stored row. */
    suspend fun put(entry: NodeConfigEntry): Result<List<NodeConfigEntry>>
}

/** [NodeConfigApi] over [DaemonLink], the window's own daemon (the bearer it connected with). */
object DaemonNodeConfigApi : NodeConfigApi {
    private val json = Json { ignoreUnknownKeys = true }
    private val list = ListSerializer(NodeConfigEntry.serializer())

    override suspend fun seed(entries: List<NodeConfigEntry>) = rows(DaemonLink.window.send("POST /node-config/seed", json.encodeToString(list, entries)))
    override suspend fun put(entry: NodeConfigEntry) = rows(DaemonLink.window.send("PUT /node-config", json.encodeToString(NodeConfigEntry.serializer(), entry)))

    private fun rows(reply: DaemonReply): Result<List<NodeConfigEntry>> = when (reply) {
        is DaemonReply.Ok -> if (reply.status in 200..299) runCatching { json.decodeFromString(list, reply.body) }
            // A daemon older than node_config answers 404: the view says so instead of showing an empty tree as if it were real.
            else Result.failure(IllegalStateException(if (reply.status == 404) "this daemon has no node configuration yet (update it)" else "the daemon answered ${reply.status}"))
        is DaemonReply.Refused -> Result.failure(IllegalStateException(reply.reason))
        is DaemonReply.Failed -> Result.failure(IllegalStateException(reply.reason))
    }
}

/** Where the stored copy is: not asked yet, being fetched, here, or not reachable ([NodeConfigState.error] says why). */
enum class NodeConfigLoad { IDLE, LOADING, LOADED, FAILED }

/**
 * P1 step 2: the one owner of the stored config (6s B). Holds the daemon's rows as composition state, so whatever reads
 * them through [ResolvedConfig] recomposes on a change; [load] seeds the daemon from [tree] and takes what it answers,
 * [set] writes one value. Until a load succeeds, everything resolves to the declared defaults.
 */
class NodeConfigState(
    val tree: ConfigTree,
    private val api: NodeConfigApi = DaemonNodeConfigApi,
    /** Runs after each successful [load] (6s D: a new implementation may become a field's selection). */
    private val afterLoad: suspend (NodeConfigState) -> Unit = {},
) {
    /** The stored rows, node id -> key -> row. */
    var stored: Map<String, Map<String, NodeConfigEntry>> by mutableStateOf(emptyMap())
        private set
    var load: NodeConfigLoad by mutableStateOf(NodeConfigLoad.IDLE)
        private set
    /** The last failure, one line; null after a success. */
    var error: String? by mutableStateOf(null)
        private set

    /** The row stored for [node] itself (no inheritance), or null. */
    fun own(node: String, key: String): NodeConfigEntry? = stored[node]?.get(key)

    suspend fun load() {
        load = NodeConfigLoad.LOADING
        apply(api.seed(tree.seed()))
        if (error == null) afterLoad(this)
        load = if (error == null) NodeConfigLoad.LOADED else NodeConfigLoad.FAILED
    }

    /** Stores [value] for [node]'s [key] as the user's, or as [setBy] (null = clear it, so it resolves from the parent again). */
    suspend fun set(node: String, key: String, value: JsonElement?, setBy: String = ConfigSetBy.USER): Boolean {
        apply(api.put(NodeConfigEntry(node, key, value, setBy)))
        return error == null
    }

    /** Test and preview hook: takes [rows] as the stored copy without a daemon. */
    fun replace(rows: List<NodeConfigEntry>) {
        stored = rows.groupBy { it.nodeId }.mapValues { (_, r) -> r.associateBy { it.key } }
        load = NodeConfigLoad.LOADED
        error = null
    }

    private fun apply(result: Result<List<NodeConfigEntry>>) {
        result.onSuccess { replace(it) }.onFailure { error = it.message ?: "node configuration failed" }
    }
}
