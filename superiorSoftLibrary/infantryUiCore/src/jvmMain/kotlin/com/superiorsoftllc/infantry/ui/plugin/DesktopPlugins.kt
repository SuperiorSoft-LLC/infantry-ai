// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin

import com.superiorsoftllc.infantry.plugin.api.ModulesConfig
import com.superiorsoftllc.infantry.plugin.api.Notifications
import com.superiorsoftllc.infantry.plugin.api.Services
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugin.host.DependencyGraph
import com.superiorsoftllc.infantry.plugin.host.FilePluginSettings
import com.superiorsoftllc.infantry.plugin.host.FilePluginStorage
import com.superiorsoftllc.infantry.plugin.host.ModuleCandidate
import com.superiorsoftllc.infantry.plugin.host.ModuleChange
import com.superiorsoftllc.infantry.plugin.host.ModuleResolver
import com.superiorsoftllc.infantry.plugin.host.ModuleState
import com.superiorsoftllc.infantry.plugin.host.ModuleStatus
import com.superiorsoftllc.infantry.plugin.host.ModulesFile
import com.superiorsoftllc.infantry.plugin.host.OfficialTrust
import com.superiorsoftllc.infantry.plugin.host.PluginFolders
import com.superiorsoftllc.infantry.plugin.host.Resolution
import com.superiorsoftllc.infantry.plugin.host.RouteGate
import com.superiorsoftllc.infantry.plugin.host.ServiceRegistry
import com.superiorsoftllc.infantry.plugin.host.SimpleEventBus
import com.superiorsoftllc.infantry.plugin.host.provideEvents

import com.superiorsoftllc.infantry.ui.model.ToastKind
import java.io.File

/**
 * PLUGINS-1: the desktop's module loader. Finds modules only by scanning the configured subfolders of the plugins root
 * (no list anywhere names a plugin), applies the trust rules ([ModuleResolver]), then loads each community module's
 * jar and entry class into this JVM. A jar that will not load refuses that module (and the ones that need it) with a
 * log line; nothing here stops the app. The compiled-in core modules always take part, from disk or not.
 *
 * RUNTIME-2: official modules come from `official/` of the plugins root and of the install's bundled modules root
 * ([PluginFolders.bundledRoot]). Each must be signed by the Infantry key ([OfficialTrust]; `devTrust` skips that in dev)
 * and loads through the same filtered class loader as a community module: no core right.
 *
 * This is a trust label, not a sandbox: a loaded jar runs in-process with the app's full access.
 */
object DesktopPlugins {
    class Loaded(
        val factory: TabFactory,
        val root: File,
        val config: ModulesConfig,
        val lockdown: Boolean,
        val log: List<String>,
        /** S6: started with every community module off (`--safe-mode`). */
        val safeMode: Boolean = false,
        /** S7: unloads this module set (see [close]). */
        private val unload: () -> Unit = {},
        /** The gated services each loaded module gets (what its tabs see as [TabContext.services]). */
        val services: (module: String) -> com.superiorsoftllc.infantry.plugin.api.PluginServices,
    ) : AutoCloseable {
        private var closed = false

        /**
         * S7: unloads every module of this set: each module's `stop()` runs, every event observer is unsubscribed, and the
         * class loaders are closed so Windows lets go of the jars (a module can then be removed outside safe mode). Safe to
         * call twice; one module that fails to stop does not keep the others from stopping.
         */
        override fun close() { if (!closed) { closed = true; unload() } }
    }

    fun load(
        stateDir: File,
        env: (String) -> String? = System::getenv,
        property: (String) -> String? = System::getProperty,
        compiled: List<InfantryModule> = CompiledModules.all,
        print: (String) -> Unit = { System.err.println(it) },
        /** The pinned Infantry key fingerprints ([OfficialTrust.PINNED_FINGERPRINTS]); overridable for tests only. */
        pinned: Set<String> = OfficialTrust.PINNED_FINGERPRINTS,
        /** VOICE-SEAM-1: what backs `speech.out` (the plugin voice engine, else the OS voice); null = no such service. */
        speech: com.superiorsoftllc.infantry.ui.voice.Speaker? = null,
        /** VOICE-SEAM-1: the host's microphone slot behind `audio.in`; null = no such service. */
        capture: com.superiorsoftllc.infantry.ui.voice.CaptureMonitor? = null,
        /** VOICE-SEAM-1: the seconds one capture may run, given the plugin's own request. */
        captureLimit: (requested: Int?) -> Int = { com.superiorsoftllc.infantry.ui.voice.captureLimitSeconds(null, it) },
        captureLines: com.superiorsoftllc.infantry.ui.voice.CaptureLines = com.superiorsoftllc.infantry.ui.voice.JavaSoundLines,
    ): Loaded {
        val log = mutableListOf<String>()
        val say: (String) -> Unit = { log += it; print(it) }
        val root = PluginFolders.root(stateDir, env, property)
        val config = PluginFolders.config(root, say)
        val lockdown = PluginFolders.lockdown(env)
        val safeMode = PluginFolders.safeMode(env, property)
        // S6: an app UI installed from a .ihq carries its modules in one jar; they take part as if compiled in.
        val loaders = mutableListOf<PluginClassLoader>()
        val withBundles = compiled + bundles(root, config, compiled, say, loaders)
        val bundled = PluginFolders.bundledRoot(env, property)
        val devTrust = PluginFolders.devTrust(property)
        val candidates = OfficialTrust.check(candidates(root, config, withBundles, bundled), OfficialTrust.infantryKeys(root, bundled), devTrust, say, pinned)
        say("[plugins] root ${root.absolutePath}${bundled?.let { " (bundled ${it.absolutePath})" } ?: ""}${if (lockdown) " (lockdown on)" else ""}${if (safeMode) " (safe mode: community modules off)" else ""}")
        var resolution = ModuleResolver.resolve(candidates, config, lockdown, safeMode = safeMode, platform = "desktop")

        var built: TabFactory? = null
        val bus = SimpleEventBus(say)
        val registry = ServiceRegistry(config, say).apply {
            provideEvents(bus, say)
            provide(Services.NOTIFICATIONS) { s ->
                Notifications { title, body ->
                    say("[plugins] ${s.name} notifies: $title - $body")
                    // TOAST-1: and the window's toast (a no-op until a window has set its controller's).
                    built?.toasts?.post(body, ToastKind.INFO, title)
                }
            }
            provide(Services.STORAGE) { s -> FilePluginStorage(dataDir(stateDir, s.name)) }
            provide(Services.SETTINGS) { s -> FilePluginSettings(FilePluginStorage(dataDir(stateDir, s.name))) }
            provide(Services.DAEMON) { s -> RouteGate.gated(s.manifest!!, lockdown, config, DaemonLink.window::send) }
            provide(Services.ENDPOINTS) { s -> JvmEndpointAccess(s.manifest!!, s.tier, config, lockdown, say) }
            speech?.let { sp -> provide(Services.SPEECH_OUT) { com.superiorsoftllc.infantry.ui.voice.SpeakerSpeechOutput(sp) } }
            capture?.let { slot -> provide(Services.AUDIO_IN) { s -> com.superiorsoftllc.infantry.ui.voice.JvmAudioCapture(s.name, slot, captureLimit, captureLines, say) } }
        }
        val services = resolution.loaded.associate { it.name to registry.forModule(it) }

        val modules = mutableListOf<InfantryModule>()
        val refused = HashSet<String>()
        for (s in resolution.loaded) {
            val m = s.manifest!!
            val needs = m.dependencies.map { it.id }.firstOrNull { it in refused }
            val result = if (needs != null) Result.failure(IllegalStateException("dependency $needs is refused")) else
                withBundles.firstOrNull { it.manifest.name == s.name && s.tier == Tier.CORE }?.let { Result.success(it) }
                    ?: runCatching { loadJar(s, services.getValue(s.name), loaders) }
            result.onSuccess { modules += it }.onFailure {
                refused += s.name
                resolution = resolution.refuse(s.name, it.message ?: it::class.simpleName.orEmpty())
            }
        }
        resolution.log.forEach(say)
        val preferences = resolution.preferences.filter { it.module !in refused }
        val shellOverrides = resolution.shellOverrides.filter { it.module !in refused }
        val changes = ModuleChanges { request -> change(root, withBundles, lockdown, request, bundled).also { c -> (c as? ModuleChange.Applied)?.log?.forEach(say); say("[plugins] ${c.message}") } }
        val factory = TabFactory(modules, preferences, services = { services[it] ?: NoPluginServices }, events = bus, statuses = statuses(resolution), changes = changes, shellOverrides = shellOverrides,
            allowInject = { config.setting(it).allowInject }, allowOverride = { config.setting(it).allowOverride }, modulesConfig = config)
        built = factory
        // NS3: Registered > "Export section…": <stateDir>/exports/<Section>.ihq, signed with this installation's export key.
        fun exportNode(node: String): Pair<String, CompositionExport.Outcome> {
            val name = node.substringAfterLast('.').replaceFirstChar { it.uppercase() }.filter { it.isLetterOrDigit() }.ifEmpty { "Section" }
            val out = File(File(stateDir, "exports"), "$name.ihq")
            val key = ExportKey.loadOrCreate(File(stateDir, "export-key"))
            return name to CompositionExport.export(factory, node, name, out, CompositionExport.packageDirs(root), key)
        }
        factory.sectionExporter = { node ->
            runCatching {
                when (val r = exportNode(node).second) {
                    is CompositionExport.Outcome.Written -> "Exported to ${r.file.absolutePath}"
                    is CompositionExport.Outcome.Refused -> "Not exported: " + r.refusals.joinToString("; ")
                }
            }.getOrElse { "Not exported: ${it.message}" }
        }
        // NS10: "Try in a fresh shell": the same export, then a real second process on the shell-only classpath (FreshShell).
        factory.freshShellLauncher = { node ->
            runCatching {
                val (name, r) = exportNode(node)
                when (r) {
                    is CompositionExport.Outcome.Written -> FreshShell.launch(stateDir, r.file, name)
                    is CompositionExport.Outcome.Refused -> "Not exported: " + r.refusals.joinToString("; ")
                }
            }.getOrElse { "Fresh shell not started: ${it.message}" }
        }
        factory.problems.forEach { say("[plugins] $it") }
        return Loaded(factory, root, config, lockdown, log, safeMode, unload = {
            modules.forEach { m -> runCatching { m.onStop() }.onFailure { say("[plugins] ${m.manifest.name} failed to stop: ${it.message}") } }
            bus.clear()
            loaders.forEach { runCatching { it.close() } }
        }) { services[it] ?: NoPluginServices }
    }

    /**
     * S6 (drop-in path): the modules of each core-tier folder whose `entry` is a [ModuleSource] rather than a [UiPlugin]: a
     * bundle, such as the app UI (17 modules plus Infantry_AppUi in one signed .ihq). Core tier only (a community module
     * cannot fill the shell's slots anyway), loaded before the rules run so their dependencies resolve as if compiled in.
     * The bundle's own name must be one of its modules. A folder that does not load is left to [loadJar]'s refusal.
     */
    private fun bundles(root: File, config: ModulesConfig, builtIn: List<InfantryModule>, say: (String) -> Unit, loaders: MutableList<PluginClassLoader>): List<InfantryModule> {
        val core = config.folders.filter { it.tier == Tier.CORE }.map { File(root, it.path).canonicalFile }
        val out = mutableListOf<InfantryModule>()
        for (c in PluginFolders.scan(root, config)) {
            val m = c.manifest ?: continue
            val dir = c.dir?.let(::File) ?: continue
            if (c.tier != Tier.CORE || dir.canonicalFile.parentFile !in core || m.entry.isNullOrBlank() || m.jar.isNullOrBlank()) continue
            if (builtIn.any { it.manifest.name == m.name } || out.any { it.manifest.name == m.name }) continue
            val jar = File(dir, m.jar!!)
            if (!jar.isFile || !jar.canonicalPath.startsWith(dir.canonicalPath + File.separator)) continue
            val modules = try {
                val loader = PluginClassLoader(dir, jar, DesktopPlugins::class.java.classLoader).also { loaders += it }
                val entry = loader.loadClass(m.entry).getDeclaredConstructor().newInstance()
                (entry as? ModuleSource ?: continue).modules() // a UiPlugin entry is an ordinary module: loadJar's
            } catch (e: Exception) {
                say("[plugins] ${m.name}: bundle ${m.entry} would not load (${e::class.simpleName}: ${e.message})"); continue
            } catch (e: LinkageError) {
                say("[plugins] ${m.name}: bundle ${m.entry} would not link (${e::class.simpleName}: ${e.message})"); continue
            }
            if (modules.none { it.manifest.name == m.name }) { say("[plugins] ${m.name}: bundle refused: none of its modules is named ${m.name}"); continue }
            val fresh = modules.filter { b -> builtIn.none { it.manifest.name == b.manifest.name } && out.none { it.manifest.name == b.manifest.name } }
            say("[plugins] ${m.name}: bundle of ${fresh.size} modules from ${m.jar}")
            out += fresh
        }
        return out
    }

    /** What is installed: every module folder under the root, plus each compiled-in core module that has none. */
    private fun candidates(root: File, config: ModulesConfig, compiled: List<InfantryModule>, bundled: File? = null): List<ModuleCandidate> {
        val onDisk = PluginFolders.scan(root, config, bundled)
        // A compiled-in core module with no folder on disk (the installed app) still loads, as core.
        return onDisk + compiled.filter { c -> onDisk.none { it.folderName == c.manifest.name } }
            .map { ModuleCandidate(it.manifest.name, Tier.CORE, null, it.manifest) }
    }

    /**
     * PLUGINS-2: Settings asks to enable or disable a module. modules.json is read afresh, the dependency rules decide,
     * and only an allowed change is written (it applies at the next start). Under the demo lock nothing is written.
     */
    fun change(root: File, compiled: List<InfantryModule>, lockdown: Boolean, request: ModuleRequest, bundled: File? = null): ModuleChange {
        if (lockdown) return ModuleChange.Refused("The lockdown is on: modules.json is not changed.")
        val config = PluginFolders.config(root)
        val graph = DependencyGraph.of(candidates(root, config, compiled, bundled), config)
        val result = if (request.enable) graph.enable(request.module, config) else graph.disable(request.module, config, request.cascade)
        if (result is ModuleChange.Applied && result.changed.isNotEmpty()) ModulesFile.write(root, config, result.config)
        return result
    }

    /**
     * NS3: a composition (no code; CONFIG-FIELDS-3: or a config-only module with neither fills nor code): its `fills[].layout` pages read from its folder. The trees are built later, through the
     * block-type registry, by [TabFactory].
     */
    fun composition(m: com.superiorsoftllc.infantry.plugin.api.ModuleManifest, tier: Tier, dir: File): InfantryModule {
        val layouts = m.fills.mapNotNull { f ->
            val path = f.layout ?: return@mapNotNull null
            val file = File(dir, path)
            if (!file.isFile || !file.canonicalPath.startsWith(dir.canonicalPath + File.separator)) error("layout $path of ${f.node} is not in the module folder")
            f.node to (kotlinx.serialization.json.Json.parseToJsonElement(file.readText()) as? kotlinx.serialization.json.JsonObject ?: error("layout $path is not a JSON object"))
        }.toMap()
        return InfantryModule(m, tier = tier, layoutFills = layouts)
    }

    /** Loaded first, in load order, then the rest by name: the order Settings > Plugins lists them. */
    private fun statuses(r: Resolution): List<ModuleStatus> =
        r.statuses.filter { it.state == ModuleState.LOADED } + r.statuses.filter { it.state != ModuleState.LOADED }.sortedBy { it.name }

    /** A module's private data area: `<stateDir>/plugin-data/<Vendor_Module>`. */
    fun dataDir(stateDir: File, module: String) = File(File(stateDir, "plugin-data"), module)

    private fun loadJar(s: ModuleStatus, services: com.superiorsoftllc.infantry.plugin.api.PluginServices, loaders: MutableList<PluginClassLoader>): InfantryModule {
        val m = s.manifest!!
        val dir = File(s.dir ?: error("no folder"))
        if (m.entry.isNullOrBlank() && m.jar.isNullOrBlank() && (m.fills.any { it.layout != null } || m.config.isNotEmpty())) return composition(m, s.tier, dir)
        val entry = m.entry?.takeIf { it.isNotBlank() } ?: error("module.json has no entry class")
        val jar = File(dir, m.jar?.takeIf { it.isNotBlank() } ?: error("module.json has no jar"))
        if (!jar.isFile) error("jar ${m.jar} not found (build it first)")
        if (!jar.canonicalPath.startsWith(dir.canonicalPath + File.separator)) error("jar ${m.jar} is outside the module folder")
        val loader = PluginClassLoader(dir, jar, DesktopPlugins::class.java.classLoader, s.tier).also { loaders += it }
        val plugin = try {
            loader.loadClass(entry).getDeclaredConstructor().newInstance() as? UiPlugin ?: error("$entry is not a UiPlugin")
        } catch (e: ReflectiveOperationException) {
            error("entry $entry would not load (${e::class.simpleName})")
        } catch (e: LinkageError) {
            error("entry $entry would not link (${e::class.simpleName}: ${e.message})")
        }
        return registerModule(m, s.tier, services, plugin)
    }
}
