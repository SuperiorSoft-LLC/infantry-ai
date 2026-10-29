package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.db.AgentHQDatabase
import com.superiorsoftllc.agenthq.plugin.api.ConfigSetBy
import com.superiorsoftllc.agenthq.plugin.api.NodeConfigEntry
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private val nodeConfigJson = Json { ignoreUnknownKeys = true }

/**
 * P1 step 2 (docs/BLOCKS-LAYOUT-DESIGN.md 6s B): the `node_config` table. The window's merged JSON tree [seed]s it (a
 * key already there is never overwritten); after that the table is the live copy, and [put] is the view's edit.
 */
class NodeConfigStore(private val db: AgentHQDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val q = db.node_configQueries

    fun all(): List<NodeConfigEntry> = every().filter { it.nodeId != MANIFEST_NODE }

    /** Every row, the reserved [MANIFEST_NODE] ones too. */
    internal fun every(): List<NodeConfigEntry> = q.allNodeConfig().executeAsList().map {
        NodeConfigEntry(it.node_id, it.key, runCatching { nodeConfigJson.parseToJsonElement(it.value_json) }.getOrNull(), it.set_by, it.updated_at)
    }

    companion object {
        /** CONFIG-3: the reserved node id whose rows hold the compiled manifests (key = host); never listed, never a setting. */
        const val MANIFEST_NODE = "@manifest"
        /** The `set_by` of a manifest row, so the seed migration cannot touch it. */
        const val MANIFEST_SET_BY = "manifest"
    }

    /** Inserts each entry with a value whose key is not stored yet, as [ConfigSetBy.SEED]. */
    fun seed(entries: List<NodeConfigEntry>) {
        val now = clock()
        db.transaction { for (e in entries) e.value?.let { q.seedNodeConfig(e.nodeId, e.key, it.toString(), ConfigSetBy.SEED, now) } }
    }

    /**
     * CONFIG-3 seed migration (docs/CONFIG-MANIFEST-DESIGN.md section 2): deletes each `seed` row whose value equals the
     * default [defaults] (node id -> key -> JSON) declares, because a default is the manifest's, never a stored row; a seed
     * row left over differs from the default, so somebody's value: it becomes a user row. User rows are never touched.
     */
    fun migrateSeedRows(defaults: Map<String, Map<String, JsonElement>>) {
        db.transaction {
            for (e in all()) if (e.nodeId != MANIFEST_NODE && e.setBy == ConfigSetBy.SEED && e.value != null && defaults[e.nodeId]?.get(e.key) == e.value) q.deleteSeedNodeConfig(e.nodeId, e.key)
            q.adoptSeedNodeConfig()
        }
    }

    /** Writes [entry]'s value (insert or overwrite); a null value removes the row, so the key resolves from the parent again. */
    fun put(entry: NodeConfigEntry) {
        val now = clock()
        val value: JsonElement? = entry.value
        db.transaction {
            if (value == null) q.deleteNodeConfig(entry.nodeId, entry.key)
            else {
                q.seedNodeConfig(entry.nodeId, entry.key, value.toString(), entry.setBy, now)
                q.updateNodeConfig(value.toString(), entry.setBy, now, entry.nodeId, entry.key)
            }
        }
    }
}

/**
 * P1 step 2: the node config surface, behind [installAccessPolicy] like every route but `/health`.
 *
 *  - `GET  /node-config`       every stored row.
 *  - `POST /node-config/seed`  CONFIG-3: a no-op kept for old clients (defaults live in `config_manifest`, not as rows); answers every row.
 *  - `PUT  /node-config`       one [NodeConfigEntry]; writes it (null value = remove); answers every row.
 */
fun Route.nodeConfigRoutes(store: NodeConfigStore) {
    val list = ListSerializer(NodeConfigEntry.serializer())
    get("/node-config") { call.respond(withContext(Dispatchers.IO) { store.all() }) }

    post("/node-config/seed") {
        val entries = runCatching { nodeConfigJson.decodeFromString(list, call.receiveText()) }.getOrNull()
        if (entries == null) { call.respond(HttpStatusCode.BadRequest, "malformed node config seed"); return@post }
        call.respond(withContext(Dispatchers.IO) { store.all() })
    }

    put("/node-config") {
        val entry = runCatching { nodeConfigJson.decodeFromString(NodeConfigEntry.serializer(), call.receiveText()) }.getOrNull()
        if (entry == null || entry.nodeId.isBlank() || entry.key.isBlank() || entry.nodeId == NodeConfigStore.MANIFEST_NODE) { call.respond(HttpStatusCode.BadRequest, "malformed node config entry"); return@put }
        call.respond(withContext(Dispatchers.IO) { store.put(entry); store.all() })
    }
}
