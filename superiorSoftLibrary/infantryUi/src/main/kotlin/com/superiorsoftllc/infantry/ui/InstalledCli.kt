// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.daemon.InfantryBin
import java.io.File

/**
 * CLIPATH-2: the `infantry` CLI of the INSTALLED app, so the sessions its daemon spawns can run it (workers do).
 *
 * The MSI/EXE has no installDist layout, so [InfantryBin]'s own lookups find nothing there. Instead the app ships small
 * launchers as Compose app resources (`infantryUi/appResources/<os>/`, wired by `appResourcesRootDir` in
 * build.gradle.kts): `infantry.cmd` (plus an `infantry` sh shim for Git Bash) on Windows, an `infantry` sh script on
 * macOS/Linux. jpackage puts them in `<app dir>/resources` and the launcher passes that directory as
 * [RESOURCES_DIR_PROPERTY]. Each launcher runs [MAIN_CLASS] on the app's own classpath (every jar in the app dir, the
 * `-cp` wildcard, `:infantry` included) with the bundled runtime's `java` when it has one.
 *
 * Only [InstallProfile] (the installed profile) pins the directory, as the `INFANTRY_BIN` system property; the daemon
 * reads it ahead of the environment, prepends it to every child's PATH and exports it ([InfantryBin.childEnv]). The dev
 * profile never calls this, so dev keeps its installDist bin exactly as before. (Gradle `run` also sets
 * [RESOURCES_DIR_PROPERTY]; that is why the pin lives in the installed profile and not in [InfantryBin].)
 */
object InstalledCli {
    /** The CLI entry point every launcher names; `application.mainClass` in infantry/build.gradle.kts. */
    const val MAIN_CLASS = "com.superiorsoftllc.infantry.cli.CliMainKt"

    /** Set by Compose Desktop's launcher to `$APPDIR/resources` when `appResourcesRootDir` is configured. */
    const val RESOURCES_DIR_PROPERTY = "compose.application.resources.dir"

    /** The shipped launcher file names, per OS, as they sit under `appResources/<os>/`. */
    const val WINDOWS_LAUNCHER = "infantry.cmd"
    const val POSIX_LAUNCHER = "infantry"

    /**
     * BUG-CLI-2: PowerShell's `infantry` on Windows. PowerShell picks it ahead of [WINDOWS_LAUNCHER] in the same dir and
     * hands java the arguments as an array; infantry.cmd's `%*` ends at the first LF and drops the rest.
     */
    const val POWERSHELL_LAUNCHER = "infantry.ps1"

    /**
     * BUG-CLI-3: what the app ships instead of [POWERSHELL_LAUNCHER]. PowerShell picks infantry.ps1 for a bare `infantry`
     * even when the execution policy forbids running it (Restricted, the Windows client default, or AllSigned: the
     * script is unsigned), and then fails where infantry.cmd would have worked. So the script ships under a name
     * PowerShell never resolves, and [syncPowerShellLauncher] puts infantry.ps1 in place only where scripts can run.
     */
    const val POWERSHELL_LAUNCHER_SOURCE = "infantry.ps1.template"

    /** Effective execution policies under which an unsigned, locally created script runs. */
    val SCRIPT_POLICIES: Set<String> = setOf("Unrestricted", "RemoteSigned", "Bypass")

    /**
     * BUG-INSTALL-PS1-1: every name `Get-ExecutionPolicy` can answer with. Anything else a probe prints (an error, a
     * warning, nothing at all) is not a policy and says nothing about whether scripts can run.
     */
    val KNOWN_POLICIES: Set<String> = setOf("Restricted", "AllSigned", "RemoteSigned", "Unrestricted", "Bypass", "Undefined", "Default")

    /** The PowerShells that resolve a bare `infantry` in a session: Windows PowerShell 5.1 and PowerShell 7. */
    val POWERSHELLS: List<String> = listOf("powershell", "pwsh")

    /**
     * Each installed PowerShell's effective `Get-ExecutionPolicy`, as the app's own environment sees it (the
     * daemon's sessions inherit that environment). A shell that cannot be started is left out; one that starts but
     * answers with anything other than a policy name is returned as it said it ([scriptsCanRun] treats it as unknown).
     */
    fun executionPolicies(run: (List<String>) -> String? = ::runQuietly): List<String> =
        POWERSHELLS.mapNotNull { shell -> run(listOf(shell, "-NoProfile", "-NonInteractive", "-Command", "Get-ExecutionPolicy")) }

    /** [answer] as one of [KNOWN_POLICIES] (PowerShell is case-insensitive), or null when it is not a policy name. */
    fun policyName(answer: String): String? = answer.trim().let { a -> KNOWN_POLICIES.firstOrNull { it.equals(a, ignoreCase = true) } }

    /**
     * True when there is a PowerShell and every one of them can run infantry.ps1; false when one of them definitely
     * cannot (a known policy outside [SCRIPT_POLICIES]) or there is no PowerShell at all; null when a probe answered
     * with something that is not a policy (BUG-INSTALL-PS1-1), so nothing is known and infantry.ps1 is left as it is.
     */
    fun scriptsCanRun(policies: List<String>): Boolean? {
        val names = policies.map(::policyName)
        return when {
            policies.isEmpty() -> false
            names.any { it != null && it !in SCRIPT_POLICIES } -> false
            names.any { it == null } -> null
            else -> true
        }
    }

    /**
     * BUG-CLI-3: make [binDir] hold infantry.ps1 (from [POWERSHELL_LAUNCHER_SOURCE]) exactly when [scriptsCanRun], and
     * remove it otherwise, so PowerShell falls back to infantry.cmd instead of refusing a script it may not run. Runs at
     * every installed start, so a policy tightened later also takes the script away. Never throws: on any failure
     * infantry.cmd still works (without BUG-CLI-2's multi-line fix in PowerShell). [scriptsCanRun] null (an unrecognised
     * probe, BUG-INSTALL-PS1-1) changes nothing. Returns whether infantry.ps1 is there.
     */
    fun syncPowerShellLauncher(binDir: File, scriptsCanRun: Boolean?): Boolean {
        val target = File(binDir, POWERSHELL_LAUNCHER)
        val source = File(binDir, POWERSHELL_LAUNCHER_SOURCE)
        if (scriptsCanRun == null) return target.isFile
        runCatching {
            if (scriptsCanRun && source.isFile) {
                if (!target.isFile || !target.readBytes().contentEquals(source.readBytes())) source.copyTo(target, overwrite = true)
            } else if (target.exists()) {
                target.delete()
            }
        }.onFailure { System.err.println("[infantry-install] could not update $target: $it") }
        return target.isFile
    }

    /** [command]'s trimmed stdout, or null when it cannot be started or does not end within 15 s. */
    private fun runQuietly(command: List<String>): String? = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start().apply { outputStream.close() }
        val out = StringBuilder()
        val reader = Thread { out.append(process.inputStream.bufferedReader().readText()) }.apply { isDaemon = true; start() }
        if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        reader.join(1_000)
        out.toString().trim()
    }.getOrNull()

    /** The app's launcher directory, or null when it is missing or holds no launcher (never a guess). */
    fun binDir(getProperty: (String) -> String? = System::getProperty): File? {
        val dir = getProperty(RESOURCES_DIR_PROPERTY)?.trim()?.takeIf { it.isNotEmpty() }?.let(::File) ?: return null
        return dir.absoluteFile.takeIf { d -> d.isDirectory && InfantryBin.LAUNCHERS.any { File(d, it).isFile } }
    }

    /** What [InstallProfile.properties] adds: `INFANTRY_BIN` = [binDir], or nothing when there is none. */
    fun properties(binDir: File?): Map<String, String> =
        if (binDir == null) emptyMap() else mapOf(InfantryBin.BIN_ENV to binDir.path)
}
