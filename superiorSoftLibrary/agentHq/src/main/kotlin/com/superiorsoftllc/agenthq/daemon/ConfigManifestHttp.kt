package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.db.AgentHQDatabase
import com.superiorsoftllc.agenthq.plugin.api.ConfigManifest
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val manifestJson = Json { ignoreUnknownKeys = true }

/**
 * CONFIG-3 (docs/CONFIG-MANIFEST-DESIGN.md section 2): the compiled unified config manifest, one reserved `node_config` row per host (node `@manifest`, key = host; no table). The host
 * compiles every loaded module's `config` into one canonical JSON and [put]s it when its hash differs; the row is swapped
 * in one transaction, together with the seed migration of `node_config` ([NodeConfigStore.migrateSeedRows]). User
 * overrides are another table and are never touched by a swap, so a changed default reaches every un-overridden key.
 */
class ConfigManifestStore(private val db: AgentHQDatabase, private val nodeConfig: NodeConfigStore, private val clock: () -> Long = System::currentTimeMillis) {
    private val q = db.node_configQueries

    fun get(host: String): ConfigManifest? = nodeConfig.every()
        .firstOrNull { it.nodeId == NodeConfigStore.MANIFEST_NODE && it.key == host }?.value
        ?.let { runCatching { manifestJson.decodeFromJsonElement(ConfigManifest.serializer(), it) }.getOrNull() }

    /** Replaces [manifest]'s host row and migrates the seed rows against its defaults; answers the stored row. */
    fun put(manifest: ConfigManifest): ConfigManifest {
        val now = clock()
        val stored = manifest.copy(compiledAt = now)
        val value = manifestJson.encodeToJsonElement(ConfigManifest.serializer(), stored).toString()
        db.transaction {
            q.seedNodeConfig(NodeConfigStore.MANIFEST_NODE, manifest.host, value, NodeConfigStore.MANIFEST_SET_BY, now)
            q.updateNodeConfig(value, NodeConfigStore.MANIFEST_SET_BY, now, NodeConfigStore.MANIFEST_NODE, manifest.host)
            nodeConfig.migrateSeedRows(defaultsOf(manifest.json) ?: emptyMap())
        }
        return stored
    }

    companion object {
        /** The manifest JSON is a list of `{module, declaration:{node, fields:[{key, default?}]}}`; null if it is not that shape. */
        fun defaultsOf(json: String): Map<String, Map<String, JsonElement>>? {
            val root = runCatching { manifestJson.parseToJsonElement(json) }.getOrNull() as? JsonArray ?: return null
            val out = LinkedHashMap<String, MutableMap<String, JsonElement>>()
            for (item in root) {
                val decl = (item as? JsonObject)?.get("declaration") as? JsonObject ?: return null
                val node = decl["node"]?.jsonPrimitive?.content ?: return null
                for (f in decl["fields"]?.jsonArray.orEmpty()) {
                    val field = f as? JsonObject ?: return null
                    val key = field["key"]?.jsonPrimitive?.content ?: return null
                    field["default"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.let { out.getOrPut(node) { LinkedHashMap() }.putIfAbsent(key, it) }
                }
            }
            return out
        }
    }
}

/**
 * CONFIG-3: the compiled manifest surface, behind [installAccessPolicy] like every route but `/health`.
 *
 *  - `GET /config-manifest?host=<h>`  the host's row, 404 when it has none.
 *  - `PUT /config-manifest`           one [ConfigManifest]; replaces the host's row; answers the stored row.
 */
fun Route.configManifestRoutes(store: ConfigManifestStore) {
    get("/config-manifest") {
        val host = call.request.queryParameters["host"]
        if (host.isNullOrBlank()) { call.respond(HttpStatusCode.BadRequest, "host is required"); return@get }
        val row = withContext(Dispatchers.IO) { store.get(host) }
        if (row == null) call.respond(HttpStatusCode.NotFound, "no config manifest for $host") else call.respond(row)
    }

    put("/config-manifest") {
        val manifest = runCatching { manifestJson.decodeFromString(ConfigManifest.serializer(), call.receiveText()) }.getOrNull()
        if (manifest == null || manifest.host.isBlank() || manifest.hash.isBlank() || ConfigManifestStore.defaultsOf(manifest.json) == null) {
            call.respond(HttpStatusCode.BadRequest, "malformed config manifest"); return@put
        }
        call.respond(withContext(Dispatchers.IO) { store.put(manifest) })
    }
}

/** CONFIG-8: the daemon's declared config over HTTP for a client without the socket: `GET /config/schema`, `GET /config/values`. */
fun Route.daemonConfigRoutes(config: DaemonConfig) {
    get("/config/schema") { call.respond(config.schema()) }
    get("/config/values") { call.respond(config.values()) }
}
