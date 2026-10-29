package com.superiorsoftllc.agenthq.runner

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
    /** In an override: drop the default entry of this [name]. */
    val disabled: Boolean = false,
)

object ToolProfiles {
    const val RESOURCE = "/watch/tool-profiles.json"
    const val SETTING = "watch.toolProfiles"
    const val ENV = "AGENTHQ_TOOL_PROFILES"

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
        (p.daemons + p.notifyOn).forEach { re ->
            runCatching { Regex(re) }.exceptionOrNull()?.let { throw IllegalArgumentException("tool profile '${p.name}': bad regex '$re' (${it.message})") }
        }
        p.shim?.forEach { s -> require(s in p.commands) { "tool profile '${p.name}': shim '$s' is not one of its commands" } }
        (p.commands + p.shim.orEmpty()).forEach { c ->
            require(c.matches(Regex("[A-Za-z0-9._+-]{1,64}"))) { "tool profile '${p.name}': bad command name '$c'" }
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

    /** The default `--notify` patterns: every profile's [ToolProfile.notifyOn]. */
    fun notifyPatterns(list: List<ToolProfile> = active): List<String> = list.flatMap { it.notifyOn }.distinct()
}
