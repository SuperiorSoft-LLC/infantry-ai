package com.superiorsoftllc.infantry.runner

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * SANDBOX-1 (ticket d4c1dbd9): confine every agent CLI the daemon launches to its working directory plus
 * `limits.sandbox.allowedDirs`. Off by default (`limits.sandbox.mode` = 0): a launch is then exactly what it was.
 *
 * Two layers, both applied at the [ProcessRunner] seam ([SandboxRunner]) so no launch path can skip them:
 *  1. HARNESS: each CLI's own permission switches, generated per launch ([HarnessRestrictions]): shell and web tools off,
 *     file access outside the allowed dirs denied, edits to the CLI's own config denied. The daemon's MCP tools stay.
 *  2. OS (the backstop, Linux only): the command runs under `landlock-exec` (docker/landlock-exec.c): read-only system
 *     dirs, the CLI install and the user's home; read-write ONLY the working dir, the allowed dirs, the CLI's own state
 *     dirs and a scratch TMPDIR. The daemon's state dir (database, token, keystore) is out of reach. Fails closed: with
 *     the mode on and no working Landlock helper on Linux, the launch is refused, never run unconfined.
 *     Other OSes: NOT ENFORCED YET ([SandboxSupport.status] says so; nothing claims protection).
 * In both cases the agent gets the daemon's scoped AGENT token instead of the owner token ([SandboxConfig.agentToken]).
 */
data class SandboxConfig(
    val enabled: Boolean,
    val allowedDirs: List<String> = emptyList(),
    /** The scoped token agents receive as INFANTRY_TOKEN (mail, board, status; never settings or spawns). Null = none. */
    val agentToken: String? = null,
) {
    companion object {
        val OFF = SandboxConfig(false)
    }
}

object SandboxSupport {
    const val HELPER_ENV = "INFANTRY_SANDBOX_HELPER"
    const val HELPER_NAME = "landlock-exec"

    val isLinux: Boolean = System.getProperty("os.name").orEmpty().lowercase().startsWith("linux")

    /** The Landlock helper: [HELPER_ENV], else `landlock-exec` on PATH; null when there is none. */
    fun helper(env: (String) -> String? = System::getenv): File? {
        env(HELPER_ENV)?.takeIf { it.isNotBlank() }?.let { return File(it).takeIf(File::canExecute) }
        return env("PATH").orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }
            .map { File(it, HELPER_NAME) }.firstOrNull { it.isFile && it.canExecute() }
    }

    /** One line for the Limits tab and the startup banner: what the sandbox really does on this machine. */
    fun status(enabled: Boolean, helper: File? = helper()): String = when {
        !enabled -> "off"
        !isLinux -> "NOT ENFORCED YET on ${System.getProperty("os.name")}: OS confinement is Linux-only; only the harness permission layer and the scoped token apply"
        helper == null -> "ON but the Landlock helper ($HELPER_NAME) is missing: agent launches are REFUSED until it is installed or $HELPER_ENV names it"
        else -> "enforced (Landlock via ${helper.path}) + harness permission layer"
    }
}

/** Thrown instead of launching when the sandbox is on but cannot be enforced on Linux. */
class SandboxUnavailableException(message: String) : IllegalStateException(message)

/** Layer 1: the per-CLI permission settings. Pure, so the exact flags are assertable. See docs/docker-deploy.md, "Sandbox". */
object HarnessRestrictions {
    /** Which CLI [command] starts, by executable name (a wrapper `node .../pi` counts by its script). */
    fun cliOf(command: List<String>): String? {
        val names = command.take(3).map { File(it).name.lowercase().removeSuffix(".cmd").removeSuffix(".exe").removeSuffix(".js") }
        return names.firstOrNull { it in setOf("opencode", "pi", "claude") }
    }

    fun apply(spec: ProcessSpec, dirs: List<String>): ProcessSpec = when (cliOf(spec.command)) {
        "opencode" -> spec.copy(env = spec.env + opencodeEnv(spec.env[OPENCODE_CONFIG_CONTENT] ?: System.getenv(OPENCODE_CONFIG_CONTENT), dirs))
        // pi has no permission system (pi 1.4.0): only which tools exist. bash/powershell go; read/edit/write stay and are
        // path-confined by the OS layer alone. Mail goes through the infantry MCP server (the pi tool bridge, on in sandbox).
        "pi" -> spec.copy(command = spec.command + listOf("--exclude-tools", "bash,powershell"))
        // claude: tool deny rules + a permissions deny list for its own config.
        "claude" -> spec.copy(command = spec.command + claudeArgs(dirs))
        else -> spec
    }

    const val OPENCODE_CONFIG_CONTENT = "OPENCODE_CONFIG_CONTENT"

    /**
     * opencode 1.19: `permission` keys bash / webfetch / websearch / external_directory / edit (patterns), merged OVER
     * whatever inline config the daemon already passes (the infantry MCP entry and its allow rule are kept), plus
     * OPENCODE_DISABLE_PROJECT_CONFIG so an `opencode.json` an agent writes into its working dir is ignored.
     */
    fun opencodeEnv(existing: String?, dirs: List<String>): Map<String, String> {
        val base = existing?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: JsonObject(emptyMap())
        val permission = (base["permission"] as? JsonObject).orEmpty().toMutableMap()
        permission["bash"] = JsonPrimitive("deny")
        permission["webfetch"] = JsonPrimitive("deny")
        permission["websearch"] = JsonPrimitive("deny")
        permission["external_directory"] = JsonObject(
            linkedMapOf<String, JsonElement>("*" to JsonPrimitive("deny")) + dirs.associate { "${it.trimEnd('/')}/**" to JsonPrimitive("allow") },
        )
        permission["edit"] = JsonObject(
            linkedMapOf<String, JsonElement>("*" to JsonPrimitive("allow")) +
                listOf("**/opencode.json", "**/opencode.jsonc", "**/.opencode/**", "**/.config/opencode/**", "**/.pi/**", "**/.claude/**")
                    .associateWith { JsonPrimitive("deny") },
        )
        val merged = JsonObject(base + ("permission" to JsonObject(permission)))
        return mapOf(
            OPENCODE_CONFIG_CONTENT to merged.toString(),
            "OPENCODE_DISABLE_PROJECT_CONFIG" to "1",
            "OPENCODE_DISABLE_AUTOUPDATE" to "1",
            "OPENCODE_DISABLE_CLAUDE_CODE" to "1",
        )
    }

    fun claudeArgs(dirs: List<String>): List<String> {
        val deny = listOf("Bash", "WebFetch", "WebSearch", "Edit(~/.claude/**)", "Write(~/.claude/**)", "Edit(~/.claude.json)", "Edit(**/.claude/settings*.json)")
        val settings = JsonObject(mapOf("permissions" to JsonObject(mapOf("deny" to JsonArray(deny.map(::JsonPrimitive))))))
        return listOf("--disallowedTools", "Bash", "WebFetch", "WebSearch", "--settings", settings.toString()) +
            dirs.flatMap { listOf("--add-dir", it) }
    }
}

/** Layer 2 + token: turns one launch into its confined form. [stateDir] = the daemon's state dir (never granted). */
class SandboxPlan(
    private val stateDir: File,
    private val home: File = File(System.getProperty("user.home")),
    private val helper: () -> File? = { SandboxSupport.helper() },
    private val isLinux: Boolean = SandboxSupport.isLinux,
    private val env: (String) -> String? = System::getenv,
) {
    private val scratch: File get() = File(stateDir, "sandbox/tmp")

    fun wrap(spec: ProcessSpec, config: SandboxConfig): ProcessSpec {
        if (!config.enabled) return spec
        val dirs = (listOfNotNull(spec.workingDir?.absolutePath) + config.allowedDirs).distinct()
        val tokenEnv = buildMap {
            config.agentToken?.let { put("INFANTRY_TOKEN", it) }
            // Blank = unset for the infantry CLI and the daemon: the owner token / keystore file paths are not handed down.
            put("INFANTRY_TOKEN_FILE", "")
            put("INFANTRY_KEYSTORE_KEY_FILE", "")
        }
        val restricted = HarnessRestrictions.apply(spec.copy(env = spec.env + tokenEnv), dirs)
        if (!isLinux) return restricted
        val tool = helper() ?: throw SandboxUnavailableException(SandboxSupport.status(true, null))
        scratch.mkdirs()
        val cli = HarnessRestrictions.cliOf(spec.command)
        if (cli == "opencode") ensureOpenCodeConfigDir(restricted.env)
        val ro = listOf("/usr", "/bin", "/lib", "/lib64", "/etc", "/opt", "/proc", "/sys/fs/cgroup", "/dev/urandom", "/dev/random") +
            listOfNotNull(
                home.absolutePath,
                executableDir(spec.command.firstOrNull()),
                env("INFANTRY_BIN"),
                File(stateDir, "harness-tools").absolutePath, // CLIs installed by `infantry harness-install`
                File(System.getProperty("java.io.tmpdir"), "infantry-mcp").absolutePath, // claude --mcp-config files
            ) + spec.extraReadDirs
        val rw = dirs + listOf("/dev/null", "/dev/zero", "/dev/tty", scratch.absolutePath) + stateDirsOf(cli, restricted.env) + spec.extraWriteDirs
        val argv = buildList {
            add(tool.absolutePath)
            ro.distinct().forEach { add("--ro"); add(it) }
            rw.distinct().forEach { add("--rw"); add(it) }
            add("--")
            addAll(restricted.command)
        }
        return restricted.copy(command = argv, env = restricted.env + ("TMPDIR" to scratch.absolutePath))
    }

    /** The CLI's own session/state dirs (read-write); its CONFIG stays read-only under [home]. */
    fun stateDirsOf(cli: String?, launchEnv: Map<String, String>): List<String> {
        fun xdg(name: String, fallback: String) = (launchEnv[name] ?: env(name))?.takeIf { it.isNotBlank() } ?: File(home, fallback).path
        return when (cli) {
            "opencode" -> listOf(
                File(xdg("XDG_DATA_HOME", ".local/share"), "opencode").path,
                File(xdg("XDG_STATE_HOME", ".local/state"), "opencode").path,
                File(xdg("XDG_CACHE_HOME", ".cache"), "opencode").path,
            )
            "pi" -> listOf((launchEnv["PI_CODING_AGENT_SESSION_DIR"] ?: env("PI_CODING_AGENT_SESSION_DIR"))?.takeIf { it.isNotBlank() }
                ?: File(home, ".pi/agent/sessions").path)
            "claude" -> listOf("projects", "todos", "statsig", "shell-snapshots", "session-env", "plans")
                .map { File(home, ".claude/$it").path } + File(home, ".claude.json").path
            else -> emptyList()
        }.onEach { if (!it.endsWith(".json")) File(it).mkdirs() }
    }

    /** opencode writes `.gitignore` into its config dir when it is missing; written here so that dir can stay read-only. */
    private fun ensureOpenCodeConfigDir(launchEnv: Map<String, String>) {
        val base = (launchEnv["XDG_CONFIG_HOME"] ?: env("XDG_CONFIG_HOME"))?.takeIf { it.isNotBlank() } ?: File(home, ".config").path
        val gitignore = File(base, "opencode/.gitignore")
        if (!gitignore.isFile) runCatching {
            gitignore.parentFile.mkdirs()
            gitignore.writeText("node_modules\npackage.json\npackage-lock.json\nbun.lock\n.gitignore")
        }
    }

    private fun executableDir(command: String?): String? {
        val c = command ?: return null
        val f = File(c).takeIf { it.isAbsolute } ?: return null
        return runCatching { f.canonicalFile.parentFile?.parentFile?.path ?: f.parent }.getOrNull()
    }
}

/** The seam: every start goes through [plan] with the live [config] (read per launch, so a settings change applies). */
class SandboxRunner(
    private val delegate: ProcessRunner,
    private val plan: SandboxPlan,
    private val config: () -> SandboxConfig,
) : ProcessRunner {
    override val id: String get() = delegate.id

    override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess =
        delegate.start(plan.wrap(spec, config()), listener) // a config that cannot be read throws: never a silent unconfined launch
}

private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()
