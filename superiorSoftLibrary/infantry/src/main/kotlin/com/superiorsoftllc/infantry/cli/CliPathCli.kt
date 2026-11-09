// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.daemon.InfantryBin
import com.superiorsoftllc.infantry.install.CliPath
import com.superiorsoftllc.infantry.install.FileUserPath
import com.superiorsoftllc.infantry.install.UserPathStore
import com.superiorsoftllc.infantry.install.UserPathStores
import java.io.File

/**
 * CLI-PATH-1: `infantry install-cli` / `infantry uninstall-cli`, the command an install (or a person) uses to put the
 * `infantry` command on this machine's USER PATH, so it runs from any terminal without `cd` into the app folder.
 *
 * Both are answered WITHOUT a daemon: they edit this machine's environment, which needs no connection, and they must
 * work on a machine whose daemon is not running yet (a fresh install, a server that only has the CLI).
 *
 * Which directory goes on the PATH is the CLI's own launcher directory ([InfantryBin.resolve]), unless `--dir` names
 * one (the headless archive's `bin`, or a test). Never the system (machine) PATH: a per-user install must not need an
 * administrator, and [CliPath] only writes the user's own value.
 *
 * `INFANTRY_CLI_PATH_FILE=<file>` puts the entry in that file instead of the machine's environment: what a container,
 * a CI box or a test uses (it is why no test changes a real PATH).
 */
internal object CliPathCli {
    /** The env var that redirects the write to a plain file (containers, CI, tests). */
    const val STORE_ENV = "INFANTRY_CLI_PATH_FILE"

    const val USAGE = "usage: infantry install-cli [--dir <launcher dir>] [--dry-run] | infantry uninstall-cli [--dir <launcher dir>] [--dry-run]"

    /** The store this process writes: the file [STORE_ENV] names, else the machine's own user PATH ([UserPathStores]). */
    fun store(getenv: (String) -> String?): UserPathStore =
        getenv(STORE_ENV)?.trim()?.takeIf { it.isNotEmpty() }?.let { FileUserPath(File(it)) }
            ?: UserPathStores.forThisMachine(env = getenv)

    /**
     * The launcher directory to install: `--dir` when given (must hold a launcher), else the directory the CLI runs
     * from. Null with the reason in [err] when neither resolves to a launcher directory.
     */
    fun launcherDir(dir: String?, getenv: (String) -> String?, err: Appendable): File? {
        if (dir != null) {
            val f = File(dir).absoluteFile
            if (!CliPath.holdsLauncher(f.path)) {
                err.appendLine("error: $f holds no Infantry AI launcher (${CliPath.LAUNCHERS.joinToString(", ")} starting ${CliPath.MAIN_CLASS})")
                return null
            }
            return f
        }
        val found = InfantryBin.resolve({ getenv(it) ?: com.superiorsoftllc.infantry.session.infantrySetting(it) })
        if (found == null || !CliPath.holdsLauncher(found.path)) {
            err.appendLine("error: no Infantry AI launcher found from this command; name its folder: infantry ${"install-cli"} --dir <launcher dir>")
            return null
        }
        return found
    }

    /**
     * Run one of the two commands: install adds the entry (idempotent, and it replaces an entry that pointed at a folder
     * an update moved), uninstall removes every entry of ours. Always prints the status line, then what it wrote, then
     * [CliPath.NEW_TERMINAL_NOTE] when it wrote anything.
     */
    fun run(
        command: String,
        args: List<String>,
        dir: String?,
        dryRun: Boolean,
        out: Appendable,
        err: Appendable,
        getenv: (String) -> String?,
    ): Int {
        if (args.isNotEmpty()) {
            err.appendLine("error: $command takes no arguments; ${USAGE.removePrefix("usage: ")}")
            return 2
        }
        val launcherDir = launcherDir(dir, getenv, err) ?: return 1
        val store = runCatching { store(getenv) }
            .onFailure { err.appendLine("error: cannot read this machine's PATH: ${it.message}") }
            .getOrNull() ?: return 1
        val adding = command == "install-cli"
        val result = runCatching {
            if (adding) CliPath.install(store, launcherDir, write = !dryRun) else CliPath.uninstall(store, launcherDir, write = !dryRun)
        }
            .onFailure { err.appendLine("error: ${it.message}") }
            .getOrNull() ?: return 1
        val plan = result.plan
        plan?.removed?.forEach { out.appendLine("removed $it (that folder no longer holds the launcher)") }
        when {
            plan == null || !plan.changed -> out.appendLine(if (adding) "already on ${store.where}: nothing to change" else "not on ${store.where}: nothing to change")
            dryRun -> out.appendLine("would write ${store.where} with ${result.status.launcherDir} (dry run: nothing was written)")
            else -> out.appendLine("${if (adding) "added" else "removed"} ${result.status.launcherDir} ${if (adding) "to" else "from"} ${store.where}")
        }
        out.appendLine(result.status.line())
        // Only a real write needs a new terminal: a running shell already has the PATH it started with.
        if (!dryRun && plan?.changed == true) out.appendLine(CliPath.NEW_TERMINAL_NOTE)
        if (adding && !dryRun && plan?.changed == true) {
            out.appendLine("The installed app keeps this entry up to date at every start; to stop that: infantry settings set ${com.superiorsoftllc.infantry.settings.SettingsService.PATHS_ADD_CLI} false")
        }
        return 0
    }
}
