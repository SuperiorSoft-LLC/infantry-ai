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

    fun all(): List<NodeConfigEntry> = q.allNodeConfig().executeAsList().map {
        NodeConfigEntry(it.node_id, it.key, runCatching { nodeConfigJson.parseToJsonElement(it.value_json) }.getOrNull(), it.set_by, it.updated_at)
    }

    /** Inserts each entry with a value whose key is not stored yet, as [ConfigSetBy.SEED]. */
    fun seed(entries: List<NodeConfigEntry>) {
        val now = clock()
        db.transaction { for (e in entries) e.value?.let { q.seedNodeConfig(e.nodeId, e.key, it.toString(), ConfigSetBy.SEED, now) } }
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
 *  - `POST /node-config/seed`  a list of [NodeConfigEntry]; inserts only the keys not stored yet; answers every row.
 *  - `PUT  /node-config`       one [NodeConfigEntry]; writes it (null value = remove); answers every row.
 */
fun Route.nodeConfigRoutes(store: NodeConfigStore) {
    val list = ListSerializer(NodeConfigEntry.serializer())
    get("/node-config") { call.respond(withContext(Dispatchers.IO) { store.all() }) }

    post("/node-config/seed") {
        val entries = runCatching { nodeConfigJson.decodeFromString(list, call.receiveText()) }.getOrNull()
        if (entries == null) { call.respond(HttpStatusCode.BadRequest, "malformed node config seed"); return@post }
        call.respond(withContext(Dispatchers.IO) { store.seed(entries); store.all() })
    }

    put("/node-config") {
        val entry = runCatching { nodeConfigJson.decodeFromString(NodeConfigEntry.serializer(), call.receiveText()) }.getOrNull()
        if (entry == null || entry.nodeId.isBlank() || entry.key.isBlank()) { call.respond(HttpStatusCode.BadRequest, "malformed node config entry"); return@put }
        call.respond(withContext(Dispatchers.IO) { store.put(entry); store.all() })
    }
}
