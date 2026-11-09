// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.harness

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

/** Which step of a plugin's life the plugin's own code threw in. */
enum class PluginFailurePhase { DISCOVER, INSTANTIATE, REGISTER, UNLOAD, RUN }

/** Why the daemon declined a plugin WITHOUT the plugin having done anything wrong at run time. */
enum class RefusalReason { VERSION_MISMATCH, DUPLICATE_PLUGIN, DUPLICATE_HARNESS_ID, INVALID_ID }

/** Plugin code threw. [plugin] is the plugin id when it was readable, otherwise the discovery label. */
data class PluginFailure(val plugin: String, val phase: PluginFailurePhase, val cause: Throwable) {
    val message: String
        get() = "plugin '$plugin' failed during ${phase.name.lowercase()}: ${cause::class.simpleName}: ${cause.message}"
}

/** Thrown to the collector of [HarnessRegistry.run] when the harness of a plugin throws mid-run. */
class PluginFailureException(val failure: PluginFailure) : RuntimeException(failure.message, failure.cause)

/** No loaded plugin registered a harness under [harnessId]. There is no default and no built-in list to fall back on. */
class UnknownHarnessException(val harnessId: String, val registered: List<String>) :
    NoSuchElementException("no harness registered under id '$harnessId' (registered: $registered)")

sealed interface PluginOutcome {
    data class Accepted(val pluginId: String, val harnessIds: List<String>) : PluginOutcome
    data class Refused(val plugin: String, val reason: RefusalReason, val detail: String) : PluginOutcome
    data class Failed(val failure: PluginFailure) : PluginOutcome
}

data class DiscoveryReport(val outcomes: List<PluginOutcome>) {
    val accepted: List<PluginOutcome.Accepted> get() = outcomes.filterIsInstance<PluginOutcome.Accepted>()
    val refused: List<PluginOutcome.Refused> get() = outcomes.filterIsInstance<PluginOutcome.Refused>()
    val failed: List<PluginOutcome.Failed> get() = outcomes.filterIsInstance<PluginOutcome.Failed>()
}

sealed interface UnloadOutcome {
    data class Unloaded(val pluginId: String, val harnessIds: List<String>) : UnloadOutcome
    data class NotLoaded(val pluginId: String) : UnloadOutcome

    /** The plugin's own unload() threw. It is removed anyway: a broken plugin must not pin its ids. */
    data class UnloadFailed(val failure: PluginFailure, val harnessIds: List<String>) : UnloadOutcome
}

/**
 * HARNESS 2/4 - the daemon side. Discovers plugins from a [HarnessPluginSource], refuses any whose
 * declared contract version this daemon does not implement, and routes a [HarnessRequest] to whichever
 * loaded plugin registered the requested harness id.
 *
 * It holds NO knowledge of any harness: no `when (harness)`, no built-in list, no default. A plugin
 * is whatever the source yields. Everything a plugin registers is staged and committed all-or-nothing.
 *
 * [supportedVersions] is exact-match on purpose: widening it is a decision the daemon owner makes with
 * knowledge, because a silently mismatched plugin looks registered and then behaves wrongly.
 * [onFailure] receives every plugin failure as it happens (default: stderr); it must not throw.
 */
class HarnessRegistry(
    val supportedVersions: Set<String> = setOf(HARNESS_CONTRACT_VERSION),
    private val onFailure: (PluginFailure) -> Unit = { System.err.println("[harness] ${it.message}") },
) {
    private class Loaded(val plugin: HarnessPlugin, val harnesses: Map<String, Harness>)

    private val lock = Any()
    private val plugins = LinkedHashMap<String, Loaded>() // plugin id -> what it registered
    private val owners = LinkedHashMap<String, String>() // harness id -> plugin id

    fun harnessIds(): List<String> = synchronized(lock) { owners.keys.toList() }

    /** HARNESS H5c: every registered harness's usage source (a throwing `limitSource()` contributes none). */
    fun limitSources(): List<com.superiorsoftllc.infantry.usage.LimitSource> {
        val all = synchronized(lock) { plugins.values.flatMap { it.harnesses.values } }
        return all.mapNotNull { runCatching { it.limitSource() }.getOrNull() }
    }

    /** HARNESS H5d: the stats source of harness [harnessId], or null (unknown id, none, or a throwing `statsSource()`). */
    fun statsSource(harnessId: String): com.superiorsoftllc.infantry.stats.PluginStatsSource? =
        runCatching { resolve(harnessId)?.statsSource() }.getOrNull()

    /** HARNESS H8b-1: harness [harnessId]'s config merge of [parts] into [existing]; null (unknown id, none, or a throwing `mergeConfig`). */
    fun mergeConfig(harnessId: String, existing: String?, parts: com.superiorsoftllc.infantry.harness.api.ConfigParts): String? =
        runCatching { resolve(harnessId)?.mergeConfig(existing, parts) }.getOrNull()

    /** HARNESS H5e: [spec] with harness [harnessId]'s watch contribution applied for a session with [env]; [spec] unchanged when there is none. */
    fun applyWatch(harnessId: String, env: Map<String, String>, spec: com.superiorsoftllc.infantry.runner.ProcessSpec): com.superiorsoftllc.infantry.runner.ProcessSpec {
        val w = runCatching { resolve(harnessId)?.watchContribution(env) }.getOrNull() ?: return spec
        return spec.copy(command = spec.command + w.extraArgs, env = spec.env + w.env)
    }

    /** HARNESS H5f: harness [harnessId]'s checked sandbox addition for a session in [worktree]; null when there is none or it was refused. */
    fun sandboxFor(harnessId: String, worktree: java.io.File): PluginSandbox? =
        runCatching { resolve(harnessId)?.sandboxContribution(worktree) }.getOrNull()

    /** RUNNER-CHECK-SIGNIN-1: why harness [harnessId]'s CLI is not signed in here, or null (signed in, unknown id, no answer, or a throw). */
    fun signInProblem(harnessId: String): String? =
        runCatching { resolve(harnessId)?.signInProblem() }.getOrNull()

    fun pluginIds(): List<String> = synchronized(lock) { plugins.keys.toList() }

    fun resolve(harnessId: String): Harness? = synchronized(lock) {
        owners[harnessId]?.let { plugins[it]?.harnesses?.get(harnessId) }
    }

    /** Pull every candidate from [source] and register it. One bad plugin never hides the others. */
    fun discover(source: HarnessPluginSource): DiscoveryReport {
        val candidates = try {
            source.candidates()
        } catch (t: Throwable) {
            return DiscoveryReport(listOf(PluginOutcome.Failed(fail(source.javaClass.name, PluginFailurePhase.DISCOVER, t))))
        }
        return DiscoveryReport(
            candidates.map { candidate ->
                val plugin = try {
                    candidate.create()
                } catch (t: Throwable) {
                    return@map PluginOutcome.Failed(fail(candidate.label, PluginFailurePhase.INSTANTIATE, t))
                }
                register(plugin, candidate.label)
            },
        )
    }

    /** Version handshake, then plugin-side registration, then an atomic commit. [label] names the plugin if its id is unreadable. */
    fun register(plugin: HarnessPlugin, label: String = plugin.javaClass.name): PluginOutcome {
        val pluginId: String
        val declared: String
        try {
            pluginId = plugin.pluginId
            declared = plugin.contractVersion
        } catch (t: Throwable) {
            return PluginOutcome.Failed(fail(label, PluginFailurePhase.REGISTER, t))
        }
        if (pluginId.isBlank()) return refuse(label, RefusalReason.INVALID_ID, "plugin '$label' declares a blank plugin id")
        // HARNESS-PLUGIN-3 H3: an API 2.x plugin (through its bridge) is checked by the semver rule, anything else exact-match.
        if (plugin is ApiHarnessPluginBridge) {
            HarnessApiVersion.refusal(declared)?.let { return refuse(pluginId, RefusalReason.VERSION_MISMATCH, "plugin '$pluginId' $it") }
        } else if (declared !in supportedVersions) {
            return refuse(
                pluginId, RefusalReason.VERSION_MISMATCH,
                "plugin '$pluginId' implements harness contract '$declared'; this daemon implements " +
                    supportedVersions.joinToString(", ") { "'$it'" } + ". Rebuild the plugin against a supported contract version.",
            )
        }
        if (synchronized(lock) { pluginId in plugins }) {
            return refuse(pluginId, RefusalReason.DUPLICATE_PLUGIN, "a plugin with id '$pluginId' is already loaded")
        }

        val staging = StagingRegistrar()
        try {
            plugin.register(staging)
        } catch (t: Throwable) {
            return PluginOutcome.Failed(fail(pluginId, PluginFailurePhase.REGISTER, t))
        } finally {
            staging.close()
        }
        staging.problem?.let { (reason, detail) -> return refuse(pluginId, reason, "plugin '$pluginId': $detail") }

        val harnesses = staging.staged
        synchronized(lock) {
            if (pluginId in plugins) {
                return refuse(pluginId, RefusalReason.DUPLICATE_PLUGIN, "a plugin with id '$pluginId' is already loaded")
            }
            harnesses.keys.firstOrNull { it in owners }?.let { taken ->
                return refuse(
                    pluginId, RefusalReason.DUPLICATE_HARNESS_ID,
                    "plugin '$pluginId' registers harness '$taken', already registered by plugin '${owners[taken]}'",
                )
            }
            plugins[pluginId] = Loaded(plugin, harnesses)
            harnesses.keys.forEach { owners[it] = pluginId }
        }
        return PluginOutcome.Accepted(pluginId, harnesses.keys.toList())
    }

    /** Remove [pluginId] and everything it registered, THEN let it release what it holds. */
    fun unload(pluginId: String): UnloadOutcome {
        val loaded = synchronized(lock) {
            plugins.remove(pluginId)?.also { l -> l.harnesses.keys.forEach { owners.remove(it) } }
        } ?: return UnloadOutcome.NotLoaded(pluginId)
        val ids = loaded.harnesses.keys.toList()
        return try {
            loaded.plugin.unload()
            UnloadOutcome.Unloaded(pluginId, ids)
        } catch (t: Throwable) {
            UnloadOutcome.UnloadFailed(fail(pluginId, PluginFailurePhase.UNLOAD, t), ids)
        }
    }

    /**
     * Route [request] to the harness registered under `request.harnessId`. A missing capability is the
     * caller's error and passes through untouched; anything else the plugin throws becomes a [PluginFailureException].
     */
    fun run(request: HarnessRequest): Flow<HarnessEvent> {
        val (pluginId, harness) = synchronized(lock) {
            val owner = owners[request.harnessId]
            val h = owner?.let { plugins[it]?.harnesses?.get(request.harnessId) }
            if (owner != null && h != null) owner to h else null
        } ?: throw UnknownHarnessException(request.harnessId, harnessIds())
        val upstream = try {
            harness.run(request)
        } catch (e: MissingCapabilityException) {
            throw e
        } catch (t: Throwable) {
            throw PluginFailureException(fail(pluginId, PluginFailurePhase.RUN, t))
        }
        return upstream.catch { t ->
            if (t is CancellationException) throw t
            throw PluginFailureException(fail(pluginId, PluginFailurePhase.RUN, t))
        }
    }

    private fun refuse(plugin: String, reason: RefusalReason, detail: String) = PluginOutcome.Refused(plugin, reason, detail)

    private fun fail(plugin: String, phase: PluginFailurePhase, cause: Throwable): PluginFailure {
        val failure = PluginFailure(plugin, phase, cause)
        onFailure(failure)
        return failure
    }

    /** Collects what a plugin registers; nothing reaches the live maps until the whole plugin validated. */
    private class StagingRegistrar : HarnessRegistrar {
        val staged = LinkedHashMap<String, Harness>()
        var problem: Pair<RefusalReason, String>? = null
        private var open = true

        override fun register(harness: Harness) {
            check(open) { "HarnessRegistrar is only valid during HarnessPlugin.register(); harness registration after that is refused" }
            val id = harness.id
            when {
                id.isBlank() -> problem = problem ?: (RefusalReason.INVALID_ID to "registers a harness with a blank id")
                id in staged -> problem = problem ?: (RefusalReason.DUPLICATE_HARNESS_ID to "registers harness '$id' more than once")
                else -> staged[id] = harness
            }
        }

        fun close() {
            open = false
        }
    }
}
