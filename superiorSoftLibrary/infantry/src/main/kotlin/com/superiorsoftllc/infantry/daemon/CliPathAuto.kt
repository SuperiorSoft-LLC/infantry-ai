// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.install.CliPath
import com.superiorsoftllc.infantry.install.FileUserPath
import com.superiorsoftllc.infantry.install.UserPathStore
import com.superiorsoftllc.infantry.install.UserPathStores
import com.superiorsoftllc.infantry.session.InfantryEnv
import com.superiorsoftllc.infantry.settings.SettingsService
import java.io.File

/**
 * CLI-PATH-1: the daemon half of "`infantry` works from any terminal".
 *
 * An INSTALLED app writes the USER PATH entry itself, at every start, because that is the only place that knows all
 * three facts: the launcher folder it shipped (CLIPATH-2's `<install>/app/resources`), where the user's PATH lives on
 * this OS (the Windows user environment, a POSIX shell profile) and whether the user wants it
 * (`paths.addCli`, default on). Doing it here, at every start, is also what keeps the entry correct across an UPDATE:
 * a new version whose install folder moved re-writes the entry and drops the one that pointed at the old folder.
 *
 * It NEVER runs for a dev build, a test daemon or a container:
 *  - [ENABLED_PROPERTY] (`-Dinfantry.cliPath.auto=true`) is set by the installed desktop profile ([com.superiorsoftllc.infantry.ui.InstallProfile])
 *    and by nothing else, so a daemon a test starts writes no environment;
 *  - a Docker image already has `infantry` on its PATH (docker/Dockerfile), so [DISTRIBUTION_ENV] `docker` skips it;
 *  - and a failure is logged and swallowed: a missing PATH entry must never stop the daemon from starting.
 */
object CliPathAuto {
    /** Set by the installed desktop app only (`-Dinfantry.cliPath.auto=true`), never by a test or a dev run. */
    const val ENABLED_PROPERTY = "infantry.cliPath.auto"

    /** The same switch as an environment variable, for a headless install that wants it. */
    const val ENABLED_ENV = "INFANTRY_CLI_PATH_AUTO"

    /** `docker` for the image (its PATH already holds the CLI): the one distribution that never writes a PATH. */
    const val DISTRIBUTION_ENV = "INFANTRY_DISTRIBUTION"

    /** `INFANTRY_CLI_PATH_FILE`: write that file instead of the machine's environment (a container, CI or a test). */
    const val STORE_ENV = "INFANTRY_CLI_PATH_FILE"

    /** Whether this process may write the user PATH: opt-in, never in a container, never for a throwaway state dir. */
    fun enabled(getenv: (String) -> String?): Boolean {
        val optedIn = (getenv(ENABLED_PROPERTY) ?: System.getProperty(ENABLED_PROPERTY))?.trim()?.equals("true", ignoreCase = true) == true
        if (!optedIn) return false
        if (getenv(DISTRIBUTION_ENV)?.trim()?.equals("docker", ignoreCase = true) == true) return false
        // A state dir under the OS temp dir is a test or a CI daemon: it must never edit the machine's environment.
        val temp = File(System.getProperty("java.io.tmpdir")).absolutePath
        val state = getenv("INFANTRY_STATE_DIR") ?: System.getProperty("INFANTRY_STATE_DIR") ?: return false
        return !File(state).absolutePath.startsWith(temp)
    }

    /** The store to write: the file [STORE_ENV] names, else this machine's user PATH. */
    fun store(getenv: (String) -> String?): UserPathStore =
        getenv(STORE_ENV)?.trim()?.takeIf { it.isNotEmpty() }?.let { FileUserPath(File(it)) }
            ?: UserPathStores.forThisMachine(home = System.getProperty("user.home"), env = getenv)

    /**
     * Bring the entry in line with the setting: `paths.addCli` on adds (and replaces) it, off removes it. [launcherDir]
     * is the CLI's launcher folder ([InfantryBin.resolve]), or null when this install has none — then nothing is written.
     * Answers the line to log, or null when there was nothing to do.
     */
    fun apply(
        settings: SettingsService,
        launcherDir: File?,
        store: UserPathStore = store(InfantryEnv::get),
        log: (String) -> Unit = { println(it) },
    ): String? {
        if (launcherDir == null || !CliPath.holdsLauncher(launcherDir.path)) return null
        val add = settings.addCliToPath()
        val result = if (add) CliPath.install(store, launcherDir) else CliPath.uninstall(store, launcherDir)
        val line = when {
            result.plan?.changed != true -> null
            add -> "[infantry] put $launcherDir on ${store.where} so `infantry` runs from any terminal (${CliPath.NEW_TERMINAL_NOTE.lowercase()})"
            else -> "[infantry] took $launcherDir off ${store.where}: ${SettingsService.PATHS_ADD_CLI} is false"
        }
        line?.let(log)
        return line
    }

    /** [apply] for the daemon's own start: only when [enabled], and never fatal. */
    fun applyAtStart(settings: SettingsService, launcherDir: File?, getenv: (String) -> String? = InfantryEnv::get, log: (String) -> Unit = { println(it) }): String? {
        if (!enabled(getenv)) return null
        return runCatching { apply(settings, launcherDir, store(getenv), log) }
            .onFailure { log("[infantry] could not update the PATH (${it.message}); `infantry install-cli` does it by hand") }
            .getOrNull()
    }
}
