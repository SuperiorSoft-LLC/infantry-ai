// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.scripting.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.IncomingMail
import com.superiorsoftllc.infantry.daemon.api.IncomingMailHook
import com.superiorsoftllc.infantry.daemon.api.MailVerdict
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.ServiceEndpoint
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File

/** SCRIPT-2: owns the open ScriptEngine extension point (endpoints under the `scripting.engine/` prefix) and the limits settings. Later tickets add the callers. */
class ScriptingDaemonPlugin : DaemonPlugin {
    private companion object {
        const val TIMELINE_ITEM = "timeline_item"
        const val TIMELINE_LIST = "timeline_list"
        const val TIMELINE_ITEMS = "timeline_items"
    }

    private var subscription: AutoCloseable? = null
    private var sessionWatch: AutoCloseable? = null
    private var wakeWatch: AutoCloseable? = null
    private var timer: java.util.concurrent.ScheduledExecutorService? = null

    override fun stop() { runCatching { timer?.shutdownNow() }; timer = null; runCatching { subscription?.close() }; subscription = null; runCatching { sessionWatch?.close() }; sessionWatch = null; runCatching { wakeWatch?.close() }; wakeWatch = null }

    override fun start(api: DaemonApi) {
        val engines = ScriptEngines({ api.services.endpoints(ScriptEngineWire.PREFIX) }, { api.config("scripting.defaultEngine")?.takeIf { it.isNotBlank() } ?: "lua" })
        val limits = ScriptingLimits { api.config(it) }
        // SCRIPT-ARSENAL-1: the app-wide scope lives in this plugin's state dir; the starter set is seeded from the jar (never over an edit).
        val globalScripts = GlobalScripts(File(api.stateDir, "global-scripts"), { engines.extensions() })
        runCatching { BundledScripts.seed(globalScripts, File(api.stateDir, "global-scripts-state"), BundledScripts.fromJar { api.log(it) }) { api.log(it) } }
            .onSuccess { if (it.isNotEmpty()) api.log("global scripts seeded: ${it.joinToString()}") }
            .onFailure { api.log("global scripts not seeded: ${it.message}") }
        val store = ScriptStore({ api.files(it) }, { engines.extensions() }, globalScripts)
        val aliases = { ScriptAliases.effective(api.config("scripting.aliases")) }
        val maxPhases = { api.config("scripting.maxPhases")?.trim()?.toIntOrNull() ?: 3 }
        // SCRIPT-VIS-1b: the run log is stored in the plugin's own tables (a daemon without storage keeps it in memory only).
        val runLog = ScriptRunLog({ api.settings.limit("limits.scripting.runLogMax")?.toInt() ?: 200 }, store = runCatching { StorageRunStore(api.storage) }.getOrNull())
        // SCRIPT-VIS-1b: each run's AUTOMATION line goes to the chat of the session it ran for (a timeline_item push, no turn). Nothing here may
        // fail a run: a throwing broadcast is dropped and the chat is exactly what it was.
        runLog.onRecord = { rec -> rec.timelineJson()?.let { runCatching { api.events.broadcast(TIMELINE_ITEM, it.toString()) } } }
        runLog.onStats = { item -> runCatching { api.events.broadcast(TIMELINE_ITEM, item.toString()) } }
        api.frames.handle(TIMELINE_LIST) { frame, _ ->
            val wanted = frame.data.trim().takeIf { it.isNotEmpty() }
            val items = runLog.timelineItems().filter { wanted == null || (it["sessionId"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull == wanted }
            Frame(TIMELINE_ITEMS, kotlinx.serialization.json.JsonArray(items).toString(), frame.sessionId, frame.requestId)
        }
        val marks = AutomationMarks({ session, mark -> api.sessions.setAutomation(session, mark) })
        val tools = ScriptTools(api.tools, engines, store, limits, aliases, maxPhases, runLog, marks)
        api.tools.register(runLog.tool())
        api.tools.register(ChatNoteTools(
            { api.sessions.get(it)?.projectId }, { runCatching { api.events.broadcast(TIMELINE_ITEM, it.toString()) } }, runLog::addNote,
            { id, text -> api.sessions.resume(id, text) },
            queueWake = { id, text -> api.sessions.queueAdd(id, text) },
            allSessionIds = { api.sessions.list().map { it.id } },
            forwardImage = { args, call ->
                api.tools.invoke("infantry_chat_image", args, com.superiorsoftllc.infantry.daemon.api.ToolInvoker(call.caller, call.clientSessionId, call.clientRole, call.clientProjectId, "chat_note"))
            },
        ).tool)
        // SCRIPT-SESSION-1: read a session's state/chat, act on its turn, suppress a built-in notice.
        val sessionTools = SessionTools(
            { api.sessions.get(it) }, { api.sessions.status(it) }, { id, n -> api.sessions.recentLines(id, n) }, { api.sessions.interrupt(it) },
            { name, session, ms -> api.sessions.suppressNotice(name, session, ms) },
            reconnectMcp = { id, force -> api.sessions.reconnectMcp(id, force) },
            queued = { api.sessions.queued(it) },
            queueAdd = { id, text -> api.sessions.queueAdd(id, text) },
            queueRemove = { id, item -> api.sessions.queueRemove(id, item) },
            queueForce = { id, item -> api.sessions.queueForce(id, item) },
            allSessionIds = { api.sessions.list().map { it.id } },
        )
        api.tools.register(sessionTools.read)
        api.tools.register(sessionTools.act)
        api.tools.register(sessionTools.suppressNotice)
        // NOTIFY-CORE-1: ih.call("notify", {...}) posts on the core notification bus.
        api.tools.register(NotificationTools { api.notifications }.tool)
        api.tools.register(tools.run)
        api.tools.register(tools.api)
        // SCRIPT-5: hooks are data (hooks.json); one subscription on the named-event bus runs them.
        val hookStore = HookStore({ api.files(it) }, File(api.stateDir, "global-hooks.json"))
        val builtinHooks = BuiltinHook.fromJar { api.log(it) }
        val arsenalKnowledge = ArsenalKnowledge({ api.services.find(com.superiorsoftllc.infantry.daemon.api.knowledge.KnowledgeWriter::class) }, store, { api.projects.list().map { it.id } }) { api.log(it) }
        val saveTools = SaveTools(store, hookStore, engines, api::displayStamp, { api.config("scripting.global.writeRoles") ?: SaveTools.DEFAULT_GLOBAL_WRITERS }, { api.projects.list().map { it.id } },
            changed = { scope, project, name -> if (scope == ScriptStore.GLOBAL || project == null) arsenalKnowledge.refreshGlobal(name) else arsenalKnowledge.refreshProject(project, name) })
        api.tools.register(HookTools(hookStore, store, { HookSettings.minEverySec { api.config(it) } }, saveTools::globalWriteRefusal).tool)
        saveTools.let { api.tools.register(it.save); api.tools.register(it.list); api.tools.register(it.delete) }
        val hooks = ScriptHooks(
            hookStore, store, engines, api.tools, ScriptDispatcher(api.tools, aliases, maxPhases, sessionProject = { api.sessions.get(it)?.projectId }),
            projects = { api.projects.list().map { it.id } }, limits = { limits.current() },
            guard = { HookSettings.guard { api.config(it) } }, enabled = { HookSettings.enabled { api.config(it) } }, mailFailures = { api.config("scripting.hooks.mailFailures")?.trim()?.lowercase() == "true" },
            tip = { project, branch -> ScriptHooks.gitTip(File(api.files(project).absolutePath("x")).parentFile.parentFile, branch) },
            log = { api.log(it) }, runLog = runLog,
            automation = marks.setter,
            // BRIDGE-EVENTS-1: the hooks this plugin ships (builtin-scripts/ in the jar), e.g. resume-after-restart on daemon.started.
            builtins = { builtinHooks },
            builtinConfig = { api.config(it) },
        )
        // SCRIPT-VIS-1: why each session was woken (and mails a hook handled instead), from the daemon's session.woken event.
        wakeWatch = api.events.subscribe("session.woken") { e ->
            runCatching {
                val o = kotlinx.serialization.json.Json.parseToJsonElement(e.payload).jsonObject
                fun s(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                runLog.wake(s("sessionId") ?: return@runCatching, s("reason") ?: return@runCatching, s("ref"), s("projectId"))
            }
        }
        api.hooks.incomingMail(object : IncomingMailHook {
            override fun incoming(mail: IncomingMail): MailVerdict? = hooks.incoming(mail)
            // SCRIPT-VIS-1b: the host dropped our verdict (timeout / limit / throw): the record must not claim a handled mail.
            override fun lost(mailId: String, reason: String) = runLog.verdictLost(mailId, reason)
        })
        // MAIL-HOOK-1b: a session's mail hooks go with the session.
        sessionWatch = api.events.onSessionRemoved { id -> runCatching { hookStore.removeForSession(api.projects.list().map { it.id }, id).takeIf { it > 0 }?.let { api.log("hooks: removed $it mail hook(s) of ended session $id") } } }
        // SCRIPT-CMD-1: the process bridge (off until scripting.exec.enabled).
        val exec = ExecTools(
            { api.config(it) }, { project -> runCatching { File(api.files(project).absolutePath("x")).parentFile.parentFile }.getOrNull() },
            { name, payload -> api.events.publish(name, payload) }, { api.log(it) }, marks,
            { session, project, args -> api.tools.invoke("infantry_progress", args, com.superiorsoftllc.infantry.daemon.api.ToolInvoker(com.superiorsoftllc.infantry.daemon.api.Caller(owner = true, agent = true, activeSession = session), session, null, project, "exec-progress")) },
        )
        // SCRIPT-FETCH-1: capability http.fetch (module.json `capabilities`), off until scripting.fetch.enabled.
        val fetch = FetchBridge({ api.config(it) }, { name, project -> api.runs.secretFields(name, project) }, { api.log(it) })
        api.capabilities.handle(FetchBridge.ID) { call -> fetch.fetch(call.args, call.caller.project) }
        api.tools.register(exec.exec)
        api.tools.register(exec.status)
        // SCRIPT-TICK-1: one 1 s clock drives `every <N>s` hooks and exec.tick (each at its own period). A throw never stops it.
        val clock = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "script-timer").apply { isDaemon = true } }
        var lastExecTick = System.currentTimeMillis()
        // SCRIPT-ARSENAL-2: the knowledge entries are written once the Notes plugin's writer is published (it may start after this plugin).
        val entriesSynced = java.util.concurrent.atomic.AtomicBoolean(false)
        clock.scheduleWithFixedDelay({
            if (!entriesSynced.get()) runCatching { val n = arsenalKnowledge.syncAll(); if (n >= 0 && entriesSynced.compareAndSet(false, true)) api.log("script arsenal knowledge entries synced: $n") }
            runCatching { hooks.timerTick(HookSettings.minEverySec { api.config(it) }) }
            runCatching {
                val now = System.currentTimeMillis()
                if (now - lastExecTick >= ExecTools.tickSec { api.config(it) } * 1000) { lastExecTick = now; exec.tick() }
            }
        }, 1, 1, java.util.concurrent.TimeUnit.SECONDS)
        timer = clock
        subscription = api.events.subscribe("*") { hooks.onEvent(it) }
        api.log("scripting ready: engines=${engines.ids()} limits=${limits.current()} builtins=${builtinHooks.map { it.id }}")
    }
}

/**
 * The registry: every engine published under the extension point, by id or file extension. [source] is read at each lookup, so
 * engines that load or unload later are seen at once. [defaultId] is data (the `scripting.defaultEngine` setting).
 */
class ScriptEngines(private val source: () -> Map<String, ServiceEndpoint>, private val defaultId: () -> String = { "lua" }) {
    private fun engines(): List<ScriptEngine> = source().filterKeys { it.startsWith(ScriptEngineWire.PREFIX) }.values.mapNotNull(ScriptEngineWire::remote)

    fun ids(): Set<String> = engines().map { it.id }.toSortedSet()
    fun byId(id: String): ScriptEngine? = engines().firstOrNull { it.id == id }
    fun byExtension(ext: String): ScriptEngine? = ext.trim().trimStart('.').lowercase().let { e -> engines().firstOrNull { e in it.extensions } }
    fun extensions(): List<String> = engines().flatMap { it.extensions }.distinct()
    /** engine id -> its binding text (script_api). */
    fun bindings(): Map<String, String> = engines().associate { it.id to it.binding }
    fun default(): ScriptEngine? = byId(defaultId())
}

/** The Scripting plugin's own limits, read from its `daemon.config` at each run; a bad value falls back to the default. */
class ScriptingLimits(private val config: (String) -> String?) {
    fun current(): ScriptLimits {
        val d = ScriptLimits()
        fun pick(key: String, range: LongRange, default: Long) = config(key)?.trim()?.toLongOrNull()?.takeIf { it in range } ?: default
        return ScriptLimits(
            pick("scripting.maxSteps", 1_000L..1_000_000_000L, d.maxSteps),
            pick("scripting.timeoutMs", 10L..600_000L, d.timeoutMs),
            pick("scripting.maxAllocBytes", 65_536L..1_073_741_824L, d.maxAllocBytes),
        )
    }
}

/** The alias table (short name -> registry tool name) is data: the JSON object in the `scripting.aliases` setting. A bad value is an empty table. */
object ScriptAliases {
    /**
     * LAUNCH-VERIFY-2: the shipped table, used while the setting is unset ([DaemonApi.config] is null for an unset key, so a fresh install
     * had NO aliases and `ih.move{}` / `ih.board{}` failed with "unknown tool or alias"). Equal to the default module.json declares for
     * `scripting.aliases` (ScriptAliasesTest keeps the two the same).
     */
    val DEFAULTS: Map<String, String> = linkedMapOf(
        "move" to "infantry_ticket_move", "comment" to "infantry_ticket_comment", "message" to "infantry_message",
        "start" to "infantry_ticket_start", "close_session" to "infantry_session_remove", "kill" to "infantry_kill",
        "prompt" to "infantry_prompt", "ask" to "infantry_ask", "ticket_get" to "infantry_ticket_get",
        "ticket_update" to "infantry_ticket_update", "ticket_create" to "infantry_ticket_create", "board" to "infantry_board",
        "emit" to "infantry_event_emit",
    )

    /** The table a run uses: the stored setting when one is set (a stored `{}` means none), else [DEFAULTS]. */
    fun effective(text: String?): Map<String, String> = if (text.isNullOrBlank()) DEFAULTS else parse(text)

    fun parse(text: String?): Map<String, String> = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(text.orEmpty().ifBlank { "{}" }).jsonObject
            .mapNotNull { (k, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { k to it } }.toMap()
    }.getOrDefault(emptyMap())
}

/** SCRIPT-5: the hook loop guard and master switch are plugin settings (`scripting.hooks.*`); a bad value falls back to the default. */
object HookSettings {
    /** SCRIPT-TICK-1: the floor for `every <N>s` hooks, seconds (setting scripting.hooks.minEverySec, default 10, 1..86400). */
    fun minEverySec(config: (String) -> String?): Long = config("scripting.hooks.minEverySec")?.trim()?.toLongOrNull()?.takeIf { it in 1L..86_400L } ?: 10L

    fun enabled(config: (String) -> String?): Boolean = config("scripting.hooks.enabled")?.trim()?.lowercase() != "false"

    fun guard(config: (String) -> String?): HookGuardConfig {
        val d = HookGuardConfig()
        fun pick(key: String, range: LongRange, default: Long) = config(key)?.trim()?.toLongOrNull()?.takeIf { it in range } ?: default
        return HookGuardConfig(
            pick("scripting.hooks.maxDepth", 0L..20L, d.maxDepth.toLong()).toInt(),
            pick("scripting.hooks.maxRuns", 1L..10_000L, d.maxRuns.toLong()).toInt(),
            pick("scripting.hooks.windowMs", 1_000L..3_600_000L, d.windowMs),
            pick("scripting.hooks.attributionMs", 0L..600_000L, d.attributionMs),
        )
    }
}
