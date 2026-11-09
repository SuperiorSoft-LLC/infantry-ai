// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runner

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * STALL-3 (ticket d258b738) item 2: what the watch knows about a build tool is DATA, never code. The runner, the PATH
 * shims, the harness hooks and the process-tree watch read the [active] list and name no tool themselves, so maven, npm,
 * cargo, make or a tool nobody has heard of is one more entry (docs/STALL.md, "Tool profiles").
 *
 * The defaults ship as the resource [RESOURCE]; the setting [SETTING] (a JSON array of entries, same format) is merged
 * over them by [ToolProfile.name]: an entry with a known name replaces that default, `"disabled": true` drops it, a new
 * name adds a tool. The daemon hands the merged list to every session as env [ENV], so the CLI runner a worker starts
 * reads the same list.
 */
@Serializable
data class ToolProfile(
    /** The entry's key (merge and disable by it). */
    val name: String,
    /** Program base names (`gradlew` matches `./gradlew`, `gradlew.bat`, `C:\x\gradlew.cmd`). */
    val commands: List<String> = emptyList(),
    /** The [commands] that get a PATH shim; null = all of them (a path-named wrapper like `gradlew` never meets PATH). */
    val shim: List<String>? = null,
    /** A call of one of [commands] is long by nature (the hooks rewrite it into the watched runner even in the foreground). */
    val long: Boolean = true,
    /** Long only when one of these arguments is present (`java` + `-jar`); empty = always. */
    val longIf: List<String> = emptyList(),
    /** Arguments the runner inserts right after the program (`--console=plain`), unless [argsUnless] says the caller chose. */
    val args: List<String> = emptyList(),
    /** Argument prefixes that mean the caller already chose (`--console`): [args] are then left out. */
    val argsUnless: List<String> = emptyList(),
    /** Regexes of processes the tool leaves running on purpose (build daemons): never waited on, never a survivor. */
    val daemons: List<String> = emptyList(),
    /** Log-line regexes a `bg run --notify` job is watched for by default (a build verdict, a compiler error). */
    val notifyOn: List<String> = emptyList(),
    /**
     * STALL-4: output-line regexes that mean the command failed whatever its exit code (`BUILD FAILED`): an exit 0 with
     * one of them in the output is reported as `exit=0 but output contains failure marker: ...`.
     */
    val failureMarkers: List<String> = emptyList(),
    /**
     * STALL-4: command-line regexes whose group 1 is a file the output is sent to (`> x.log`, `| tee x.log`,
     * `Out-File x.log`). Followed only with `--follow` / `watch.followRedirects`; else named in the quiet notice's hint.
     */
    val redirects: List<String> = emptyList(),
    /** STALL-4: options of [commands] whose value is a log file (`--log-file`, as `--log-file x` or `--log-file=x`). */
    val logFlags: List<String> = emptyList(),
    /**
     * STACK-NEUTRAL-1: command-line regexes of the tool's CLIENT process (the one a build is started with): a live session
     * running one keeps a [sharedDaemon] it may use alive when the session that started the daemon closes.
     */
    val clients: List<String> = emptyList(),
    /** STACK-NEUTRAL-1: the [daemons] are shared between sessions and the gate: spared or handed over at a session's close. */
    val sharedDaemon: Boolean = false,
    /** STACK-NEUTRAL-1: process image regexes (full match, `.exe` optional) the processes list shows when no session owns them. */
    val heavyImages: List<String> = emptyList(),
    /** STACK-NEUTRAL-1: command-line regexes (found anywhere) the ledger may adopt by a session's worktree path. */
    val heavyCommands: List<String> = emptyList(),
    /** STACK-NEUTRAL-1: the concurrency env every worker session gets for this tool, from the build limits. */
    val env: List<EnvTemplate> = emptyList(),
    /** STACK-NEUTRAL-3: progress readers of this tool's output, selected with `infantry run/progress --reader <name>`. */
    val progress: List<ProgressSpec> = emptyList(),
    /**
     * PROC-LEAK-1: the argv that stops the tool's [daemons] gracefully (`./gradlew --stop`), run in the closing session's
     * worktree before its daemons are killed; empty = no graceful step (the kill follows the grace at once). The first entry
     * is tried as given, then with the OS's wrapper suffixes (`.bat`, `.cmd`) on Windows.
     */
    val stop: List<String> = emptyList(),
    /**
     * PROC-LEAK-1: command-line regexes of a short-lived CLIENT that should not hang (a CLI call): one that used no CPU for
     * `limits.processes.stuckMinutes` is reported stuck on its session.
     */
    val stuck: List<String> = emptyList(),
    /**
     * PROC-LEAK-2: where one of [daemons] names the directory it last built in (Gradle: its per-pid daemon log), so the
     * process watch can give a detached daemon whose client is gone to the session whose worktree that is. Null = none.
     */
    val daemonDir: DaemonDirSource? = null,
    /** CI-TESTS-1: where a project pins this tool's version (the health report's environment lists it). Null = not reported. */
    val version: VersionSource? = null,
    /** In an override: drop the default entry of this [name]. */
    val disabled: Boolean = false,
)

/**
 * PROC-LEAK-2: [paths] are file templates (`${pid}`, `${home}`, `${env:NAME}`; `*` = one directory level; a template naming an
 * unset variable is skipped); [match] is a regex over the file's lines whose group 1 is the directory (the last match wins).
 */
@Serializable
data class DaemonDirSource(val paths: List<String> = emptyList(), val match: String = "")

/**
 * CI-TESTS-1: [file] is a path relative to the project root; [match] is a regex over its lines whose group 1 is the
 * version the project pins (the first matching line wins).
 */
@Serializable
data class VersionSource(val file: String, val match: String) {
    /** The version [projectDir] pins, or null when the file is missing or no line matches. */
    fun read(projectDir: java.io.File): String? = runCatching {
        val re = Regex(match)
        java.io.File(projectDir, file).takeIf { it.isFile }?.readLines()?.firstNotNullOfOrNull { re.find(it)?.groupValues?.getOrNull(1) }
    }.getOrNull()
}

/**
 * STACK-NEUTRAL-1: one env value a worker session gets. [value] may name the build limits as `${maxWorkers}` and
 * `${maxParallelForks}`; [onlyIf] (one of [LIMITS]) leaves the template out while that limit is 0 (unlimited). Templates of
 * one [name] are joined with [join], after the daemon's own value of that variable when [inherit] is set.
 */
@Serializable
data class EnvTemplate(
    val name: String,
    val value: String,
    val onlyIf: String? = null,
    val join: String = " ",
    val inherit: Boolean = true,
) {
    companion object {
        const val MAX_WORKERS = "maxWorkers"
        const val MAX_PARALLEL_FORKS = "maxParallelForks"
        val LIMITS = setOf(MAX_WORKERS, MAX_PARALLEL_FORKS)
    }
}

/**
 * STACK-NEUTRAL-3: one output line a progress reader reports on. [reader] is the `--reader` name (specs of one name are
 * tried in order); [match] a regex found in the line; [report] is `status`, `step`, `count` or `progress`; [label] a
 * template where `$1`.. are the match groups (for `count` / `progress` it must come out as the number).
 */
@Serializable
data class ProgressSpec(val reader: String, val match: String, val report: String = "status", val label: String? = null)

object ToolProfiles {
    const val RESOURCE = "/watch/tool-profiles.json"
    const val SETTING = "watch.toolProfiles"
    const val ENV = "INFANTRY_TOOL_PROFILES"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val serializer = ListSerializer(ToolProfile.serializer())

    /** The shipped defaults. */
    val DEFAULTS: List<ToolProfile> by lazy {
        val text = ToolProfiles::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("tool profiles resource $RESOURCE missing")
        parse(text)
    }

    /** The list in force in this process: the daemon sets it from the settings, the CLI from [ENV]. */
    @Volatile
    var active: List<ToolProfile> = emptyList()
        get() = field.ifEmpty { DEFAULTS }

    fun parse(text: String): List<ToolProfile> = json.decodeFromString(serializer, text)

    fun encode(list: List<ToolProfile>): String = json.encodeToString(serializer, list)

    /** [defaults] with [overrides] merged in by name (replace, `disabled` drops, a new name appends). Throws on a bad entry. */
    fun merge(defaults: List<ToolProfile>, overrides: List<ToolProfile>): List<ToolProfile> {
        overrides.forEach(::validate)
        val byName = LinkedHashMap<String, ToolProfile>()
        defaults.forEach { byName[it.name] = it }
        overrides.forEach { o -> if (o.disabled) byName.remove(o.name) else byName[o.name] = o }
        return byName.values.toList()
    }

    fun validate(p: ToolProfile) {
        require(p.name.isNotBlank()) { "a tool profile needs a name" }
        p.version?.let { v ->
            require(v.file.isNotBlank()) { "tool profile '${p.name}': version needs a file" }
            runCatching { Regex(v.match) }.exceptionOrNull()?.let { throw IllegalArgumentException("tool profile '${p.name}': bad regex '${v.match}' (${it.message})") }
        }
        (p.daemons + p.notifyOn + p.failureMarkers + p.redirects + p.clients + p.heavyImages + p.heavyCommands).forEach { re ->
            runCatching { Regex(re) }.exceptionOrNull()?.let { throw IllegalArgumentException("tool profile '${p.name}': bad regex '$re' (${it.message})") }
        }
        p.progress.forEach { s ->
            require(s.reader.matches(Regex("[A-Za-z0-9._-]{1,32}"))) { "tool profile '${p.name}': bad progress reader name '${s.reader}'" }
            require(s.report in setOf("status", "step", "count", "progress")) { "tool profile '${p.name}': progress report must be status|step|count|progress, was '${s.report}'" }
            runCatching { Regex(s.match) }.exceptionOrNull()?.let { throw IllegalArgumentException("tool profile '${p.name}': bad regex '${s.match}' (${it.message})") }
        }
        p.shim?.forEach { s -> require(s in p.commands) { "tool profile '${p.name}': shim '$s' is not one of its commands" } }
        (p.commands + p.shim.orEmpty()).forEach { c ->
            require(c.matches(Regex("[A-Za-z0-9._+-]{1,64}"))) { "tool profile '${p.name}': bad command name '$c'" }
        }
        p.env.forEach { t ->
            require(t.name.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,127}"))) { "tool profile '${p.name}': bad env name '${t.name}'" }
            require(t.onlyIf == null || t.onlyIf in EnvTemplate.LIMITS) {
                "tool profile '${p.name}': env onlyIf must be one of ${EnvTemplate.LIMITS.joinToString()}, was '${t.onlyIf}'"
            }
        }
    }

    /** The list from env [ENV] (the CLI side); null when unset or unreadable. */
    fun fromEnv(getenv: (String) -> String?): List<ToolProfile>? =
        getenv(ENV)?.takeIf { it.isNotBlank() }?.let { runCatching { parse(it) }.getOrNull() }?.takeIf { it.isNotEmpty() }

    /** A program word's base name: path, case and a Windows/script extension dropped. */
    fun baseName(program: String): String =
        program.substringAfterLast('/').substringAfterLast('\\').lowercase()
            .removeSuffix(".bat").removeSuffix(".cmd").removeSuffix(".exe").removeSuffix(".sh").removeSuffix(".ps1")

    fun profileFor(program: String, list: List<ToolProfile> = active): ToolProfile? {
        val base = baseName(program)
        return list.firstOrNull { p -> p.commands.any { it.equals(base, ignoreCase = true) } }
    }

    /** The programs that get a PATH shim. */
    fun shimPrograms(list: List<ToolProfile> = active): List<String> =
        list.flatMap { it.shim ?: it.commands }.distinct()

    /** True when [program] called with [args] is long by its profile. */
    fun isLong(program: String, args: List<String>, list: List<ToolProfile> = active): Boolean {
        val p = profileFor(program, list) ?: return false
        if (!p.long) return false
        return p.longIf.isEmpty() || args.any { it in p.longIf }
    }

    /** [cmd] with its profile's extra args after the program, unless the caller already chose. */
    fun withArgs(cmd: List<String>, list: List<ToolProfile> = active): List<String> {
        val prog = cmd.firstOrNull() ?: return cmd
        val p = profileFor(File(prog).name, list) ?: return cmd
        if (p.args.isEmpty()) return cmd
        if (cmd.drop(1).any { a -> p.argsUnless.any { a.startsWith(it) } }) return cmd
        return listOf(prog) + p.args + cmd.drop(1)
    }

    /** One regex over every profile's detached daemons (matches nothing when there are none). */
    fun daemonRegex(list: List<ToolProfile> = active): Regex {
        val parts = list.flatMap { it.daemons }
        return if (parts.isEmpty()) Regex("(?!)") else Regex("(?i)" + parts.joinToString("|") { "(?:$it)" })
    }

    /** STACK-NEUTRAL-1: the daemons of [ToolProfile.sharedDaemon] profiles (matches nothing when there are none). */
    fun sharedDaemonRegex(list: List<ToolProfile> = active): Regex = daemonRegex(list.filter { it.sharedDaemon })

    /** STACK-NEUTRAL-1: the name of the profile whose [ToolProfile.daemons] match [command], or null. */
    fun daemonProfile(command: String, list: List<ToolProfile> = active): String? =
        list.firstOrNull { p -> p.daemons.isNotEmpty() && daemonRegex(listOf(p)).containsMatchIn(command) }?.name

    /** STACK-NEUTRAL-1: one regex over every profile's [ToolProfile.clients] (matches nothing when there are none). */
    fun clientRegex(list: List<ToolProfile> = active): Regex = anyOf(list.flatMap { it.clients })

    /** STACK-NEUTRAL-1: process images worth listing when unattributed; use with [Regex.matches]. */
    fun heavyImageRegex(list: List<ToolProfile> = active): Regex {
        val parts = list.flatMap { it.heavyImages }.distinct()
        return if (parts.isEmpty()) Regex("(?!)") else Regex("(?i)(?:" + parts.joinToString("|") { "(?:$it)" } + ")(?:\\.exe)?")
    }

    /** STACK-NEUTRAL-1: command lines the ledger may adopt by worktree path; use with [Regex.containsMatchIn]. */
    fun heavyCommandRegex(list: List<ToolProfile> = active): Regex = anyOf(list.flatMap { it.heavyCommands })

    private fun anyOf(parts: List<String>): Regex =
        if (parts.isEmpty()) Regex("(?!)") else Regex("(?i)" + parts.distinct().joinToString("|") { "(?:$it)" })

    /**
     * STACK-NEUTRAL-1: every profile's [ToolProfile.env] filled from the limits, in profile order; [inherited] is the
     * daemon's own environment (a variable the templates name is appended to, never replaced). 0 = unlimited.
     */
    fun sessionEnv(maxWorkers: Int, maxParallelForks: Int, inherited: (String) -> String? = { null }, list: List<ToolProfile> = active): Map<String, String> {
        val values = mapOf(EnvTemplate.MAX_WORKERS to maxWorkers, EnvTemplate.MAX_PARALLEL_FORKS to maxParallelForks)
        val out = LinkedHashMap<String, String>()
        for (t in list.flatMap { it.env }) {
            if (t.onlyIf != null && (values[t.onlyIf] ?: 0) <= 0) continue
            val v = values.entries.fold(t.value) { acc, (k, n) -> acc.replace("\${$k}", n.toString()) }
            val before = out[t.name] ?: (if (t.inherit) inherited(t.name)?.trim()?.takeIf { it.isNotEmpty() } else null)
            out[t.name] = if (before == null) v else before + t.join + v
        }
        return out
    }

    /** The default `--notify` patterns: every profile's [ToolProfile.notifyOn]. */
    fun notifyPatterns(list: List<ToolProfile> = active): List<String> = list.flatMap { it.notifyOn }.distinct()

    /** STALL-4: every profile's failure markers, compiled (bad ones dropped). */
    fun failureMarkers(list: List<ToolProfile> = active): List<Regex> =
        list.flatMap { it.failureMarkers }.distinct().mapNotNull { runCatching { Regex(it) }.getOrNull() }

    /** Paths that are never a log: the null devices. */
    private val NULL_TARGETS = setOf("/dev/null", "nul", "\$null", "nul:")

    /**
     * STALL-4 item 2: the files [commandLine] sends its output to, by every profile's [ToolProfile.redirects] and, for the
     * program words of a profile, its [ToolProfile.logFlags]; relative paths taken against [cwd]. A path with a shell
     * variable (`$X`, `%X%`) cannot be known here and is skipped, as are the null devices.
     */
    fun redirectTargets(commandLine: String, cwd: File, list: List<ToolProfile> = active): List<String> {
        val found = ArrayList<String>()
        for (re in list.flatMap { it.redirects }.distinct()) {
            val r = runCatching { Regex(re) }.getOrNull() ?: continue
            r.findAll(commandLine).forEach { m -> m.groups[1]?.value?.let(found::add) }
        }
        val words = commandLine.split(Regex("\\s+")).filter { it.isNotEmpty() }
        for (p in list.filter { it.logFlags.isNotEmpty() }) {
            if (words.none { w -> p.commands.any { it.equals(baseName(w), ignoreCase = true) } }) continue
            words.forEachIndexed { i, w ->
                for (flag in p.logFlags) {
                    if (w == flag) words.getOrNull(i + 1)?.let(found::add)
                    else if (w.startsWith("$flag=")) found += w.removePrefix("$flag=")
                }
            }
        }
        return found.asSequence()
            .map { it.trim().trim('"', '\'') }
            .filter { it.isNotEmpty() && it.lowercase() !in NULL_TARGETS && !it.contains('$') && !it.contains('%') && !it.startsWith("&") }
            .map { f -> File(f).let { if (it.isAbsolute) it else File(cwd, f) }.absoluteFile.normalize().path }
            .distinct().toList()
    }
}
