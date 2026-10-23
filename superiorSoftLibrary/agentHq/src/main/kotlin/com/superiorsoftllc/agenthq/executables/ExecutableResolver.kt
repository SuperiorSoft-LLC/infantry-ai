package com.superiorsoftllc.agenthq.executables

import java.io.File

/**
 * CLIPATH-1: the ONE place that decides which file a CLI launch runs (claude, opencode, pi, a descriptor harness, git).
 *
 * Every launcher asks [resolve] / [require] instead of spawning a bare command name, because an installed app's PATH is
 * frequently not the user's shell PATH. The order is fixed and is the contract:
 *
 *  1. the user's override from Settings ("CLI locations"), stored by the daemon ([ExecutableOverrides]);
 *  2. the tool's environment variable, when it has one (`AGENTHQ_CLAUDE_EXE`, ...), the pre-CLIPATH deployment knob;
 *  3. the tool's KNOWN DEFAULT INSTALL LOCATIONS for this OS ([CliTool.defaults], one table per tool in [CliTools]);
 *  4. PATH (on Windows only names with a PATHEXT extension: an extensionless npm shim is not a Windows executable).
 *
 * A set override that is no longer an executable file is NOT silently skipped: the user said "use this one", so the
 * tool is reported unavailable with that reason rather than quietly running a different binary. A broken environment
 * variable is skipped (it is listed in [Resolution.tried]), as before CLIPATH-1 it was never validated at all.
 *
 * Nothing here spawns or caches anything: a resolution is a few file stats, done when a launch or a listing asks.
 */
class ExecutableResolver(
    private val environment: ExecutableEnvironment,
    private val overrides: ExecutableOverrides = ExecutableOverrides.NONE,
) {
    /** Where [tool] resolves right now; never throws (an unreadable override store counts as no override). */
    fun resolve(tool: CliTool): Resolution {
        val tried = ArrayList<String>()
        val override = runCatching { overrides.get(tool.id) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        if (override != null) {
            val problem = problem(tool, override)
            return if (problem == null) {
                Resolution(tool, override, Source.OVERRIDE, override, listOf(override), null)
            } else {
                Resolution(
                    tool, null, null, override, listOf(override),
                    "${tool.label}: the location set in Settings ($override) $problem. Fix it or clear it to use the default.",
                )
            }
        }

        tool.envVar?.let { name -> environment.env(name)?.trim()?.takeIf { it.isNotEmpty() } }?.let { fromEnv ->
            tried += fromEnv
            if (problem(tool, fromEnv) == null) return Resolution(tool, fromEnv, Source.ENVIRONMENT, null, tried.toList(), null)
        }

        for (candidate in tool.defaults(environment).distinct()) {
            tried += candidate
            if (problem(tool, candidate) == null) return Resolution(tool, candidate, Source.DEFAULT_LOCATION, null, tried.toList(), null)
        }

        for (candidate in pathCandidates(tool)) {
            tried += candidate
            if (problem(tool, candidate) == null) return Resolution(tool, candidate, Source.PATH, null, tried.toList(), null)
        }

        val names = commandFileNames(tool).joinToString(", ")
        return Resolution(
            tool, null, null, null, tried.toList(),
            "${tool.label} not found. Tried ${tried.size} location(s) and PATH ($names). " +
                "Install it, or set its location in Settings > CLI locations.",
        )
    }

    /** The resolved path, or an [IllegalStateException] whose message is the not-found reason (with the tried paths). */
    fun require(tool: CliTool): String {
        val resolution = resolve(tool)
        return resolution.path ?: throw IllegalStateException(resolution.describeFailure())
    }

    /** Why [path] cannot be [tool]'s executable, or null when it can. What Settings refuses a save with. */
    fun validate(tool: CliTool, path: String): String? {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return "the path is empty"
        if (!environment.isAbsolute(trimmed)) return "'$trimmed' is not an absolute path"
        return problem(tool, trimmed)?.let { "'$trimmed' $it" }
    }

    private fun problem(tool: CliTool, path: String): String? = when {
        !environment.exists(path) -> "does not exist"
        environment.isDirectory(path) -> "is a directory, not the executable file"
        !isLaunchable(tool, path) -> if (environment.os == HostOs.WINDOWS) {
            "is not a Windows executable (expected ${(windowsExtensions() + tool.extraExtensions).joinToString("/")})"
        } else {
            "is not executable (chmod +x it)"
        }
        else -> null
    }

    private fun isLaunchable(tool: CliTool, path: String): Boolean {
        val ext = extensionOf(path)
        if (ext != null && tool.extraExtensions.any { it.equals(ext, ignoreCase = true) }) return true
        return if (environment.os == HostOs.WINDOWS) {
            ext != null && windowsExtensions().any { it.equals(ext, ignoreCase = true) }
        } else {
            environment.isExecutable(path)
        }
    }

    /** Every file PATH could hold for [tool], in PATH order: dir x name x (Windows) extension. */
    private fun pathCandidates(tool: CliTool): List<String> {
        val raw = environment.env("PATH") ?: environment.env("Path") ?: return emptyList()
        val separator = if (environment.os == HostOs.WINDOWS) ';' else ':'
        val dirs = raw.split(separator).map { it.trim().trim('"') }.filter { it.isNotEmpty() }.distinct()
        return dirs.flatMap { dir -> commandFileNames(tool).map { environment.join(dir, it) } }
    }

    private fun commandFileNames(tool: CliTool): List<String> =
        if (environment.os == HostOs.WINDOWS) {
            tool.commandNames.flatMap { name -> if (extensionOf(name) != null) listOf(name) else windowsExtensions().map { name + it.lowercase() } }
        } else {
            tool.commandNames
        }

    private fun windowsExtensions(): List<String> =
        (environment.env("PATHEXT")?.split(';')?.map { it.trim() }?.filter { it.startsWith(".") } ?: emptyList())
            .ifEmpty { DEFAULT_PATHEXT }
            .filter { it.uppercase() in LAUNCHABLE_WINDOWS }

    private fun extensionOf(path: String): String? {
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) null else name.substring(dot)
    }

    /** Where the resolution came from; [wire] is the `cli_locations` frame's `source`. */
    enum class Source(val wire: String) { OVERRIDE("override"), ENVIRONMENT("env"), DEFAULT_LOCATION("default"), PATH("path") }

    /** One tool's resolution. [path] null = not found, and then [reason] says why and [tried] lists every candidate. */
    data class Resolution(
        val tool: CliTool,
        val path: String?,
        val source: Source?,
        val override: String?,
        val tried: List<String>,
        val reason: String?,
    ) {
        val found: Boolean get() = path != null

        /** The reason plus the tried paths, one per line: what a failed launch and the availability report say. */
        fun describeFailure(): String =
            (reason ?: "${tool.label} not found") +
                if (tried.isEmpty()) "" else tried.joinToString(separator = "\n  ", prefix = "\nTried:\n  ")
    }

    companion object {
        /** Windows' own default PATHEXT, reduced to what CreateProcess can start directly or via cmd. */
        private val DEFAULT_PATHEXT = listOf(".COM", ".EXE", ".BAT", ".CMD")
        private val LAUNCHABLE_WINDOWS = DEFAULT_PATHEXT.toSet()

        /** The resolver over the real OS, with no stored overrides (what a launch outside a daemon uses). */
        fun system(overrides: ExecutableOverrides = ExecutableOverrides.NONE): ExecutableResolver =
            ExecutableResolver(SystemExecutableEnvironment, overrides)
    }
}

/** The host OS as the resolver needs it. */
enum class HostOs {
    WINDOWS, MAC, LINUX;

    companion object {
        fun current(): HostOs {
            val name = System.getProperty("os.name").orEmpty().lowercase()
            return when {
                name.startsWith("windows") -> WINDOWS
                name.contains("mac") || name.contains("darwin") -> MAC
                else -> LINUX
            }
        }
    }
}

/**
 * What the resolver reads from the machine: the OS, environment variables and file facts. Replaceable so the
 * resolution order is testable on any OS with a fake file system (see `ExecutableResolverTest`).
 */
interface ExecutableEnvironment {
    val os: HostOs
    fun env(name: String): String?
    fun home(): String?
    fun exists(path: String): Boolean
    fun isDirectory(path: String): Boolean

    /** Unix only: the execute bit. Windows decides by extension. */
    fun isExecutable(path: String): Boolean
    fun isAbsolute(path: String): Boolean

    /** [base] / [parts], with this OS's separator. */
    fun join(base: String, vararg parts: String): String {
        val sep = if (os == HostOs.WINDOWS) "\\" else "/"
        return parts.fold(base.trimEnd('/', '\\')) { acc, part -> acc + sep + part.trim('/', '\\').replace('/', sep[0]).replace('\\', sep[0]) }
    }
}

/** The real machine. */
object SystemExecutableEnvironment : ExecutableEnvironment {
    override val os: HostOs = HostOs.current()
    override fun env(name: String): String? = System.getenv(name)
    override fun home(): String? = System.getProperty("user.home")
    override fun exists(path: String): Boolean = File(path).exists()
    override fun isDirectory(path: String): Boolean = File(path).isDirectory
    override fun isExecutable(path: String): Boolean = File(path).let { it.isFile && it.canExecute() }
    override fun isAbsolute(path: String): Boolean = File(path).isAbsolute
}

/**
 * The per-tool overrides the user set in Settings. Null / blank = none. [set] with null clears. The daemon's
 * implementation is [StoredExecutableOverrides] (the settings table); tests use [InMemory].
 */
interface ExecutableOverrides {
    fun get(toolId: String): String?
    fun set(toolId: String, path: String?)

    class InMemory(initial: Map<String, String> = emptyMap()) : ExecutableOverrides {
        private val values = java.util.concurrent.ConcurrentHashMap(initial)
        override fun get(toolId: String): String? = values[toolId]
        override fun set(toolId: String, path: String?) {
            if (path.isNullOrBlank()) values.remove(toolId) else values[toolId] = path.trim()
        }
    }

    companion object {
        val NONE: ExecutableOverrides = object : ExecutableOverrides {
            override fun get(toolId: String): String? = null
            override fun set(toolId: String, path: String?) = throw UnsupportedOperationException("no override store")
        }
    }
}

/**
 * The resolver every launcher uses. Harness plugins are built by the ServiceLoader with no arguments, so they cannot
 * be handed one; the daemon installs its store-backed resolver here at start ([install]) and restores the previous one
 * when it closes. Outside a daemon (CLI tools, tests) it is the system resolver with no overrides.
 */
object CliExecutables {
    @Volatile
    var resolver: ExecutableResolver = ExecutableResolver.system()
        private set

    /** Install [next]; the returned function puts the previous resolver back (only if [next] is still installed). */
    fun install(next: ExecutableResolver): () -> Unit {
        val previous = resolver
        resolver = next
        return { if (resolver === next) resolver = previous }
    }

    fun resolve(tool: CliTool): ExecutableResolver.Resolution = resolver.resolve(tool)

    fun require(tool: CliTool): String = resolver.require(tool)
}
