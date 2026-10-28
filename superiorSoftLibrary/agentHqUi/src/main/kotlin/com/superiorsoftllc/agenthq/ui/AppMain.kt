package com.superiorsoftllc.agenthq.ui

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.system.exitProcess
import com.superiorsoftllc.agenthq.daemon.StateDirInUseException
import com.superiorsoftllc.agenthq.daemon.StateDirLock

/**
 * PKG-1: the ONE entry Compose Desktop launches (`run` in dev, the jpackage launcher once installed).
 *
 * It picks the profile at runtime ([LaunchProfile]):
 *  - installed (the MSI/EXE launcher sets `jpackage.app-path`, or `-Dagenthq.installed=true`): pin the
 *    production state dir and port ([InstallProfile]) and start the app's own daemon in this JVM;
 *  - otherwise dev, exactly as [runDevUi] (MainKt): the window only, connecting to the dev daemon on 8787.
 */
fun main(args: Array<String>) {
    safeModeFlag(args)
    // NS4: --app <dir> (shell builds): app.json + <dir>/plugins as the plugins root. The per-app state dir is NS5's.
    ShellApp.apply(args)
    if (LaunchProfile.isInstalled()) {
        // PKG-3: the state dir decides where the log goes, so resolving it comes first; its override warnings are
        // held and written into the log once it is open, so a Start-menu launch (no console) keeps every line.
        val early = ByteArrayOutputStream()
        val console = System.err
        System.setErr(PrintStream(early, true, Charsets.UTF_8))
        val pinned = try { InstallProfile.apply() } finally { System.setErr(console) }
        // BUG-INSTALL-LOCK-1: one daemon per installed state dir, decided before the log rotates or the database opens,
        // exactly as the daemon's own main (DaemonMain.kt) refuses a second daemon on its state dir.
        val stateLock = InstalledLock.acquireOrExit(File(pinned.getValue("AGENTHQ_STATE_DIR")), early.toString(Charsets.UTF_8))
        val logs = LaunchProfile.installLogs(installed = true, stateDir = File(pinned.getValue("AGENTHQ_STATE_DIR")))
        System.err.print(early.toString(Charsets.UTF_8))
        // INSTALL-1: the install dir too, so a log shows at a glance that the state dir is outside it (the MSI deletes it).
        println("[agentHq-install] app=${InstallProfile.installDir() ?: "<not a jpackage launch>"} state=${pinned["AGENTHQ_STATE_DIR"]} port=${pinned["AGENTHQ_PORT"]}")
        // CLIPATH-2: sessions get the app's own `agenthq` launcher on PATH; say so, or say why not.
        println(
            pinned["AGENTHQ_BIN"]?.let { "[agentHq-install] sessions get the agenthq CLI on PATH from $it" }
                ?: "[agentHq-install] WARNING: no agenthq launcher in the app resources; sessions cannot run `agenthq`",
        )
        // BUG-CLI-3: agenthq.ps1 only where PowerShell may run it; off the start path (probing spawns PowerShell), and
        // until it lands PowerShell simply uses agenthq.cmd.
        pinned["AGENTHQ_BIN"]?.takeIf { System.getProperty("os.name").startsWith("Windows") }?.let { bin ->
            Thread {
                val policies = InstalledCli.executionPolicies()
                val ps1 = InstalledCli.syncPowerShellLauncher(File(bin), InstalledCli.scriptsCanRun(policies))
                println("[agentHq-install] PowerShell execution policy $policies; agenthq.ps1 ${if (ps1) "installed" else "not installed (agenthq.cmd is used)"}")
            }.apply { isDaemon = true; name = "agenthq-ps1-sync" }.start()
        }
        // UPDATE-1: only the installed profile gets an updater (a dev build never updates itself).
        val stateDir = File(pinned.getValue("AGENTHQ_STATE_DIR"))
        runRealDaemonUi(logsDir = logs?.dir, stateLock = stateLock) { exit, running -> InstalledUpdates.service(installed = true, stateDir = stateDir, exit = exit, runningAgents = running) }
    } else {
        runDevUi()
    }
}

/** Which profile this JVM runs. Pure, so a test can pin it without a launcher. */
object LaunchProfile {
    /** Set by the jpackage launcher of an installed app, never by Gradle's `run`. */
    const val APP_PATH_PROPERTY = "jpackage.app-path"

    /** Forces the installed profile from any launch (`-Dagenthq.installed=true`). */
    const val FORCE_PROPERTY = "agenthq.installed"

    fun isInstalled(getProperty: (String) -> String? = System::getProperty): Boolean =
        !getProperty(APP_PATH_PROPERTY).isNullOrBlank() ||
            getProperty(FORCE_PROPERTY)?.trim()?.equals("true", ignoreCase = true) == true

    /**
     * PKG-3: the installed profile routes stdout/stderr to `<stateDir>/logs/agenthq.log` ([InstallLogs]); dev
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
        val line = "[agentHq-install] refusing to start: ${refused.message}"
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
