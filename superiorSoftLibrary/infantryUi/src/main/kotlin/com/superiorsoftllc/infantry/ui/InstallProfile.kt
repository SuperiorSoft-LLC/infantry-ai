// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.session.StateDirMigration
import com.superiorsoftllc.infantry.session.InfantryEnv

import com.superiorsoftllc.infantry.daemon.InfantryBin
import java.io.File

/**
 * PKG-1: the production defaults of the INSTALLED Infantry (the MSI/EXE built by `packageReleaseMsi`).
 *
 * The installed app must never touch the dev setup: not the dev state dir (`~/.infantry`), not the dev daemon's
 * port (8787) nor the dev UI preview's (8788). So it has its own of both, and its own names for overriding them:
 * `INFANTRY_INSTALL_STATE_DIR` and `INFANTRY_INSTALL_PORT`. The dev names (`INFANTRY_STATE_DIR`, `INFANTRY_PORT`) are
 * deliberately NOT read here, so a variable exported for the dev daemon cannot leak into the installed one.
 *
 * [apply] writes the resolved values as system properties, which the daemon reads ahead of the environment
 * (`infantrySetting`). The daemon's own `StateDirLock` then guards the installed state dir exactly as it guards
 * the dev one, and a taken port still falls back to a free one (`DaemonPort`), the window following it.
 */
object InstallProfile {
    /** The installed daemon's port. Not 8787 (dev daemon), not 8788 (dev UI preview), and its 10-port fallback scan stays clear of both. */
    const val DEFAULT_PORT = 8797

    /**
     * The state dir's path under `%LOCALAPPDATA%` (or `%APPDATA%`): `SuperiorSoft\InfantryAI`.
     *
     * INSTALL-1: NOT `%LOCALAPPDATA%\Infantry`. That is where the per-user MSI installs the app (jpackage's INSTALLDIR
     * is `LocalAppDataFolder\<packageName>`), and the MSI carries jpackage's RemoveFolderEx, which deletes the whole
     * INSTALLDIR on uninstall. An upgrade uninstalls the old version first, so a state dir in there lost the database,
     * logs and mailbox on the very first upgrade (measured, 1.0.0 -> 1.0.1). Keep it under a vendor folder instead.
     */
    const val VENDOR_DIR_NAME = "SuperiorSoft"
    const val APP_DIR_NAME = "InfantryAI"

    /** XPLAT-STATE-1: Linux data dirs are lower-case by convention (`~/.local/share/infantry`). */
    const val LINUX_DIR_NAME = "infantry"

    /** XPLAT-STATE-1: which platform data-dir convention [stateDir] follows. Anything not macOS/Linux is Windows. */
    enum class Platform { WINDOWS, MAC, LINUX }

    /** Maps an `os.name` value to its [Platform]. Unknown names keep the Windows behaviour (the only shipped one). */
    fun platform(osName: String): Platform {
        val os = osName.lowercase()
        return when {
            os.startsWith("mac") || os.startsWith("darwin") -> Platform.MAC
            os.startsWith("linux") -> Platform.LINUX
            else -> Platform.WINDOWS
        }
    }

    const val PORT_ENV = "INFANTRY_INSTALL_PORT"
    const val STATE_DIR_ENV = "INFANTRY_INSTALL_STATE_DIR"

    /** INSTALL-CWD-1: the setting that runs each project's sessions in that project's own directory. */
    const val PROJECT_DIRS_SETTING = com.superiorsoftllc.infantry.session.ProjectFolders.ENABLED_SETTING

    /** The dev ports an override may never pick (QUICKSTART.md, "Ports"). */
    val DEV_PORTS: Set<Int> = setOf(8787, 8788)

    /** `INFANTRY_INSTALL_PORT` when it is a valid port that is not a dev port, else [DEFAULT_PORT]. */
    fun port(env: (String) -> String?): Int {
        val raw = env(PORT_ENV)?.trim()?.takeIf { it.isNotEmpty() } ?: return DEFAULT_PORT
        val asked = raw.toIntOrNull()
        return when {
            asked == null || asked !in 1..65535 -> DEFAULT_PORT.also { warn("$PORT_ENV=$raw is not a port; using $DEFAULT_PORT") }
            asked in DEV_PORTS -> DEFAULT_PORT.also { warn("$PORT_ENV=$asked is a dev port; using $DEFAULT_PORT") }
            else -> asked
        }
    }

    /**
     * The installed app's own directory: the folder of the jpackage launcher (`jpackage.app-path` is
     * `<INSTALLDIR>\Infantry.exe`). Null when not launched by the installer's launcher (dev, `-Dinfantry.installed=true`).
     */
    fun installDir(getProperty: (String) -> String? = System::getProperty): File? =
        getProperty(LaunchProfile.APP_PATH_PROPERTY)?.trim()?.takeIf { it.isNotEmpty() }?.let { File(it).absoluteFile.parentFile }

    /**
     * `INFANTRY_INSTALL_STATE_DIR`, else the platform default ([os], injectable for tests; XPLAT-STATE-1):
     *  - Windows: `%LOCALAPPDATA%\SuperiorSoft\InfantryAI` (falling back to `<home>\AppData\Local\SuperiorSoft\InfantryAI`);
     *    unchanged, so existing installs keep their path.
     *  - macOS: `<home>/Library/Application Support/Infantry`.
     *  - Linux: `$XDG_DATA_HOME/infantry`, else `<home>/.local/share/infantry`.
     *
     * Refused, in favour of the default: an override naming the dev state dir (`<home>/.infantry`), and (INSTALL-1) any
     * dir inside [installDir], which the MSI deletes on uninstall and upgrade. If the default itself is inside it (the
     * owner chose `%LOCALAPPDATA%\SuperiorSoft` as the install dir), `%APPDATA%\SuperiorSoft\InfantryAI` is used instead
     * (Windows only: the macOS/Linux defaults never sit in an app bundle or `/opt` install dir).
     */
    fun stateDir(
        env: (String) -> String?,
        home: String = System.getProperty("user.home"),
        installDir: File? = null,
        os: String = System.getProperty("os.name").orEmpty(),
        appDirName: String = APP_DIR_NAME,
        linuxDirName: String = LINUX_DIR_NAME,
    ): File {
        fun under(base: String?, fallback: String) =
            File(File(base?.takeIf { it.isNotBlank() } ?: File(home, fallback).path, VENDOR_DIR_NAME), appDirName).absoluteFile
        val local = under(env("LOCALAPPDATA"), "AppData/Local")
        val default = when (platform(os)) {
            Platform.MAC -> File(home, "Library/Application Support/$appDirName").absoluteFile
            Platform.LINUX -> File(env("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: File(home, ".local/share").path, linuxDirName).absoluteFile
            Platform.WINDOWS -> if (installDir != null && local.isInside(installDir)) {
                val roaming = under(env("APPDATA"), "AppData/Roaming")
                if (roaming.isInside(installDir)) {
                    warn("every default state dir is inside the install dir $installDir; using $local, which an uninstall deletes")
                    local
                } else {
                    warn("$local is inside the install dir $installDir; using $roaming")
                    roaming
                }
            } else {
                local
            }
        }
        val override = env(STATE_DIR_ENV)?.takeIf { it.isNotBlank() }?.let { File(it).absoluteFile } ?: return default
        val dev = File(home, ".infantry").absoluteFile.normalize()
        if (override.normalize() == dev) {
            warn("$STATE_DIR_ENV names the dev state dir $dev; using $default")
            return default
        }
        if (installDir != null && override.isInside(installDir)) {
            warn("$STATE_DIR_ENV=$override is inside the install dir $installDir, which uninstall and upgrade delete; using $default")
            return default
        }
        return override
    }

    /** True when this file is [dir] or below it (by path segments, so `Infantry-data` is not inside `Infantry`). */
    private fun File.isInside(dir: File): Boolean =
        absoluteFile.normalize().toPath().startsWith(dir.absoluteFile.normalize().toPath())

    /**
     * Every daemon/UI setting the installed app pins, keyed by the name the daemon reads. All state lives under
     * the one [stateDir]: the database, the daemon's config home (`commands.json`, `harnesses/`) and the UI's
     * own data (avatar uploads) -- so uninstalling and deleting that one directory removes everything.
     *
     * CLIPATH-2: plus `INFANTRY_BIN`, the app's own `infantry` launcher directory ([InstalledCli]), when the app ships one:
     * every session the installed daemon spawns gets it first on PATH. Like the dev names above, an `INFANTRY_BIN` in
     * the environment does not win over it (a dev installDist must not leak into the installed app).
     *
     * INSTALL-CWD-1: plus `INFANTRY_PROJECT_DIRS=true`: the installed app's cwd is wherever it was launched from (a
     * Start-menu launch: not a project), so its sessions and ticket starts run in their project's own directory.
     *
     * INSTALL-URL-1: plus `INFANTRY_OWN_URL_ONLY=true`: like the dev names above, an `INFANTRY_URL` in the environment
     * (the dev daemon's) never reaches the installed sessions; they are told the installed daemon's own port.
     */
    fun properties(
        env: (String) -> String? = InfantryEnv::get,
        home: String = System.getProperty("user.home"),
        cliBin: File? = InstalledCli.binDir(),
        installDir: File? = installDir(),
        os: String = System.getProperty("os.name").orEmpty(),
    ): Map<String, String> {
        val dir = stateDir(env, home, installDir, os)
        return linkedMapOf(
            "INFANTRY_STATE_DIR" to dir.path,
            "INFANTRY_PORT" to port(env).toString(),
            "INFANTRY_HOME" to dir.path,
            "INFANTRY_DB" to File(dir, "infantry.db").path,
            "INFANTRY_UI_DATA_DIR" to File(dir, "ui").path,
            PROJECT_DIRS_SETTING to "true",
            // INSTALL-URL-1: sessions always get this daemon's own address; a dev-exported INFANTRY_URL is ignored.
            InfantryBin.OWN_URL_ONLY_SETTING to "true",
        ).apply { putAll(InstalledCli.properties(cliBin)) }
    }

    /** Pin [properties] as system properties. Call before the daemon is built. */
    fun apply(env: (String) -> String? = InfantryEnv::get): Map<String, String> {
        val pinned = properties(env) + CLI_PATH_AUTO_PROPERTY
        migrateLegacyStateDir(env, File(pinned.getValue("INFANTRY_STATE_DIR")))
        return pinned.onEach { (k, v) -> System.setProperty(k, v) }
    }

    /**
     * CLI-PATH-1: the installed app is the ONE profile that keeps `infantry` on this machine's user PATH
     * ([com.superiorsoftllc.infantry.daemon.CliPathAuto]). A dev run, a test daemon and the Docker image never set this,
     * so only an install ever writes an environment.
     */
    val CLI_PATH_AUTO_PROPERTY: Map<String, String> = mapOf(com.superiorsoftllc.infantry.daemon.CliPathAuto.ENABLED_PROPERTY to "true")

    /** The pre-rename directory names, only for [migrateLegacyStateDir]. */
    private const val LEGACY_APP_DIR_NAME = "AgentHQ"
    private const val LEGACY_LINUX_DIR_NAME = "agenthq"

    /**
     * RENAME-2: first start after the rename. The platform default moved from `SuperiorSoft\AgentHQ` to `SuperiorSoft\InfantryAI`
     * (and the Linux/macOS names likewise): when only the old one exists it is copied over, never deleted. An explicit
     * `INFANTRY_INSTALL_STATE_DIR` is left where it is; only old file names inside it get their new names.
     */
    fun migrateLegacyStateDir(
        env: (String) -> String?,
        newDir: File,
        home: String = System.getProperty("user.home"),
        installDir: File? = installDir(),
        os: String = System.getProperty("os.name").orEmpty(),
    ) {
        if (!env(STATE_DIR_ENV).isNullOrBlank()) {
            StateDirMigration.upgradeLayout(newDir)
            return
        }
        val old = stateDir({ if (it == STATE_DIR_ENV) null else env(it) }, home, installDir, os, LEGACY_APP_DIR_NAME, LEGACY_LINUX_DIR_NAME)
        StateDirMigration.migrate(old, newDir)
    }

    private fun warn(line: String) = System.err.println("[infantry-install] $line")
}
