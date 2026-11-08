// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.scripting.daemon

import com.superiorsoftllc.infantry.daemon.api.files.ProjectFiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * SCRIPT-5: what a hook listens to. Two forms, both data: `lane enter <lane>` / `lane exit <lane>` (a ticket entered / left that board column; todo, in_progress,
 * pending, done or any user lane) and a named event (`ticket.commented`, `merge.done`, a prefix `ticket.*`, or `*`), any event on the
 * SCRIPT-3 bus. A lane hook is the named event [LANE_EVENT] filtered on its `to` field.
 */
sealed interface HookOn {
    data class Lane(val lane: String, val exit: Boolean = false) : HookOn { override fun toString() = "lane ${if (exit) "exit" else "enter"} $lane" }
    data class Named(val pattern: String) : HookOn { override fun toString() = pattern }
    /** SCRIPT-TICK-1: a timer, `every <N>s` (the plugin setting scripting.hooks.minEverySec is the floor). */
    data class Every(val seconds: Long) : HookOn { override fun toString() = "every ${seconds}s" }

    companion object {
        /** The named event a ticket lane change is published as (by the daemon's ticket stream); lane hooks subscribe to it. */
        const val LANE_EVENT = "ticket.moved"
        /** SCRIPT-TICK-1: the named event fired for each live background exec run. */
        const val EXEC_TICK = "exec.tick"
        private val LANE = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
        private val NAME = Regex("[a-z][a-z0-9_-]*([.][a-z0-9][a-z0-9_-]*)*")

        /** Parses `lane enter <lane>` (case and spacing free) or an event name / pattern; null with a reason in [error] for anything else. */
        fun parse(text: String): HookOn {
            val t = text.trim().replace(Regex("\\s+"), " ")
            require(t.isNotEmpty()) { "a hook needs 'on' (e.g. 'lane enter done' or 'ticket.commented')" }
            if (t.startsWith("every ", ignoreCase = true)) {
                val n = Regex("""every (\d{1,7})s""", RegexOption.IGNORE_CASE).matchEntire(t)?.groupValues?.get(1)?.toLongOrNull()
                require(n != null && n > 0) { "bad timer hook '$t' (expected: every <N>s, e.g. every 30s)" }
                return Every(n)
            }
            if (t.startsWith("lane ", ignoreCase = true)) {
                val parts = t.split(' ')
                val dir = parts.getOrNull(1)?.lowercase()
                require(parts.size == 3 && (dir == "enter" || dir == "exit") && LANE.matches(parts[2])) { "bad lane hook '$t' (expected: lane enter <lane> or lane exit <lane>)" }
                return Lane(parts[2].lowercase(), dir == "exit")
            }
            val core = if (t == "*") "x" else t.removeSuffix(".*").removeSuffix(".")
            require(NAME.matches(core)) { "bad event '$t' (a dotted lower-case name like ticket.commented, a prefix 'ticket.*', or 'lane enter <lane>', 'lane exit <lane>')" }
            return Named(t)
        }
    }
}

/** One hook: when [on] happens, run the stored script [script] with [args] plus the event payload as its input. */
data class Hook(
    val id: String,
    val on: HookOn,
    val script: String,
    val args: JsonObject = JsonObject(emptyMap()),
    val enabled: Boolean = true,
    /** HOOK-OWNER-1: who last edited it (audit only, never a role test). A mail hook (`to` set) runs as this session; every other hook runs as the project. */
    val ownerSession: String? = null,
    val ownerRole: String? = null,
    val ownerIsOwner: Boolean = false,
    /** MAIL-HOOK-1b: a `mail.incoming` hook names exactly one session (the recipient it runs for); null for every other hook. */
    val to: String? = null,
    /**
     * PROJECT-ISOLATION-1: true = the hook also runs for events that name NO project (daemon.started, a project-less emit). A hook
     * never runs for another project's events, global or not.
     */
    val global: Boolean = false,
    /** SCRIPT-ARSENAL-2: true = read from the app-wide hook file (an arsenal hook): it runs for EVERY project's events, as that project. Never stored in a project's hooks.json. */
    val arsenal: Boolean = false,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id); put("on", on.toString()); put("script", script)
        if (args.isNotEmpty()) put("args", args)
        if (!enabled) put("enabled", false)
        ownerSession?.let { put("ownerSession", it) }
        ownerRole?.let { put("ownerRole", it) }
        if (ownerIsOwner) put("ownerIsOwner", true)
        to?.let { put("to", it) }
        if (global) put("global", true)
    }

    fun line(): String = "$id  on $on${to?.let { " to $it" } ?: ""} -> $script${ownerSession?.let { " (edited by $it${ownerRole?.let { r -> "/$r" } ?: ""})" } ?: ""}${if (args.isEmpty()) "" else " $args"}${if (global) "  [global]" else ""}${if (arsenal) "  [arsenal: every project]" else ""}${if (enabled) "" else "  [disabled]"}"
}

/**
 * SCRIPT-5: the hooks of a project, DATA read from `<project>/.infantry/scripts/hooks.json` through the project file surface (next to
 * the scripts they name): `{"version":1,"hooks":[{"id":"merge-on-done","on":"lane enter done","script":"on-ticket-done","args":{}}]}`.
 * Read at each event with the file's mtime as the cache key, so an edit on disk or a `script hook add` is seen at once.
 */
class HookStore(private val files: (String) -> ProjectFiles, private val arsenalFile: java.io.File?) {
    constructor(files: (String) -> ProjectFiles) : this(files, null)

    private data class Cached(val stamp: Pair<Long, Long>?, val hooks: List<Hook>, val error: String?)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Cached>()

    /** The hooks of [projectId]; a missing file is none. A file that does not parse is none too, with the reason in [error]. */
    fun list(projectId: String): List<Hook> {
        val own = load(projectId).hooks
        // SCRIPT-ARSENAL-2: the app-wide hooks run for every project; a project hook of the same id wins (the project opts out by shadowing it).
        return own + arsenalHooks().filter { g -> own.none { it.id == g.id } }
    }

    /** SCRIPT-ARSENAL-2: only the project's own hooks, without the arsenal ones. */
    fun listOwn(projectId: String): List<Hook> = load(projectId).hooks

    /** SCRIPT-ARSENAL-2: the app-wide hooks (`<state>/global-hooks.json`, same format as hooks.json). */
    fun arsenalHooks(): List<Hook> {
        val f = arsenalFile?.takeIf { it.isFile } ?: return emptyList()
        return parse(f.readText(Charsets.UTF_8)).getOrDefault(emptyList()).map { it.copy(arsenal = true) }
    }

    @Synchronized fun addArsenal(hook: Hook) {
        val f = arsenalFile ?: throw IllegalStateException("this daemon has no global hook file")
        val cur = if (f.isFile) parse(f.readText(Charsets.UTF_8)).getOrElse { throw IllegalStateException("${f.name} does not parse: ${it.message}") } else emptyList()
        require(cur.none { it.id == hook.id }) { "global hook '${hook.id}' already exists (remove it first)" }
        writeArsenal(f, cur + hook)
    }

    @Synchronized fun removeArsenal(id: String): Boolean {
        val f = arsenalFile?.takeIf { it.isFile } ?: return false
        val cur = parse(f.readText(Charsets.UTF_8)).getOrElse { throw IllegalStateException("${f.name} does not parse: ${it.message}") }
        val next = cur.filter { it.id != id }
        if (next.size == cur.size) return false
        writeArsenal(f, next); return true
    }

    private fun writeArsenal(f: java.io.File, hooks: List<Hook>) {
        f.parentFile?.mkdirs()
        val doc = buildJsonObject { put("version", 1); put("hooks", buildJsonArray { hooks.forEach { add(it.toJson()) } }) }
        f.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), doc), Charsets.UTF_8)
    }

    /** Why `hooks.json` of [projectId] could not be read, or null. */
    fun error(projectId: String): String? = load(projectId).error

    fun add(projectId: String, hook: Hook) = mutate(projectId) { current ->
        require(current.none { it.id == hook.id }) { "hook '${hook.id}' already exists (remove it first)" }
        current + hook
    }

    /** True when a hook with [id] was there. */
    fun remove(projectId: String, id: String): Boolean {
        var found = false
        mutate(projectId) { current -> current.filter { it.id != id }.also { found = it.size != current.size } }
        return found
    }

    /** MAIL-HOOK-1b: drops every `mail.incoming` hook attached to [session] (it ended), in each of [projects]. Returns how many went. */
    fun removeForSession(projects: List<String>, session: String): Int {
        var n = 0
        for (p in projects) runCatching {
            if (list(p).none { it.to == session }) return@runCatching
            mutate(p) { cur -> cur.filter { it.to != session }.also { n += cur.size - it.size } }
        }
        return n
    }

    @Synchronized private fun mutate(projectId: String, change: (List<Hook>) -> List<Hook>) {
        val f = files(projectId)
        val raw = f.get(PATH)
        // Refuse to overwrite a file we cannot read: the owner's hand edit must not be lost to a failed parse.
        val current = if (raw == null) emptyList() else parse(raw.toString(Charsets.UTF_8)).getOrElse { throw IllegalStateException("$PATH does not parse: ${it.message}") }
        val next = change(current)
        val doc = buildJsonObject { put("version", 1); put("hooks", buildJsonArray { next.forEach { add(it.toJson()) } }) }
        f.put(PATH, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), doc).toByteArray(Charsets.UTF_8))
        cache.remove(projectId)
    }

    private fun load(projectId: String): Cached {
        val f = files(projectId)
        val st = runCatching { f.stat(PATH) }.getOrNull()
        val stamp = st?.let { it.modifiedMillis to it.size }
        cache[projectId]?.takeIf { it.stamp == stamp }?.let { return it }
        val fresh = if (st == null) Cached(null, emptyList(), null) else {
            val raw = f.get(PATH)?.toString(Charsets.UTF_8)
            if (raw == null) Cached(stamp, emptyList(), null) else parse(raw).fold({ Cached(stamp, it, null) }, { Cached(stamp, emptyList(), it.message) })
        }
        cache[projectId] = fresh
        return fresh
    }

    companion object {
        const val PATH = "${ScriptStore.DIR}/hooks.json"
        private val ID = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")

        fun validId(id: String) = ID.matches(id)

        /** Parses a whole `hooks.json`; a bad entry fails the file (named in the message) rather than silently dropping a hook. */
        fun parse(text: String): Result<List<Hook>> = runCatching {
            val root = Json.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException("not a JSON object")
            val arr = root["hooks"] as? JsonArray ?: return@runCatching emptyList()
            val seen = HashSet<String>()
            arr.mapIndexed { i, el ->
                val o = el as? JsonObject ?: throw IllegalArgumentException("hooks[$i] is not an object")
                fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                val id = s("id") ?: throw IllegalArgumentException("hooks[$i] has no id")
                require(validId(id)) { "hook id '$id' is bad (letters, digits, '_', '-', '.'; at most 64)" }
                require(seen.add(id)) { "hook id '$id' is listed twice" }
                val script = s("script") ?: throw IllegalArgumentException("hook '$id' names no script")
                Hook(
                    id, HookOn.parse(s("on").orEmpty()), script, (o["args"] as? JsonObject) ?: JsonObject(emptyMap()),
                    (o["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true, s("ownerSession"), s("ownerRole"), (o["ownerIsOwner"] as? JsonPrimitive)?.booleanOrNull == true, s("to"),
                    (o["global"] as? JsonPrimitive)?.booleanOrNull == true,
                )
            }
        }
    }
}
