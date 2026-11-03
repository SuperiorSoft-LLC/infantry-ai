// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.system.exitProcess
import com.superiorsoftllc.infantry.daemon.StateDirInUseException
import com.superiorsoftllc.infantry.daemon.StateDirLock

/**
 * PKG-1: the ONE entry Compose Desktop launches (`run` in dev, the jpackage launcher once installed).
 *
 * It picks the profile at runtime ([LaunchProfile]):
 *  - installed (the MSI/EXE launcher sets `jpackage.app-path`, or `-Dinfantry.installed=true`): pin the
 *    production state dir and port ([InstallProfile]) and start the app's own daemon in this JVM;
 *  - otherwise dev, exactly as [runDevUi] (MainKt): the window only, connecting to the dev daemon on 8787.
 */
fun main(args: Array<String>) {
    com.superiorsoftllc.infantry.session.InfantryEnv.installProcessMirror()
    safeModeFlag(args)
    // NS4: --app <dir> (shell builds): app.json + <dir>/plugins as the plugins root.
    // NS5/NS6a: a shell app never runs on the Infantry install's (or the dev) daemon or state: it has its own.
    val app = ShellApp.apply(args)
    if (app != null) {
        runShellApp(app)
    } else if (LaunchProfile.isInstalled()) {
        // PKG-3: the state dir decides where the log goes, so resolving it comes first; its override warnings are
        // held and written into the log once it is open, so a Start-menu launch (no console) keeps every line.
        val early = ByteArrayOutputStream()
        val console = System.err
        System.setErr(PrintStream(early, true, Charsets.UTF_8))
        val pinned = try { InstallProfile.apply() } finally { System.setErr(console) }
        // BUG-INSTALL-LOCK-1: one daemon per installed state dir, decided before the log rotates or the database opens,
        // exactly as the daemon's own main (DaemonMain.kt) refuses a second daemon on its state dir.
        val stateLock = InstalledLock.acquireOrExit(File(pinned.getValue("INFANTRY_STATE_DIR")), early.toString(Charsets.UTF_8))
        val logs = LaunchProfile.installLogs(installed = true, stateDir = File(pinned.getValue("INFANTRY_STATE_DIR")))
        System.err.print(early.toString(Charsets.UTF_8))
        // INSTALL-1: the install dir too, so a log shows at a glance that the state dir is outside it (the MSI deletes it).
        println("[infantry-install] app=${InstallProfile.installDir() ?: "<not a jpackage launch>"} state=${pinned["INFANTRY_STATE_DIR"]} port=${pinned["INFANTRY_PORT"]}")
        // CLIPATH-2: sessions get the app's own `infantry` launcher on PATH; say so, or say why not.
        println(
            pinned["INFANTRY_BIN"]?.let { "[infantry-install] sessions get the infantry CLI on PATH from $it" }
                ?: "[infantry-install] WARNING: no infantry launcher in the app resources; sessions cannot run `infantry`",
        )
        // BUG-CLI-3: infantry.ps1 only where PowerShell may run it; off the start path (probing spawns PowerShell), and
        // until it lands PowerShell simply uses infantry.cmd.
        pinned["INFANTRY_BIN"]?.takeIf { System.getProperty("os.name").startsWith("Windows") }?.let { bin ->
            Thread {
                val policies = InstalledCli.executionPolicies()
                val ps1 = InstalledCli.syncPowerShellLauncher(File(bin), InstalledCli.scriptsCanRun(policies))
                println("[infantry-install] PowerShell execution policy $policies; infantry.ps1 ${if (ps1) "installed" else "not installed (infantry.cmd is used)"}")
            }.apply { isDaemon = true; name = "infantry-ps1-sync" }.start()
        }
        // UPDATE-1: only the installed profile gets an updater (a dev build never updates itself).
        val stateDir = File(pinned.getValue("INFANTRY_STATE_DIR"))
        runRealDaemonUi(logsDir = logs?.dir, stateLock = stateLock, updates = { exit, running -> InstalledUpdates.service(installed = true, stateDir = stateDir, exit = exit, runningAgents = running) })
    } else {
        runDevUi()
    }
}

/**
 * NS5/NS6a: a shell app's start. Its state dir, loopback host and ephemeral port are pinned first ([AppDaemonProfile]),
 * then [DaemonChoice] decides: a saved server the user picked (Servers), else the bundled daemon from the build's
 * [DaemonLauncher], else the Connect dialog. Never a silent fallback, never the Infantry install's daemon.
 */
internal fun runShellApp(app: ShellAppLaunch) {
    AppDaemonProfile.apply(app)
    val stateDir = app.stateDir
    val launcher = DaemonLaunchers.find()
    val servers = KeystoreSavedServers(stateDir)
    val saved = ActiveServerFile(stateDir).read()?.takeIf { url -> servers.list().any { it.url == url } }
    val choice = DaemonChoice.resolve(app.manifest.daemon, saved, buildHasDaemon = launcher != null)
    println("[infantry-app] app=${app.manifest.appId} daemon=$choice state=$stateDir")
    if (choice == DaemonChoice.ConnectDialog) {
        // NS6b brings the Connect dialog for an app; until then say why, rather than attach anywhere.
        System.err.println("[infantry-app] no daemon to use: mode=${app.manifest.daemon.mode}, no saved server, bundled daemon ${if (launcher == null) "absent" else "not allowed"}")
        exitProcess(2)
    }
    val logs = LaunchProfile.installLogs(LaunchProfile.isInstalled(), stateDir)
    // The lock and the seed come with the bundled daemon; a saved server opens without starting it (SWITCH-1).
    runRealDaemonUi(logsDir = logs?.dir, local = launcher?.create(stateDir, app.manifest.daemon.seedProject))
}

/** Which profile this JVM runs. Pure, so a test can pin it without a launcher. */
object LaunchProfile {
    /** Set by the jpackage launcher of an installed app, never by Gradle's `run`. */
    const val APP_PATH_PROPERTY = "jpackage.app-path"

    /** Forces the installed profile from any launch (`-Dinfantry.installed=true`). */
    const val FORCE_PROPERTY = "infantry.installed"

    fun isInstalled(getProperty: (String) -> String? = System::getProperty): Boolean =
        !getProperty(APP_PATH_PROPERTY).isNullOrBlank() ||
            getProperty(FORCE_PROPERTY)?.trim()?.equals("true", ignoreCase = true) == true

    /**
     * PKG-3: the installed profile routes stdout/stderr to `<stateDir>/logs/infantry.log` ([InstallLogs]); dev
     * returns null and leaves the console exactly as it was.
     */
    fun installLogs(installed: Boolean, stateDir: File): InstallLogs.Installed? =
        if (installed) InstallLogs.install(stateDir) else null
}

/**
 * BUG-INSTALL-LOCK-1: the installed app's state-dir lock. A second launch on a held state dir (a double-click on the
 * Start-menu shortcut) starts no daemon: it prints one line naming the holder, appends it to the running app's log
 * (a Start-menu launch has no console), asks the running app to show its window (INSTALL-REFOCUS-1, [WindowActivation]),
 * and exits 2.
 */
object InstalledLock {
    fun acquireOrExit(stateDir: File, held: String = ""): StateDirLock.Held = try {
        StateDirLock.acquire(stateDir)
    } catch (refused: StateDirInUseException) {
        val line = "[infantry-install] refusing to start: ${refused.message}"
        System.err.print(held)
        System.err.println(line)
        // Best effort: the holder's log is open for append, never rotated from here.
        runCatching { InstallLogs.file(InstallLogs.dir(stateDir), 0).takeIf { it.exists() }?.appendText(line + System.lineSeparator()) }
        // INSTALL-REFOCUS-1: a Start-menu double-click must not look like nothing happened: bring the running window
        // forward over the holder's daemon port, or say so in a native dialog.
        System.err.println(WindowActivation.notifyAlreadyRunning(refused.holder.port))
        exitProcess(2)
    }
}
