package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.session.InfantryEnv

import com.superiorsoftllc.agenthq.session.agentHqSetting
import java.io.File

/**
 * BUG-G2: where the `agenthq` CLI lives, so every session the daemon spawns can run it.
 *
 * Phase 10 tells agents to run `agenthq knowledge` (P10-K9). A spawned agent inherits the daemon's
 * environment, and nothing put the CLI on that PATH, so the call failed with "command not found".
 * The daemon now PREPENDS the CLI's bin directory to each child's PATH and exports it as
 * [BIN_ENV], so a prompt can also name the directory explicitly.
 *
 * The directory is, in order:
 *  1. `AGENTHQ_BIN` when set (a directory, or the launcher script itself). Read as a system property first, then the
 *     environment ([agentHqSetting]): CLIPATH-2's installed app pins it as a property to the launcher it ships in its
 *     own app dir (`InstallProfile` / `InstalledCli` in agentHqUi), so only the installed profile sets it that way.
 *  2. The installDist layout the daemon's own classes were loaded from: `<home>/lib/<jar>` gives
 *     `<home>/bin`.
 *  3. A development run (`runDaemon`, classes under `<module>/build/classes/...`): the module's
 *     `build/install/agenthq/bin`, which `runDaemon` builds first.
 * Each candidate counts only when it actually holds a launcher ([LAUNCHERS]): the POSIX script
 * `agenthq` (what Git Bash and POSIX shells run), `agenthq.bat` (cmd / PowerShell via PATHEXT), or the installed
 * app's `agenthq.cmd` (CLIPATH-2).
 */
object AgentHqBin {
    const val BIN_ENV = "AGENTHQ_BIN"

    /** The CLI's address fallback after `--url` / `--endpoint` (CliMain `resolveUrl`); unset = ws://127.0.0.1:8787/ws. */
    const val URL_ENV = "AGENTHQ_URL"

    /**
     * installDist names the launchers after `application.applicationName` (build.gradle.kts); the installed app ships
     * `agenthq.cmd` (Windows) and `agenthq` (POSIX) from agentHqUi's `appResources` (CLIPATH-2).
     */
    val LAUNCHERS = listOf("agenthq", "agenthq.bat", "agenthq.cmd")

    fun resolve(
        env: (String) -> String? = { agentHqSetting(it) },
        codeSource: File? = codeSourceOf(AgentHqBin::class.java),
    ): File? {
        env(BIN_ENV)?.takeIf { it.isNotBlank() }?.let { configured ->
            val f = File(configured)
            return (if (f.isFile) f.parentFile else f)?.absoluteFile
        }
        return candidatesFrom(codeSource).firstOrNull(::holdsLauncher)?.absoluteFile
    }

    /**
     * The env every spawned session gets: [binDir] first on PATH, [BIN_ENV], and JAVA_HOME when the
     * daemon's own environment has none (the launcher scripts need a JVM; the daemon's is one).
     * Empty when there is no [binDir]: the child then inherits the daemon's PATH untouched.
     */
    fun childEnv(
        binDir: File?,
        env: (String) -> String? = InfantryEnv::get,
        javaHome: String? = System.getProperty("java.home"),
        pathSeparator: String = File.pathSeparator,
    ): Map<String, String> {
        val dir = binDir?.absolutePath ?: return emptyMap()
        return buildMap {
            val inherited = env("PATH")?.takeIf { it.isNotBlank() }
            put("PATH", if (inherited == null) dir else dir + pathSeparator + inherited)
            put(BIN_ENV, dir)
            if (env("JAVA_HOME").isNullOrBlank() && !javaHome.isNullOrBlank()) put("JAVA_HOME", javaHome)
        }
    }

    /**
     * INSTALL-URL-1: `true` = sessions are always told this daemon's own address and an inherited [URL_ENV] is
     * ignored. Pinned by the installed app's `InstallProfile`, so a dev-exported `AGENTHQ_URL` (the dev daemon on
     * 8787) cannot point the installed app's sessions at the dev daemon.
     */
    const val OWN_URL_ONLY_SETTING = "AGENTHQ_OWN_URL_ONLY"

    /**
     * BUG-G2c: the `agenthq` address every spawned session gets, so a CLI call inside a session reaches
     * THIS daemon even when it is not on the default port. An explicit [URL_ENV] in the daemon's own
     * environment wins; otherwise `ws://<agent-reachable host>:<port>/ws` (a wildcard bind = 127.0.0.1).
     * INSTALL-URL-1: except under [OWN_URL_ONLY_SETTING], where the inherited one is ignored with a warning.
     */
    fun urlEnv(
        host: String,
        port: Int,
        env: (String) -> String? = { agentHqSetting(it) },
        warn: (String) -> Unit = { System.err.println("[agentHq] $it") },
    ): Map<String, String> {
        val own = "ws://${AccessPolicy.agentReachableHost(host)}:$port/ws"
        val inherited = env(URL_ENV)?.takeIf { it.isNotBlank() }
        val ownOnly = env(OWN_URL_ONLY_SETTING)?.trim()?.equals("true", ignoreCase = true) == true
        if (inherited != null && ownOnly && inherited != own) {
            warn("ignoring inherited $URL_ENV=$inherited; sessions of this daemon use $own ($OWN_URL_ONLY_SETTING)")
        }
        val url = if (ownOnly) own else inherited ?: own
        return mapOf(URL_ENV to url)
    }

    internal fun holdsLauncher(dir: File): Boolean = dir.isDirectory && LAUNCHERS.any { File(dir, it).isFile }

    internal fun candidatesFrom(codeSource: File?): List<File> {
        val source = codeSource?.absoluteFile ?: return emptyList()
        return buildList {
            // installDist: <home>/lib/agenthq.jar -> <home>/bin
            if (source.isFile) source.parentFile?.parentFile?.let { add(File(it, "bin")) }
            // runDaemon: <module>/build/classes/kotlin/main -> <module>/build/install/agenthq/bin
            generateSequence(source) { it.parentFile }
                .firstOrNull { it.name == "build" }
                ?.let { add(File(it, "install/agenthq/bin")) }
        }
    }

    private fun codeSourceOf(type: Class<*>): File? = runCatching {
        File(type.protectionDomain.codeSource.location.toURI())
    }.getOrNull()
}
