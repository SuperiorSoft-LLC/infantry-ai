package com.superiorsoftllc.agenthq.ui

import androidx.compose.ui.window.application
import com.superiorsoftllc.agenthq.daemon.AccessConfigurationException
import com.superiorsoftllc.agenthq.daemon.AgentHqDaemon
import com.superiorsoftllc.agenthq.daemon.PortInUseException
import com.superiorsoftllc.agenthq.daemon.RemoteAccess
import com.superiorsoftllc.agenthq.daemon.StateDirLock
import com.superiorsoftllc.agenthq.provider.ProviderFactory
import com.superiorsoftllc.agenthq.session.agentHqSetting
import com.superiorsoftllc.agenthq.session.stateDir
import com.superiorsoftllc.agenthq.ui.shell.AgentHqShell
import com.superiorsoftllc.agenthq.ui.shell.PlatformHost
import com.superiorsoftllc.agenthq.ui.shell.ShellKeys
import com.superiorsoftllc.agenthq.ui.shell.defaultPlatformHost
import com.superiorsoftllc.agenthq.session.isBusy
import com.superiorsoftllc.agenthq.update.UpdateService
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.io.InputStream
import kotlin.system.exitProcess

/**
 * Desktop production entry (UI-REAL): starts the REAL daemon in this JVM, then opens the window against it.
 *
 * The provider comes from `AGENTHQ_PROVIDER` exactly as the daemon reads it; unset means OpenCode
 * ([ProviderFactory.DEFAULT]). The stub is not forced here: [SmokeMain] (test sources) keeps the stub path.
 * The shell is pointed at the daemon this entry started, never at a URL the environment may name, so the
 * window always shows the daemon that is actually running.
 */
fun main() = runRealDaemonUi()

/**
 * The body of the real-daemon entry, shared by `runRealDaemon` (dev, port 8788) and the installed app
 * (AppMainKt installed profile, PKG-1), which pins its own state dir and port before calling this.
 */
/**
 * UPDATE-1: [updates] builds the installed app's updater, given the exit it calls once the installer has started
 * (stop the daemon, end the JVM so the MSI can replace the files). Null in dev: no updater, no Updates section.
 */
/**
 * BUG-INSTALL-LOCK-1: [stateLock] is the installed app's state-dir lock (AppMain takes it before anything opens the
 * database); the bound port is recorded in it so a refused second launch can name it, and it is released on stop.
 * Null in dev (`runRealDaemon` shares `~/.agenthq` with the dev daemon by design).
 */
fun runRealDaemonUi(
    logsDir: File? = null,
    stateLock: StateDirLock.Held? = null,
    updates: ((exit: () -> Unit, runningAgents: () -> Int) -> UpdateService)? = null,
) {
    val daemon = try {
        // INSTALL-REFOCUS-1: the installed app (it holds the state-dir lock) answers a refused second launch's activation.
        if (stateLock != null) AgentHqDaemon.fromEnv(WindowActivation.route) else AgentHqDaemon.fromEnv()
    } catch (refused: AccessConfigurationException) {
        System.err.println("[agentHq-ui] refusing to start the daemon: ${refused.message}")
        exitProcess(2)
    } catch (refused: PortInUseException) {
        System.err.println("[agentHq-ui] refusing to start the daemon: ${refused.message}")
        exitProcess(2)
    }
    val wsUrl = RealDaemonLaunch.start(daemon)
    stateLock?.recordPort(daemon.port)
    println("[agentHq-ui] daemon provider=${daemon.providerId} at $wsUrl")

    val stopped = AtomicBoolean(false)
    fun stopDaemon() {
        if (!stopped.compareAndSet(false, true)) return
        daemon.server.stop(gracePeriodMillis = 0, timeoutMillis = 500)
        daemon.close()
        stateLock?.release()
    }
    val updater = updates?.invoke({ stopDaemon(); exitProcess(0) }, { daemon.manager.list().count { it.isBusy() } })
    // UPDATE-1: on start, off the UI thread; a failure is a dismissable card, never a blocked window.
    updater?.check(manual = false)

    try {
        if (RealDaemonLaunch.headless { agentHqSetting(it) }) {
            // E2E-OWNER-1: the owner-path smoke boots this exact entry without a window; closing stdin stops it.
            println("[agentHq-ui] headless: no window; the daemon stops when stdin closes")
            RealDaemonLaunch.awaitStdinEof()
        } else {
            application {
                AgentHqWindow(onCloseRequest = ::exitApplication) {
                    AgentHqShell(PinnedDaemonHost(defaultPlatformHost(), wsUrl, logsDir, updater, servers = KeystoreSavedServers(stateDir())))
                    if (updater != null) UpdatePrompt(updater)
                }
            }
        }
    } finally {
        stopDaemon()
    }
}

/** Pure launch rules for the real-daemon entry, separated from the Window so they are testable without a model. */
object RealDaemonLaunch {
    /** The provider the daemon will run: `AGENTHQ_PROVIDER` as configured, blank or unset is OpenCode. */
    fun providerId(env: (String) -> String?): String = ProviderFactory.fromFlag(env(ProviderFactory.ENV)).id

    /**
     * PORT-1: start [daemon] and return the WebSocket the shell must connect to, built from the port the daemon
     * ACTUALLY bound (a fallback when the configured one was taken), never from the configured port.
     */
    fun start(daemon: AgentHqDaemon): String = wsUrl(daemon.start(), daemon.host)

    /** The WebSocket the shell connects to: the daemon's own loopback endpoint. */
    fun wsUrl(port: Int, host: String = "127.0.0.1"): String =
        "ws://${if (host == "0.0.0.0") "127.0.0.1" else host}:$port/ws" // a wildcard bind is not a dialable address

    /** E2E-OWNER-1: `AGENTHQ_HEADLESS` (property or env) = run the daemon with no window, for the owner-path smoke. */
    const val HEADLESS = "AGENTHQ_HEADLESS"

    /** Whether [HEADLESS] asks for no window: `1`, `true` or `yes`, any case. Unset or anything else opens the window. */
    fun headless(env: (String) -> String?): Boolean = env(HEADLESS)?.trim()?.lowercase() in setOf("1", "true", "yes")

    /** Blocks until [input] reaches end of stream: the headless daemon's stop signal (its parent closes stdin). */
    fun awaitStdinEof(input: InputStream = System.`in`) {
        val buffer = ByteArray(256)
        while (input.read(buffer) >= 0) Unit
    }
}

/** The platform host with the daemon URL pinned: the shell connects to the daemon started here, nowhere else. */
/** PKG-3: [logsDir] is the installed app's log folder (null in dev: no file log, nothing to show or open). */
/** UPDATE-1: [updater] adds Configuration > Updates (the installed app only). */
/** BUG-PASTE-1: `by base` forwards every capability (clipboard, drop, file reads, any added later); only the URL, logs and updates are its own. */
internal class PinnedDaemonHost(
    private val base: PlatformHost,
    private val wsUrl: String,
    logsDir: File?,
    updater: UpdateService? = null,
    /** The token this machine can read itself (the stored remote.token while remote access is on), else null. */
    private val storedToken: () -> String? = { RemoteAccess.clientToken() },
    /** PAIR-1: where a paired server and its sealed token are kept; null = none (tests). */
    servers: com.superiorsoftllc.agenthq.ui.pair.SavedServers? = null,
) : PlatformHost by base {
    override val savedServers: com.superiorsoftllc.agenthq.ui.pair.SavedServers? = servers

    override val defaultDaemonUrl: String = wsUrl

    override fun setting(key: String): String? = when (key) {
        ShellKeys.WS_URL -> wsUrl
        // The window never asks for a token it can read: an explicit setting wins, else the stored one.
        ShellKeys.TOKEN -> base.setting(key)?.takeIf { it.isNotBlank() } ?: storedToken()
        else -> base.setting(key)
    }

    override val logsDir: String? = logsDir?.path

    override val updates: UpdatesEntry? =
        updater?.let { u -> UpdatesEntry(InstalledUpdates.version() ?: "an unknown version") { u.check(manual = true) } }
}
