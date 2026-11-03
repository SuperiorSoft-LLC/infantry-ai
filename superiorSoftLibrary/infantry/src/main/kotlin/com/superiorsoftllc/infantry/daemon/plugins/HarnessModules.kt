package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.harness.ApiHarnessPluginBridge
import com.superiorsoftllc.infantry.harness.HarnessApiClassLoader
import com.superiorsoftllc.infantry.harness.HarnessApiVersion
import com.superiorsoftllc.infantry.harness.HarnessHostDeclaration
import com.superiorsoftllc.infantry.harness.HarnessHostServices
import com.superiorsoftllc.infantry.harness.HarnessRegistry
import com.superiorsoftllc.infantry.harness.JarHarnessPluginSource
import com.superiorsoftllc.infantry.harness.ModuleHarnessHost
import com.superiorsoftllc.infantry.harness.PluginFailure
import com.superiorsoftllc.infantry.harness.PluginOutcome
import com.superiorsoftllc.infantry.harness.UnloadOutcome
import com.superiorsoftllc.infantry.executables.CliTool
import com.superiorsoftllc.infantry.harness.install.InstallableHarness
import com.superiorsoftllc.infantry.harness.install.ManifestHarnessCli
import com.superiorsoftllc.infantry.plugin.api.HarnessDeclaration
import com.superiorsoftllc.infantry.plugin.api.ModuleSetting
import com.superiorsoftllc.infantry.plugin.api.ModulesConfig
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugin.host.ModuleCandidate
import com.superiorsoftllc.infantry.plugin.host.ModuleStatus
import com.superiorsoftllc.infantry.plugin.host.OfficialTrust
import com.superiorsoftllc.infantry.plugin.host.Resolution
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * HARNESS-PLUGIN-3 H4 (docs/HARNESS-PLUGINS.md sections 3.2 and 4): loads the harness of every module the resolver let
 * through ([Resolution.loaded]) whose module.json has a `harness` block, into the daemon's ONE [HarnessRegistry], beside the
 * built-ins. Trust is the daemon halves' ([DaemonPluginHost.Trust]): bundled and devTrust load, an official jar must pass
 * [OfficialTrust], a community module needs `harness.code` plus a grant for each declared hook, setting and its CLI, and may
 * not take an id another source holds (or [reserved], the configured providers). Every rule is checked BEFORE the jar is
 * opened; a failure refuses that module only (default deny, fail closed), never the daemon.
 *
 * [sync] is idempotent: a module newly loaded is loaded, a module no longer loaded (disabled, removed, refused by a policy
 * change) is unloaded and its class loader closed. Sessions already running keep the adapter they hold; the next spawn of an
 * unloaded id is "not installed".
 */
class HarnessModules(
    private val registry: HarnessRegistry,
    private val trust: DaemonPluginHost.Trust,
    private val services: HarnessHostServices,
    private val stateDir: File,
    private val reserved: () -> Set<String> = { emptySet() },
    private val log: (String) -> Unit = { System.err.println(it) },
) : AutoCloseable {

    private class Loaded(val module: String, val pluginId: String, val ids: List<String>, val aliases: Map<String, String>, val source: JarHarnessPluginSource, val declaration: HarnessDeclaration)

    private val lock = Any()
    private val loaded = LinkedHashMap<String, Loaded>()
    private val statuses = ConcurrentHashMap<String, String>()

    /** `loaded | refused: why | failed: why` for a module with a harness block; null for any other module. */
    fun status(module: String): String? = statuses[module]

    /** The harness ids [module] registered (empty when it is not loaded). */
    fun idsOf(module: String): List<String> = synchronized(lock) { loaded[module]?.ids.orEmpty() }

    /** Every loaded module's `harness.aliases`, for the session manager's id lookup. */
    fun alias(name: String): String? = synchronized(lock) { loaded.values.firstNotNullOfOrNull { it.aliases[name] } }

    /**
     * HARNESS-PLUGIN-4 H5a: the `harness.cli` tool of every LOADED module (a refused or ungranted module has none), for the
     * resolver, Settings > CLI locations and [ModuleHarnessHost.resolveCli].
     */
    fun cliTools(): List<CliTool> = synchronized(lock) { loaded.values.mapNotNull { it.declaration.cli } }.map(ManifestHarnessCli::tool)

    /** The tool of the loaded module whose `harness.cli.id` is [cliId], or null. */
    fun cliTool(cliId: String): CliTool? = cliTools().firstOrNull { it.id == cliId }

    /** H5a: what the harness installer may install for the loaded modules: each `harness.cli` with an npm `install`. */
    fun installables(): List<InstallableHarness> =
        synchronized(lock) { loaded.values.mapNotNull { it.declaration.cli } }.mapNotNull(ManifestHarnessCli::installable)

    /** Bring the registry in line with [resolution]: load what is newly loaded, unload what is not loaded any more. */
    fun sync(resolution: Resolution, config: ModulesConfig) {
        val wanted = resolution.loaded.filter { it.manifest?.harness != null }
        val wantedNames = wanted.map { it.name }.toSet()
        synchronized(lock) { loaded.keys.filter { it !in wantedNames } }.forEach { unload(it, "no longer loaded") }
        resolution.statuses.filter { it.manifest?.harness != null && it.name !in wantedNames }.forEach { s ->
            if (synchronized(lock) { s.name !in loaded }) statuses[s.name] = "refused: module ${s.state.name.lowercase()}${s.reason?.let { ": $it" }.orEmpty()}"
        }
        // Trusted tiers first, so a community module can never take an id before the official module that owns it loads.
        wanted.sortedBy { if (it.tier == Tier.COMMUNITY) 1 else 0 }.forEach { s ->
            if (synchronized(lock) { s.name in loaded }) return@forEach
            runCatching { load(s, config) }.onFailure { refuse(s.name, it.message ?: it::class.simpleName.orEmpty()) }
        }
    }

    /** Remove [module]'s harnesses from the registry, let the plugin release, close its class loader. */
    fun unload(module: String, why: String = "unloaded") {
        val l = synchronized(lock) { loaded.remove(module) } ?: return
        when (val o = registry.unload(l.pluginId)) {
            is UnloadOutcome.UnloadFailed -> log("[harness] $module unload failed (removed anyway): ${o.failure.message}")
            else -> Unit
        }
        l.source.close()
        statuses[module] = "refused: $why"
        log("[harness] $module unloaded ($why): ${l.ids}")
    }

    override fun close() {
        synchronized(lock) { loaded.keys.toList() }.forEach { unload(it, "daemon stopping") }
    }

    private fun refuse(module: String, why: String) {
        statuses[module] = "refused: $why"
        log("[harness] harness of $module refused: $why")
    }

    private fun load(status: ModuleStatus, config: ModulesConfig) {
        val m = status.manifest ?: return
        val h = m.harness ?: return
        val id = m.name
        val setting = config.setting(id)
        val dir = File(status.dir ?: return refuse(id, "no folder"))
        refusal(status.tier, h, setting)?.let { return refuse(id, it) }
        val jarPath = h.jar!!
        val jar = File(dir, jarPath)
        if (!jar.canonicalPath.startsWith(dir.canonicalPath + File.separator)) return refuse(id, "harness.jar $jarPath is outside the module folder")
        if (!jar.isFile) return refuse(id, "jar missing: harness.jar $jarPath is not in ${dir.path} (build it; a package ships it)")
        if (status.tier == Tier.OFFICIAL && !trust.trusts(dir)) {
            OfficialTrust.refusal(ModuleCandidate(dir.name, status.tier, dir.absolutePath, m), jarPath, trust.keys)?.let { return refuse(id, "official harness jar refused: $it") }
        }
        val community = status.tier == Tier.COMMUNITY
        val allow = setting.allow.toSet()
        val host = ModuleHarnessHost(
            id, stateDir,
            HarnessHostDeclaration(h.settings, h.cli?.id, h.cli?.command, h.hooks.toSet()),
            granted = { grant -> !community || grant in allow },
            services = services, logSink = log,
        )
        val onFailure: (PluginFailure) -> Unit = { log("[harness] $id: ${it.message}") }
        val source = JarHarnessPluginSource(
            { listOf(jar) }, HarnessApiClassLoader(HarnessModules::class.java.classLoader),
            bridge = { ApiHarnessPluginBridge(it, host, services.runner, onFailure, declaredIds = h.ids.toSet()) },
        )
        fun fail(why: String) { source.close(); statuses[id] = "failed: $why"; log("[harness] harness of $id failed: $why") }
        val candidates = source.candidates()
        if (candidates.size != 1) {
            source.close()
            return refuse(id, "harness.jar must list exactly one harness API plugin, it lists ${candidates.size}")
        }
        val plugin = try {
            candidates.single().create()
        } catch (t: Throwable) {
            return fail("${t::class.simpleName}: ${t.message}")
        }
        if (plugin !is ApiHarnessPluginBridge) { source.close(); return refuse(id, "harness.jar holds no harness API 2.x plugin") }
        val pluginId = runCatching { plugin.pluginId }.getOrElse { return fail("pluginId threw ${it::class.simpleName}: ${it.message}") }
        if (pluginId != id) { source.close(); return refuse(id, "the plugin's pluginId '${pluginId.take(60)}' is not the module name") }
        val declared = runCatching { plugin.contractVersion }.getOrNull()
        if (declared != h.contract) { source.close(); return refuse(id, "the plugin implements harness API '${declared?.take(40)}' but module.json harness.contract says '${h.contract}'") }
        when (val o = registry.register(plugin, "module:$id")) {
            is PluginOutcome.Accepted -> {
                synchronized(lock) { loaded[id] = Loaded(id, pluginId, o.harnessIds, h.aliases, source, h) }
                statuses[id] = "loaded"
                log("[harness] harness of $id (${status.tier.name.lowercase()}) loaded: ${o.harnessIds}")
            }
            is PluginOutcome.Refused -> { source.close(); refuse(id, o.detail) }
            is PluginOutcome.Failed -> fail(o.failure.message)
        }
    }

    /** Why [h] may not load for [tier] and its grants; null = it may. Checked before the jar is opened. */
    private fun refusal(tier: Tier, h: HarnessDeclaration, setting: ModuleSetting): String? {
        if (h.jar.isNullOrBlank()) return "a harness block without harness.jar"
        val contract = h.contract?.takeIf { it.isNotBlank() } ?: return "a harness block without harness.contract (the harness API version, e.g. 2.0.0)"
        HarnessApiVersion.refusal(contract)?.let { return "harness.contract: $it" }
        if (h.ids.isEmpty()) return "a harness block without harness.ids"
        h.ids.firstOrNull { !ID.matches(it) }?.let { return "harness id '${it.take(40)}' must match ${ID.pattern}" }
        if (h.ids.toSet().size != h.ids.size) return "harness.ids lists an id twice"
        h.hooks.firstOrNull { it !in ModuleHarnessHost.HOOK_KINDS }?.let { return "unknown harness hook '${it.take(40)}' (known: ${ModuleHarnessHost.HOOK_KINDS.joinToString()})" }
        h.aliases.entries.firstOrNull { (a, target) -> target !in h.ids || !ID.matches(a) || a in h.ids }?.let { return "harness.aliases '${it.key.take(40)}' must name one of harness.ids and not be one" }
        h.cli?.let { cli ->
            ManifestHarnessCli.refusal(cli, allowBuiltIn = tier != Tier.COMMUNITY && cli.id in h.ids)?.let { return it }
            if (synchronized(lock) { loaded.values.any { it.declaration.cli?.id == cli.id } }) return "harness.cli.id '${cli.id}' is already declared by another loaded harness module"
        }
        val taken = registry.harnessIds().toSet() + reserved()
        if (tier != Tier.COMMUNITY) return null
        val allow = setting.allow.toSet()
        if ("harness.code" !in allow) return "community harness code needs the 'harness.code' grant in modules.json"
        (h.ids + h.aliases.keys).firstOrNull { it in taken }?.let { return "community harness id '$it' is already taken (a community harness may not shadow an installed one)" }
        val needed = buildList {
            if (h.cli != null) add(ModuleHarnessHost.CLI_GRANT)
            // H5a: an npm install runs the package's install scripts on this machine, a right of its own.
            if (h.cli?.install?.npm != null) add(CLI_INSTALL_GRANT)
            h.hooks.forEach { add("harness.hook.$it") }
            h.settings.forEach { add("harness.setting:$it") }
        }
        needed.firstOrNull { it !in allow }?.let { return "missing grant '$it' in modules.json" }
        return null
    }

    companion object {
        val ID = Regex("[a-z0-9][a-z0-9-]{0,39}")

        /** H5a: a community module's `harness.cli.install` needs this grant (beside `harness.cli`). */
        const val CLI_INSTALL_GRANT = "harness.cli.install"
    }
}
