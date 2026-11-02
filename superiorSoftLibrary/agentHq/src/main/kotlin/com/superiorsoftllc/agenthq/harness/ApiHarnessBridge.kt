package com.superiorsoftllc.agenthq.harness

import com.superiorsoftllc.agenthq.harness.api.HARNESS_API_VERSION
import com.superiorsoftllc.agenthq.harness.api.HarnessHost
import com.superiorsoftllc.agenthq.runner.PipedProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.harness.api.Harness as ApiHarness
import com.superiorsoftllc.agenthq.harness.api.HarnessEvent as ApiEvent
import com.superiorsoftllc.agenthq.harness.api.HarnessPlugin as ApiPlugin
import com.superiorsoftllc.agenthq.harness.api.HarnessRegistrar as ApiRegistrar
import com.superiorsoftllc.agenthq.harness.api.HarnessRequest as ApiRequest
import com.superiorsoftllc.agenthq.harness.api.ToolApproval as ApiToolApproval

/**
 * HARNESS-PLUGIN-3 H3: the harness API 2.x semver rule. Same major as this daemon, minor not newer (a minor only adds
 * members with defaults, so a plugin built against an older minor still links). Null = compatible, else why not.
 */
object HarnessApiVersion {
    fun refusal(declared: String, host: String = HARNESS_API_VERSION): String? {
        val d = parse(declared) ?: return "declares harness API '${declared.take(40)}', which is not a version (expected major.minor.patch)"
        val h = parse(host)!!
        return when {
            d.first != h.first -> "implements harness API $declared; this daemon implements $host (major ${h.first}). Rebuild the plugin against harness API ${h.first}.x."
            d.second > h.second -> "needs harness API $declared; this daemon implements $host. Update Infantry AI or use an older plugin version."
            else -> null
        }
    }

    private fun parse(v: String): Triple<Int, Int, Int>? =
        Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(v.trim())?.destructured?.let { (a, b, c) -> Triple(a.toInt(), b.toInt(), c.toInt()) }
}

/**
 * HARNESS-PLUGIN-3 H3: a harness API 2.x plugin seen by the 1.x [HarnessRegistry], so registration, the atomic commit,
 * `unload`, `providers` and the session path ([com.superiorsoftllc.agenthq.provider.HarnessProvider]) stay ONE mechanism.
 * The registry checks [contractVersion] with [HarnessApiVersion] for a bridge (the 1.x exact-match set for anything else).
 *
 * Every call into plugin code is caught here and reported to [onFailure] with the plugin's name, so a throwing plugin
 * fails its own call (a refused spawn, an error event) and never the daemon or another harness.
 */
class ApiHarnessPluginBridge(
    val api: ApiPlugin,
    private val host: HarnessHost,
    private val runner: ProcessRunner = PipedProcessRunner(),
    private val onFailure: (PluginFailure) -> Unit = { System.err.println("[harness] ${it.message}") },
    /** H4: module.json `harness.ids`; the plugin must register exactly these (null = not checked, a plugin outside a module). */
    private val declaredIds: Set<String>? = null,
) : HarnessPlugin {
    override val pluginId: String get() = api.pluginId
    override val contractVersion: String get() = api.contractVersion

    override fun register(registrar: HarnessRegistrar) {
        val id = pluginId
        val registered = LinkedHashSet<String>()
        api.register(object : ApiRegistrar {
            override val host: HarnessHost get() = this@ApiHarnessPluginBridge.host
            override fun register(harness: ApiHarness) {
                val adapted = ApiCliHarness(id, harness, runner, onFailure, hookAllowed = { kind -> (host as? ModuleHarnessHost)?.hookAllowed(kind) ?: false })
                check(declaredIds == null || adapted.id in declaredIds) { "registers harness '${adapted.id.take(40)}', which module.json harness.ids does not declare (${declaredIds.orEmpty().sorted()})" }
                registered += adapted.id
                registrar.register(adapted)
            }
        })
        check(declaredIds == null || registered == declaredIds) { "module.json harness.ids declares ${declaredIds.orEmpty().sorted()} but the plugin registered ${registered.sorted()}" }
    }

    override fun unload() = api.unload()
}

/** Thrown when plugin code failed on the session path; the message names the plugin, the phase and the cause. */
class HarnessPluginCallException(val failure: PluginFailure) : IllegalStateException(failure.message, failure.cause)

/** HARNESS-PLUGIN-3 H3: one API 2.x harness as the daemon's [CliHarness]: its argv and its translator, nothing more. */
class ApiCliHarness(
    val pluginId: String,
    val api: ApiHarness,
    runner: ProcessRunner,
    private val onFailure: (PluginFailure) -> Unit,
    /** H5c / H5d: whether hook [kind] is declared and granted for this plugin's module; the default refuses (no host to ask). */
    private val hookAllowed: (String) -> Boolean = { false },
    private val usageTimeoutMs: Long = USAGE_TIMEOUT_MS,
) : CliHarness(
    runner,
    specFor = { request -> guarded(pluginId, onFailure) { api.process(request.toApi()) }.let { ProcessSpec(it.command, it.workingDir, it.env) } },
    translatorFactory = { GuardedTranslator(pluginId, guarded(pluginId, onFailure) { api.newTranslator() }, onFailure) },
    cliName = runCatching { api.id }.getOrDefault(pluginId),
) {
    override val id: String = api.id
    override val capabilities: Set<HarnessCapability> =
        runCatching { api.capabilities }.getOrDefault(emptySet()).mapTo(LinkedHashSet()) { HarnessCapability.valueOf(it.name) }

    /** CONTEXT-LIMIT-1: API 2.1 [ApiHarness.contextLimit]; a 2.0 plugin (or a throwing getter) is unsupported. */
    override val contextLimit: ContextLimitCapability =
        runCatching { api.contextLimit }.getOrNull()
            ?.let { ContextLimitCapability(ContextLimitSupport.valueOf(it.support.name), it.how) }
            ?: ContextLimitCapability.UNSUPPORTED

    /** EFFORT-1: API 2.2 [ApiHarness.effort]; a 2.0 / 2.1 plugin (or a throwing getter) keeps the contract default (EFFORT_SELECTION decides). */
    override val effort: EffortCapability =
        runCatching { api.effort }.getOrNull()
            ?.let { EffortCapability(EffortSupport.valueOf(it.support.name), it.how) }
            ?: if (HarnessCapability.EFFORT_SELECTION in capabilities) EffortCapability(EffortSupport.SUPPORTED, "the harness declares EFFORT_SELECTION")
            else EffortCapability.UNSUPPORTED

    private val usageAdapter: com.superiorsoftllc.agenthq.usage.LimitSource? by lazy {
        if (!hookAllowed("usage")) return@lazy null
        val src = guarded(pluginId, onFailure) { api.usageSource() } ?: return@lazy null
        PluginLimitSource(api.id, pluginId, src, usageTimeoutMs, onFailure)
    }

    /** H5c: the module's usage source behind the `harness.hook.usage` grant; a throwing `usageSource()` is attributed and contributes none. */
    override fun limitSource(): com.superiorsoftllc.agenthq.usage.LimitSource? = try { usageAdapter } catch (_: HarnessPluginCallException) { null }

    private class GuardedTranslator(
        private val pluginId: String,
        private val inner: com.superiorsoftllc.agenthq.harness.api.HarnessTranslator,
        private val onFailure: (PluginFailure) -> Unit,
    ) : HarnessTranslator {
        override fun translate(line: String): List<HarnessEvent> = try {
            inner.translate(line).map { it.toInternal() }
        } catch (t: Throwable) {
            val f = PluginFailure(pluginId, PluginFailurePhase.RUN, t).also(onFailure)
            listOf(HarnessEvent.Error(f.message))
        }

        override fun terminal(exitCode: Int, stderrTail: String): HarnessEvent = try {
            inner.terminal(exitCode, stderrTail).toInternal()
        } catch (t: Throwable) {
            HarnessEvent.Error(PluginFailure(pluginId, PluginFailurePhase.RUN, t).also(onFailure).message)
        }
    }

    private companion object {
        const val USAGE_TIMEOUT_MS = 10_000L

        fun <T> guarded(pluginId: String, onFailure: (PluginFailure) -> Unit, block: () -> T): T = try {
            block()
        } catch (t: Throwable) {
            throw HarnessPluginCallException(PluginFailure(pluginId, PluginFailurePhase.RUN, t).also(onFailure))
        }

        fun HarnessRequest.toApi() = ApiRequest(
            prompt, harnessId, project, model, sessionId, effort,
            when (toolApproval) {
                ToolApproval.DENY -> ApiToolApproval.DENY
                ToolApproval.ALLOW_EDITS -> ApiToolApproval.ALLOW_EDITS
                ToolApproval.ALLOW_ALL -> ApiToolApproval.ALLOW_ALL
                ToolApproval.ASK -> ApiToolApproval.ASK
            },
            contextLimit,
        )

        fun ApiEvent.toInternal(): HarnessEvent = when (this) {
            is ApiEvent.SessionStarted -> HarnessEvent.SessionStarted(sessionId)
            is ApiEvent.TextDelta -> HarnessEvent.TextDelta(text)
            is ApiEvent.ToolCall -> HarnessEvent.ToolCall(callId, name, arguments)
            is ApiEvent.ToolResult -> HarnessEvent.ToolResult(callId, output, isError)
            is ApiEvent.Reasoning -> HarnessEvent.Reasoning(text)
            is ApiEvent.Usage -> HarnessEvent.Usage(inputTokens, outputTokens)
            is ApiEvent.Completion -> HarnessEvent.Completion(reason)
            is ApiEvent.Error -> HarnessEvent.Error(message)
            is ApiEvent.Denial -> if (tool.isBlank()) HarnessEvent.Error("harness plugin emitted a denial with no tool name") else HarnessEvent.Denial(tool, reason)
        }
    }
}

/**
 * HARNESS H5c: a plugin's [com.superiorsoftllc.agenthq.harness.api.HarnessUsageSource] as a daemon `LimitSource`. The call runs on
 * its own daemon thread and is bounded by [timeoutMs]; a throw or a timeout becomes `unknown` / `source_failed` for this harness
 * only (never the other providers) and is reported to [onFailure] with the plugin's name. The plugin's reason text never reaches the wire.
 */
internal class PluginLimitSource(
    override val provider: String,
    private val pluginId: String,
    private val source: com.superiorsoftllc.agenthq.harness.api.HarnessUsageSource,
    private val timeoutMs: Long,
    private val onFailure: (PluginFailure) -> Unit,
) : com.superiorsoftllc.agenthq.usage.LimitSource {
    override fun read(now: Long): com.superiorsoftllc.agenthq.usage.ProviderLimits {
        val box = java.util.concurrent.atomic.AtomicReference<Result<com.superiorsoftllc.agenthq.harness.api.HarnessUsageReading>>()
        val t = Thread({ box.set(runCatching { source.read(now) }) }, "agentHq-usage-$provider").apply { isDaemon = true; start() }
        t.join(timeoutMs)
        val result = box.get()
        if (result == null) {
            t.interrupt()
            onFailure(PluginFailure(pluginId, PluginFailurePhase.RUN, IllegalStateException("usage source of $provider timed out after $timeoutMs ms")))
            return failed(now)
        }
        val reading = result.getOrElse {
            onFailure(PluginFailure(pluginId, PluginFailurePhase.RUN, it))
            return failed(now)
        }
        val windows = reading.windows.map { w ->
            com.superiorsoftllc.agenthq.daemon.api.usage.LimitWindow(
                provider, w.label, w.windowMinutes, w.percent, w.resetsAt, now, "live",
                com.superiorsoftllc.agenthq.usage.LimitPace.delta(w.percent, w.resetsAt, w.windowMinutes, now),
            )
        }
        return if (windows.isEmpty()) com.superiorsoftllc.agenthq.usage.ProviderLimits.unknown(provider, now, com.superiorsoftllc.agenthq.usage.LimitReason.BAD_RESPONSE)
        else com.superiorsoftllc.agenthq.usage.ProviderLimits(provider, "ok", windows, null, now)
    }

    private fun failed(now: Long) = com.superiorsoftllc.agenthq.usage.ProviderLimits.unknown(provider, now, com.superiorsoftllc.agenthq.usage.LimitReason.SOURCE_FAILED)
}
