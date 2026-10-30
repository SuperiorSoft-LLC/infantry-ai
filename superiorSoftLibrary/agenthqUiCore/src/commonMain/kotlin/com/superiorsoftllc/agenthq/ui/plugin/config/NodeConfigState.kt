package com.superiorsoftllc.agenthq.ui.plugin.config

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.superiorsoftllc.agenthq.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.agenthq.plugin.api.ConfigManifest
import com.superiorsoftllc.agenthq.plugin.api.ConfigSchemaFrame
import com.superiorsoftllc.agenthq.plugin.api.ConfigValuesFrame
import com.superiorsoftllc.agenthq.plugin.api.ConfigManifestSource
import com.superiorsoftllc.agenthq.plugin.api.ConfigScopes
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
    /** CONFIG-3: the manifest the daemon holds for [host], or null when it has none (or is older than the table). */
    suspend fun manifest(host: String): Result<ConfigManifest?> = Result.success(null)
    /** CONFIG-3: replaces the daemon's manifest row for [ConfigManifest.host]. */
    suspend fun putManifest(manifest: ConfigManifest): Result<ConfigManifest?> = Result.success(null)
}

/** [NodeConfigApi] over [DaemonLink], the window's own daemon (the bearer it connected with). */
object DaemonNodeConfigApi : NodeConfigApi {
    private val json = Json { ignoreUnknownKeys = true }
    private val list = ListSerializer(NodeConfigEntry.serializer())

    override suspend fun seed(entries: List<NodeConfigEntry>) = rows(DaemonLink.window.send("POST /node-config/seed", json.encodeToString(list, entries)))
    override suspend fun put(entry: NodeConfigEntry) = rows(DaemonLink.window.send("PUT /node-config", json.encodeToString(NodeConfigEntry.serializer(), entry)))

    override suspend fun manifest(host: String): Result<ConfigManifest?> = one(DaemonLink.window.send("GET /config-manifest?host=$host", ""))
    override suspend fun putManifest(manifest: ConfigManifest): Result<ConfigManifest?> =
        one(DaemonLink.window.send("PUT /config-manifest", json.encodeToString(ConfigManifest.serializer(), manifest)))

    private fun one(reply: DaemonReply): Result<ConfigManifest?> = when (reply) {
        is DaemonReply.Ok -> when {
            reply.status == 404 -> Result.success(null)
            reply.status in 200..299 -> runCatching { json.decodeFromString(ConfigManifest.serializer(), reply.body) }
            else -> Result.failure(IllegalStateException("the daemon answered ${reply.status}"))
        }
        is DaemonReply.Refused -> Result.failure(IllegalStateException(reply.reason))
        is DaemonReply.Failed -> Result.failure(IllegalStateException(reply.reason))
    }

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
    /** CONFIG-3: which host's manifest row this window compiles (`desktop`, or `compiled` for a wasm / Android module list). */
    private val host: String = "desktop",
    private val sources: List<ConfigManifestSource> = emptyList(),
    /** Runs after each successful [load] (6s D: a new implementation may become a field's selection). */
    private val afterLoad: suspend (NodeConfigState) -> Unit = {},
) {
    /** The stored rows, node id -> key -> row. */
    var stored: Map<String, Map<String, NodeConfigEntry>> by mutableStateOf(emptyMap())
        private set
    var load: NodeConfigLoad by mutableStateOf(NodeConfigLoad.IDLE)
        private set
    /**
     * CONFIG-3: the declarations of the compiled unified manifest the daemon holds for [host] (what the Settings dialog
     * generates tabs from). Null until a load has published and read it back, and whenever the daemon has none: the
     * dialog then uses [tree] (the same declarations, built locally), so a bare shell and an older daemon behave as before.
     */
    var snapshot: List<ModuleDeclaration>? by mutableStateOf(null)
        private set
    /** The declarations to build the dialog from: the daemon's snapshot, else the local [tree]'s, then the daemon's own ([daemonDecls]). */
    val declarations: List<ModuleDeclaration> get() = (snapshot ?: tree.declarations) + daemonDecls
    /** CONFIG-11: the snapshot with the daemon's own sections after it; null while there is no snapshot (the dialog then uses [declarations]). */
    val planDeclarations: List<ModuleDeclaration>? get() = snapshot?.let { it + daemonDecls }

    /** CONFIG-11: the daemon's `config_schema` sections (scope `daemon`), empty below contract 1.142.0 or before the daemon answered. */
    var daemonDecls: List<ModuleDeclaration> by mutableStateOf(emptyList())
        private set
    /** CONFIG-11: the daemon's `config_values`, node id -> key -> value. */
    private var daemonStored: Map<String, Map<String, JsonElement>> by mutableStateOf(emptyMap())
    /** CONFIG-11: the daemon's last refusal of a write (stale rev, validator text), shown inline on the daemon cards. */
    var daemonError: String? by mutableStateOf(null)
        private set
    private var daemonWriter: (suspend (node: String, key: String, value: JsonElement?) -> Boolean)? = null
    private val daemonFields: Map<Pair<String, String>, ConfigFieldDeclaration>
        get() = daemonDecls.flatMap { m -> m.declaration.fields.map { (m.declaration.node to it.key) to it } }.toMap()

    /**
     * CONFIG-11: takes the daemon's sections and values from the connection ([schema] null = the daemon does not serve
     * config: the hand-written sections stay). A secret is never a field; a section the daemon marks read-only (unsigned
     * plugin) stays read-only.
     */
    fun applyDaemon(
        schema: ConfigSchemaFrame?,
        values: ConfigValuesFrame?,
        error: String?,
        writer: (suspend (node: String, key: String, value: JsonElement?) -> Boolean)?,
    ) {
        daemonWriter = writer
        daemonError = error
        daemonDecls = schema?.declarations.orEmpty().map { d ->
            ModuleDeclaration(d.node.removePrefix("daemon."), d.copy(
                tab = d.tab ?: "General",
                fields = d.fields.filterNot { it.type == "secret" || it.type == "password" },
            ))
        }.filter { it.declaration.fields.isNotEmpty() || it.declaration.block != null }
        daemonStored = values?.rows.orEmpty().filter { it.value != null && it.value !is kotlinx.serialization.json.JsonNull }
            .groupBy({ it.nodeId }) { it.key to it.value!! }.mapValues { (_, l) -> l.toMap() }
    }

    /** True when [node]'s fields come from the daemon's schema. */
    fun isDaemonNode(node: String): Boolean = daemonDecls.any { it.declaration.node == node }

    /** The daemon field's value (stored, else its declared default), or null when [node]/[key] is not a daemon field. */
    fun daemonJson(node: String, key: String): JsonElement? {
        val f = daemonFields[node to key] ?: return null
        return daemonStored[node]?.get(key) ?: f.default?.takeIf { it !is kotlinx.serialization.json.JsonNull }
    }
    /** The last failure, one line; null after a success. */
    var error: String? by mutableStateOf(null)
        private set

    /**
     * CONFIG-4: the `scope:"device"` overrides read from the host's prefs, node id -> key -> value. A device field's
     * daemon row is ignored; [ResolvedConfig] layers this over the parent chain and under nothing else for that key.
     */
    var device: Map<String, Map<String, JsonElement>> by mutableStateOf(emptyMap())
        private set
    private var devicePrefs: DevicePrefs? = null

    /** CONFIG-4: binds the host's prefs, runs the one-time migration of the old pref keys, and reads every device field. */
    fun attachDevice(prefs: DevicePrefs) {
        devicePrefs = prefs
        DeviceScope.migrate(prefs)
        device = tree.fields.values.flatten().filter { it.field.scope == ConfigScopes.DEVICE }.mapNotNull { f ->
            DeviceScope.decode(prefs.get(DeviceScope.prefKey(f.node, f.key)))?.let { Triple(f.node, f.key, it) }
        }.groupBy({ it.first }) { it.second to it.third }.mapValues { (_, l) -> l.toMap() }
    }

    /** True when [key] resolved for [node] is a device-scope field. */
    fun isDevice(node: String, key: String): Boolean = tree.field(node, key)?.field?.scope == ConfigScopes.DEVICE

    /** The row stored for [node] itself (no inheritance), or null. */
    fun own(node: String, key: String): NodeConfigEntry? =
        daemonStored[node]?.get(key)?.takeIf { daemonFields.containsKey(node to key) }?.let { NodeConfigEntry(node, key, it, ConfigSetBy.USER) } ?: stored[node]?.get(key)

    /**
     * DEMO-FIX-1: the daemon's policy is `restricted` (a read-only lock): its node-config rows are not offered and the seed
     * POST is refused (403), so [load] makes no call and everything resolves to the declared defaults.
     */
    var daemonRowsClosed: Boolean = false

    suspend fun load() {
        if (daemonRowsClosed) { replace(emptyList()); return }
        load = NodeConfigLoad.LOADING
        apply(api.seed(tree.seed()))
        if (error == null) { publishManifest(); afterLoad(this) }
        load = if (error == null) NodeConfigLoad.LOADED else NodeConfigLoad.FAILED
    }

    /**
     * CONFIG-3: compiles the tree, and when the daemon's row for [host] has another hash replaces it (the daemon swaps the
     * row in one transaction and drops seed rows equal to a default). The snapshot is what the daemon answers; any failure
     * leaves it null and the local tree authoritative for the session. Never an error of the load.
     */
    private suspend fun publishManifest() {
        val local = ConfigManifests.compile(tree, host, sources)
        val held = api.manifest(host).getOrNull()
        val stored = if (held?.hash == local.hash) held else api.putManifest(local).getOrNull()
        snapshot = stored?.let(ConfigManifests::parse)
    }

    /** Stores [value] for [node]'s [key] as the user's, or as [setBy] (null = clear it, so it resolves from the parent again). */
    suspend fun set(node: String, key: String, value: JsonElement?, setBy: String = ConfigSetBy.USER): Boolean {
        // CONFIG-11: a daemon-scoped field is written through config_set; the daemon's answer comes back as config_values / config_changed.
        if (daemonFields.containsKey(node to key)) return daemonWriter?.invoke(node, key, value) ?: false
        val prefs = devicePrefs
        if (prefs != null && isDevice(node, key)) {
            // The store cannot delete: null is written as "" and reads back as unset.
            prefs.put(DeviceScope.prefKey(node, key), value?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.let(DeviceScope::encode) ?: "")
            val own = device[node].orEmpty().toMutableMap()
            if (value == null || value is kotlinx.serialization.json.JsonNull) own.remove(key) else own[key] = value
            device = device + (node to own)
            return true
        }
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
