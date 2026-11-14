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
import java.lang.ref.WeakReference

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
 * PLUGIN-HOTSWAP-1: a loaded set can swap ONE plugin live ([Loaded.swap]): only that module (and its dependents) unloads
 * and loads again, every other module keeps its objects and its UI state (docs/wiki/Plugin-lifecycle.md).
 *
 * This is a trust label, not a sandbox: a loaded jar runs in-process with the app's full access.
 */
object DesktopPlugins {
    /**
     * NOTIFY-CORE-1: the process's ONE way onto the window's notification bus. Every module's [com.superiorsoftllc.infantry.plugin.api.Services.NOTIFICATIONS]
     * posts through it, and so does app code outside the shell (the update notices); the window sets its bus as the target.
     */
    val notifications: NotificationsRelay = NotificationsRelay { print(it) }

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
        /** PLUGIN-HOTSWAP-1: the live swap of this set (see [swap]); null = this set cannot swap. */
        private val swapper: (() -> Swap)? = null,
    ) : AutoCloseable {
        private var closed = false

        /**
         * S7: unloads every module of this set: each module's `stop()` runs, every event observer is unsubscribed, and the
         * class loaders are closed so Windows lets go of the jars (a module can then be removed outside safe mode). Safe to
         * call twice; one module that fails to stop does not keep the others from stopping. PLUGIN-HOTSWAP-1: the set a
         * [swap] answers shares its modules with this one; closing either unloads them all.
         */
        override fun close() { if (!closed) { closed = true; unload() } }

        /**
         * PLUGIN-HOTSWAP-1 (docs/wiki/Plugin-lifecycle.md): reads the plugins root again and applies what changed (a module
         * installed, updated, enabled, disabled or removed) to ONLY the modules it touches and the modules that depend on
         * them: their `stop()` runs, their [com.superiorsoftllc.infantry.plugin.api.PluginScope] closes, their observers
         * leave the bus and their class loaders close; the new versions load into new class loaders with a new
         * [TabFactory.generation]. Every other module keeps its objects, its class loader and its UI state. Answers
         * [Swap.Applied] with the set the window draws from now on and weak references to the closed loaders (the leak
         * check, [com.superiorsoftllc.infantry.plugin.host.LeakWatch]), or [Swap.Unsupported] (nothing unloaded) when only
         * the full reload (the soft restart) can apply the change.
         */
        fun swap(): Swap = if (closed) Swap.Unsupported("the module set is closed") else swapper?.invoke() ?: Swap.Unsupported("this module set cannot swap a plugin live")
    }

    /** PLUGIN-HOTSWAP-1: what [Loaded.swap] did. */
    sealed interface Swap {
        /** Applied live: [next] replaces the set it was asked of. [released]: the closed class loaders, by module, for the leak check. */
        class Applied(
            val next: Loaded,
            val unloaded: List<String>,
            val loaded: List<String>,
            val released: Map<String, WeakReference<ClassLoader>>,
            val message: String,
        ) : Swap

        /** Not applied, nothing unloaded: the host does the full reload (the soft restart) instead, for [reason]. */
        class Unsupported(val reason: String) : Swap
    }

    /** PLUGIN-HOTSWAP-1: one loaded module of a set, with what it takes to swap it. */
    private class Entry(
        val name: String,
        val module: InfantryModule,
        /** Its own class loader; null = compiled in, a bundle's module, or a composition (no code). */
        val loader: PluginClassLoader?,
        /** What its folder looked like when it loaded ([fingerprint]); null = compiled in or bundled (never swapped alone). */
        val fingerprint: String?,
        val generation: Int,
    )

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
        val root = PluginFolders.uiRoot(stateDir, env, property)
        val config = PluginFolders.config(root, say)
        val lockdown = PluginFolders.lockdown(env)
        val safeMode = PluginFolders.safeMode(env, property)
        // S6: an app UI installed from a .ihq carries its modules in one jar; they take part as if compiled in.
        val bundleLoaders = mutableListOf<PluginClassLoader>()
        val withBundles = compiled + bundles(root, config, compiled, say, bundleLoaders)
        val bundled = PluginFolders.bundledRoot(env, property)
        val devTrust = PluginFolders.devTrust(property)
        fun resolve(c: ModulesConfig): Resolution {
            val candidates = OfficialTrust.check(candidates(root, c, withBundles, bundled), OfficialTrust.infantryKeys(root, bundled), devTrust, say, pinned)
            return ModuleResolver.resolve(candidates, c, lockdown, safeMode = safeMode, platform = "desktop")
        }
        say("[plugins] root ${root.absolutePath}${bundled?.let { " (bundled ${it.absolutePath})" } ?: ""}${if (lockdown) " (lockdown on)" else ""}${if (safeMode) " (safe mode: community modules off)" else ""}")
        val resolution = resolve(config)

        var built: TabFactory? = null
        // NOTIFY-CORE-1: ONE relay for every load, so a presenter a module registered survives the factory being rebuilt.
        val relay = notifications
        val bus = SimpleEventBus(say)
        // PLUGIN-HOTSWAP-1: one registry per modules.json read (a live swap reads it again); the same providers each time.
        fun registry(c: ModulesConfig) = ServiceRegistry(c, say).apply {
            provideEvents(bus, say)
            // NOTIFY-CORE-1: the notification bus, stamped with the module's name (the window sets the bus on the relay).
            provide(Services.NOTIFICATIONS) { s -> ModuleNotifications(s.name, relay, say) }
            provide(Services.STORAGE) { s -> FilePluginStorage(dataDir(stateDir, s.name)) }
            provide(Services.SETTINGS) { s -> FilePluginSettings(FilePluginStorage(dataDir(stateDir, s.name))) }
            provide(Services.DAEMON) { s -> RouteGate.gated(s.manifest!!, lockdown, c, DaemonLink.window::send) }
            provide(Services.ENDPOINTS) { s -> JvmEndpointAccess(s.manifest!!, s.tier, c, lockdown, say) }
            speech?.let { sp -> provide(Services.SPEECH_OUT) { com.superiorsoftllc.infantry.ui.voice.SpeakerSpeechOutput(sp) } }
            capture?.let { slot -> provide(Services.AUDIO_IN) { s -> com.superiorsoftllc.infantry.ui.voice.JvmAudioCapture(s.name, slot, captureLimit, captureLines, say) } }
        }

        PluginFaults.sink = say
        val set = ModuleSet(say, bus, withBundles)
        set.load(resolution, registry(config))
        resolution.log.forEach(say)
        val changes = ModuleChanges { request -> change(root, withBundles, lockdown, request, bundled).also { c -> (c as? ModuleChange.Applied)?.log?.forEach(say); say("[plugins] ${c.message}") } }

        // NS3: Registered > "Export section…": <stateDir>/exports/<Section>.ihq, signed with this installation's export key.
        fun exportNode(factory: TabFactory, node: String): Pair<String, CompositionExport.Outcome> {
            val name = node.substringAfterLast('.').replaceFirstChar { it.uppercase() }.filter { it.isLetterOrDigit() }.ifEmpty { "Section" }
            val out = File(File(stateDir, "exports"), "$name.ihq")
            val key = ExportKey.loadOrCreate(File(stateDir, "export-key"))
            return name to CompositionExport.export(factory, node, name, out, CompositionExport.packageDirs(root), key)
        }

        /** The window's factory over the set's modules as [r] decided: the same wiring at load and after a swap. */
        fun factoryOf(r: Resolution, c: ModulesConfig): TabFactory {
            val factory = set.factory(r, changes, c)
            built = factory
            factory.notifications = relay
            factory.sectionExporter = { node ->
                runCatching {
                    when (val e = exportNode(factory, node).second) {
                        is CompositionExport.Outcome.Written -> "Exported to ${e.file.absolutePath}"
                        is CompositionExport.Outcome.Refused -> "Not exported: " + e.refusals.joinToString("; ")
                    }
                }.getOrElse { "Not exported: ${it.message}" }
            }
            // NS10: "Try in a fresh shell": the same export, then a real second process on the shell-only classpath (FreshShell).
            factory.freshShellLauncher = { node ->
                runCatching {
                    val (name, e) = exportNode(factory, node)
                    when (e) {
                        is CompositionExport.Outcome.Written -> FreshShell.launch(stateDir, e.file, name)
                        is CompositionExport.Outcome.Refused -> "Not exported: " + e.refusals.joinToString("; ")
                    }
                }.getOrElse { "Fresh shell not started: ${it.message}" }
            }
            factory.problems.forEach { say("[plugins] $it") }
            return factory
        }

        val unloadAll: () -> Unit = {
            set.close()
            bundleLoaders.forEach { runCatching { it.close() } }
        }
        lateinit var swapper: () -> Swap
        fun loadedSet(f: TabFactory, c: ModulesConfig) = Loaded(f, root, c, lockdown, log, safeMode, unload = unloadAll, services = set::services, swapper = swapper)
        swapper = {
            val c = PluginFolders.config(root, say)
            when (val out = set.swap(resolve(c), registry(c))) {
                is ModuleSet.Outcome.Unsupported -> Swap.Unsupported(out.reason)
                is ModuleSet.Outcome.Done -> {
                    say("[plugins] ${out.message}")
                    Swap.Applied(loadedSet(factoryOf(out.resolution, c), c), out.unloaded, out.loaded, out.released, out.message)
                }
            }
        }
        return loadedSet(factoryOf(set.resolution, config), config)
    }

    /**
     * PLUGIN-HOTSWAP-1: the loaded modules of one window and what it takes to swap one of them: its own class loader, the
     * folder fingerprint it loaded from, and its load generation. [swap] diffs a fresh [Resolution] against what is loaded.
     */
    private class ModuleSet(private val say: (String) -> Unit, private val bus: SimpleEventBus, private val builtIn: List<InfantryModule>) {
        private val entries = LinkedHashMap<String, Entry>()
        private val serviceMap = HashMap<String, com.superiorsoftllc.infantry.plugin.api.PluginServices>()
        private val refused = HashSet<String>()
        private var generations = 0
        private var closed = false
        var resolution: Resolution = Resolution(emptyList(), emptyList(), emptyList())
            private set

        sealed interface Outcome {
            class Done(val resolution: Resolution, val unloaded: List<String>, val loaded: List<String>, val released: Map<String, WeakReference<ClassLoader>>, val message: String) : Outcome
            class Unsupported(val reason: String) : Outcome
        }

        fun services(module: String) = serviceMap[module] ?: NoPluginServices

        private fun builtInOf(s: ModuleStatus) = builtIn.firstOrNull { it.manifest.name == s.name && s.tier == Tier.CORE }

        /** FIRSTRUN-LOG-1: a module with no UI half (daemon/harness only) is normal, not a refusal: it takes no part here. */
        private fun takesPart(s: ModuleStatus) = hasUiHalf(s.manifest!!) || builtInOf(s) != null

        /** Loads every module of [r] (the start), or only [only] of it (a swap), in [r]'s order; refusals go into [resolution]. */
        fun load(r: Resolution, registry: ServiceRegistry, only: Set<String>? = null): List<String> {
            var res = r
            val loadedNow = mutableListOf<String>()
            for (s in r.loaded) {
                if (only != null && s.name !in only) continue
                val svc = registry.forModule(s).also { serviceMap[s.name] = it }
                if (!takesPart(s)) continue
                val needs = s.manifest!!.dependencies.map { it.id }.firstOrNull { it in refused }
                val builtIn = builtInOf(s)
                val result = if (needs != null) Result.failure(IllegalStateException("dependency $needs is refused")) else
                    builtIn?.let { Result.success(it to null) } ?: runCatching { loadJar(s, svc) }
                result.onSuccess { (module, loader) ->
                    entries[s.name] = Entry(s.name, module, loader, if (builtIn == null) fingerprint(s) else null, generations)
                    loadedNow += s.name
                }.onFailure {
                    refused += s.name
                    serviceMap.remove(s.name)
                    val reason = it.message ?: it::class.simpleName.orEmpty()
                    // MASCOT-PICKER-1: the resolution already logged "loaded"; a jar the loader then refuses says so too.
                    say("[plugins] ${s.name} (${s.tier.name.lowercase()}): refused at load - ${it::class.simpleName}: $reason")
                    res = res.refuse(s.name, reason)
                }
            }
            // Keep the resolution's order (a swapped module goes back where it loads).
            val order = res.loaded.map { it.name }.withIndex().associate { it.value to it.index }
            val sorted = entries.values.sortedBy { order[it.name] ?: Int.MAX_VALUE }
            entries.clear(); sorted.forEach { entries[it.name] = it }
            resolution = res
            return loadedNow
        }

        fun factory(r: Resolution, changes: ModuleChanges, c: ModulesConfig): TabFactory {
            val gens = entries.mapValues { it.value.generation }
            return TabFactory(entries.values.map { it.module }, r.preferences.filter { it.module !in refused }, services = ::services, events = bus,
                statuses = statuses(r), changes = changes, shellOverrides = r.shellOverrides.filter { it.module !in refused },
                allowInject = { c.setting(it).allowInject }, allowOverride = { c.setting(it).allowOverride }, modulesConfig = c,
                generation = { gens[it] ?: 0 })
        }

        /** Unloads one module: its stop (and scope), its observers, its class loader; answers a weak reference to the loader. */
        private fun unload(e: Entry): WeakReference<ClassLoader>? {
            runCatching { e.module.onStop() }.onFailure { say("[plugins] ${e.name} failed to stop: ${it.message}") }
            bus.dropOwner(e.name)
            entries.remove(e.name)
            serviceMap.remove(e.name)
            return e.loader?.let { l -> runCatching { l.close() }; WeakReference<ClassLoader>(l) }
        }

        @Synchronized
        fun swap(r: Resolution, registry: ServiceRegistry): Outcome {
            if (closed) return Outcome.Unsupported("the module set is closed")
            val wanted = r.loaded.filter(::takesPart).associateBy { it.name }
            // Compiled-in and bundled modules never swap one by one: a change to one of them needs the full reload.
            val fixed = entries.values.filter { it.fingerprint == null }.map { it.name }.toSet()
            fixed.firstOrNull { it !in wanted }?.let { return Outcome.Unsupported("$it is compiled in or bundled and no longer loads") }
            wanted.values.firstOrNull { it.name !in entries && builtInOf(it) != null }?.let { return Outcome.Unsupported("${it.name} is compiled in or bundled") }
            val changed = (entries.keys + wanted.keys).filter { n ->
                val e = entries[n]
                val w = wanted[n]
                n !in fixed && (e == null || w == null || e.fingerprint != fingerprint(w) || e.module.tier != w.tier)
            }.toMutableSet()
            // A module refused last time is tried again (its dependency may be here now).
            changed += refused.filter { it in wanted }
            if (changed.isEmpty()) return Outcome.Unsupported("nothing changed in the plugins root")
            // A module that needs a changed one goes with it (it may hold the old version's objects through a service).
            var grew = true
            while (grew) {
                grew = false
                val deps = entries.values.map { it.name to it.module.manifest.dependencies } + wanted.values.map { it.name to it.manifest!!.dependencies }
                for ((n, d) in deps) if (n !in changed && d.any { it.id in changed }) { changed += n; grew = true }
            }
            fixed.firstOrNull { it in changed }?.let { return Outcome.Unsupported("$it is compiled in or bundled and depends on a changed module") }
            // Out: the old versions, dependents first (reverse load order).
            val leaving = entries.values.filter { it.name in changed }.reversed()
            val released = LinkedHashMap<String, WeakReference<ClassLoader>>()
            for (e in leaving) unload(e)?.let { released[e.name] = it }
            refused.removeAll { it in changed }
            // In: the new versions, each with a new generation (its old composition is disposed, ModuleScope).
            generations++
            val loadedNow = load(r, registry, only = changed)
            val unloaded = leaving.map { it.name }
            val failed = changed.filter { it in refused }
            val message = buildString {
                append("plugins swapped live")
                if (unloaded.isNotEmpty()) append("; unloaded ${unloaded.joinToString()}")
                if (loadedNow.isNotEmpty()) append("; loaded ${loadedNow.joinToString()}")
                if (failed.isNotEmpty()) append("; refused ${failed.joinToString { "$it (${resolution.status(it)?.reason})" }}")
            }
            return Outcome.Done(resolution, unloaded, loadedNow, released, message)
        }

        @Synchronized
        fun close() {
            if (closed) return
            closed = true
            entries.values.forEach { e -> runCatching { e.module.onStop() }.onFailure { say("[plugins] ${e.name} failed to stop: ${it.message}") } }
            bus.clear()
            entries.values.forEach { e -> e.loader?.let { runCatching { it.close() } } }
        }
    }

    /**
     * PLUGIN-HOTSWAP-1: what a module's folder looked like when it loaded: its module.json, its jar, its `lib/` jars and its
     * layout files (name, size, time). A swap reloads a module whose fingerprint changed (an update, a rebuilt jar).
     */
    internal fun fingerprint(s: ModuleStatus): String {
        val m = s.manifest ?: return "none"
        val dir = s.dir?.let(::File) ?: return "${s.tier}|$m"
        val files = listOfNotNull(File(dir, "module.json"), m.jar?.takeIf { it.isNotBlank() }?.let { File(dir, it) }) +
            File(dir, "lib").listFiles { f -> f.isFile && f.name.endsWith(".jar") }.orEmpty().sortedBy { it.name } +
            m.fills.mapNotNull { f -> f.layout?.let { File(dir, it) } }
        return "${s.tier}|${dir.canonicalPath}|" + files.joinToString(";") { f -> "${f.name}:${if (f.isFile) "${f.length()}@${f.lastModified()}" else "-"}" }
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

    /** A UI half is a UI entry class, a UI jar, or a composition (layout fills / config). */
    internal fun hasUiHalf(m: com.superiorsoftllc.infantry.plugin.api.ModuleManifest): Boolean =
        !m.entry.isNullOrBlank() || !m.jar.isNullOrBlank() || m.fills.any { it.layout != null } || m.config.isNotEmpty()

    /** The module of [s] from its folder, and the class loader it got (null for a composition, which has no code). */
    private fun loadJar(s: ModuleStatus, services: com.superiorsoftllc.infantry.plugin.api.PluginServices): Pair<InfantryModule, PluginClassLoader?> {
        val m = s.manifest!!
        val dir = File(s.dir ?: error("no folder"))
        if (m.entry.isNullOrBlank() && m.jar.isNullOrBlank() && (m.fills.any { it.layout != null } || m.config.isNotEmpty())) return composition(m, s.tier, dir) to null
        val entry = m.entry?.takeIf { it.isNotBlank() } ?: error("module.json has no entry class")
        val jar = File(dir, m.jar?.takeIf { it.isNotBlank() } ?: error("module.json has no jar"))
        if (!jar.isFile) error("jar ${m.jar} not found (build it first)")
        if (!jar.canonicalPath.startsWith(dir.canonicalPath + File.separator)) error("jar ${m.jar} is outside the module folder")
        val loader = PluginClassLoader(dir, jar, DesktopPlugins::class.java.classLoader, s.tier)
        entry.substringBeforeLast('.', "").takeIf { it.isNotEmpty() }?.let { PluginFaults.claim(s.name, it) }
        val plugin = try {
            loader.loadClass(entry).getDeclaredConstructor().newInstance() as? UiPlugin ?: error("$entry is not a UiPlugin")
        } catch (e: ReflectiveOperationException) {
            runCatching { loader.close() }
            error("entry $entry would not load (${e::class.simpleName})")
        } catch (e: LinkageError) {
            runCatching { loader.close() }
            error("entry $entry would not link (${e::class.simpleName}: ${e.message})")
        } catch (e: IllegalStateException) {
            runCatching { loader.close() }
            throw e
        }
        return try {
            registerModule(m, s.tier, services, plugin) to loader
        } catch (e: Throwable) {
            runCatching { loader.close() }
            throw e
        }
    }
}
