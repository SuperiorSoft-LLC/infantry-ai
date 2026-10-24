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
import com.superiorsoftllc.agenthq.ui.shell.LocalEndpoint
import com.superiorsoftllc.agenthq.ui.shell.LocalServer
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
    val headless = RealDaemonLaunch.headless { agentHqSetting(it) }
    val servers = KeystoreSavedServers(stateDir())
    val activeServer = ActiveServerFile(stateDir())
    val local = InProcessDaemon(stateLock)
    // SWITCH-1: the window opens on the remote server last picked (the installed thin client of a headless / Docker
    // server) WITHOUT starting this machine's daemon; "This computer" starts it on demand. Headless always runs it.
    val remote = if (headless) null else activeServer.read()?.let { url -> servers.list().firstOrNull { it.url == url } }
    val wsUrl = if (remote == null) (local.start()?.url ?: exitProcess(2)) else ""
    if (remote != null) println("[agentHq-ui] opening on remote server '${remote.name}' (${remote.url}); this computer's daemon is not started")

    val updater = updates?.invoke({ local.stop(); exitProcess(0) }, local::runningAgents)
    // UPDATE-1: on start, off the UI thread; a failure is a dismissable card, never a blocked window.
    updater?.check(manual = false)

    // MD-PLUGIN-1: markdown engine plugins (jars) from <stateDir>/markdown-engines show up in Config > Appearance > Markdown engine.
    if (!headless) runCatching {
        com.superiorsoftllc.agenthq.ui.markdown.MarkdownEnginePluginLoader(File(stateDir(), "markdown-engines"), com.superiorsoftllc.agenthq.ui.model.ChatMarkdown.engines).loadAll()
    }

    try {
        if (headless) {
            // E2E-OWNER-1: the owner-path smoke boots this exact entry without a window; closing stdin stops it.
            println("[agentHq-ui] headless: no window; the daemon stops when stdin closes")
            RealDaemonLaunch.awaitStdinEof()
        } else {
            application {
                AgentHqWindow(onCloseRequest = ::exitApplication) {
                    AgentHqShell(PinnedDaemonHost(defaultPlatformHost(), wsUrl, logsDir, updater, servers = servers, remote = remote, local = local, activeFile = activeServer, pluginsDir = File(stateDir(), "plugins")))
                    if (updater != null) UpdatePrompt(updater)
                }
            }
        }
    } finally {
        local.stop()
    }
}

/** SWITCH-1: the in-process daemon behind "This computer"; started once, on demand, and stopped when the app exits. */
internal class InProcessDaemon(private val stateLock: StateDirLock.Held?) : LocalServer {
    private var daemon: AgentHqDaemon? = null
    private var wsUrl: String? = null

    @Synchronized
    override fun start(): LocalEndpoint? {
        wsUrl?.let { return LocalEndpoint(it, RemoteAccess.clientToken()) }
        val started = try {
            // INSTALL-REFOCUS-1: the installed app (it holds the state-dir lock) answers a refused second launch's activation.
            if (stateLock != null) AgentHqDaemon.fromEnv(WindowActivation.route) else AgentHqDaemon.fromEnv()
        } catch (refused: AccessConfigurationException) {
            System.err.println("[agentHq-ui] refusing to start the daemon: ${refused.message}")
            return null
        } catch (refused: PortInUseException) {
            System.err.println("[agentHq-ui] refusing to start the daemon: ${refused.message}")
            return null
        }
        val url = RealDaemonLaunch.start(started)
        stateLock?.recordPort(started.port)
        println("[agentHq-ui] daemon provider=${started.providerId} at $url")
        daemon = started
        wsUrl = url
        return LocalEndpoint(url, RemoteAccess.clientToken())
    }

    fun runningAgents(): Int = daemon?.manager?.list()?.count { it.isBusy() } ?: 0

    @Synchronized
    override fun stop() {
        daemon?.let {
            it.server.stop(gracePeriodMillis = 0, timeoutMillis = 500)
            it.close()
        }
        daemon = null
        wsUrl = null
        stateLock?.release()
    }
}

/** SWITCH-1: which saved server the window opens on, a URL in `<stateDir>/active-server.txt` (no secret); absent = this computer. */
internal class ActiveServerFile(dir: File) {
    private val file = File(dir, "active-server.txt")

    fun read(): String? = runCatching { file.takeIf { it.isFile }?.readText(Charsets.UTF_8)?.trim()?.ifEmpty { null } }.getOrNull()

    fun write(url: String?) {
        runCatching { if (url == null) file.delete() else { file.parentFile?.mkdirs(); file.writeText(url, Charsets.UTF_8) } }
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
    /** SWITCH-1: the saved server the window opens on instead of the local daemon; its URL and token replace [wsUrl]. */
    private val remote: com.superiorsoftllc.agenthq.ui.pair.SavedServer? = null,
    /** SWITCH-1: this machine's daemon, started on demand by "This computer". */
    local: LocalServer? = null,
    private val activeFile: ActiveServerFile? = null,
    /** VOICE-PLUGIN-1: where voice plugins are installed (`<stateDir>/plugins`); null = OS voice only, no Plugins section (tests). */
    pluginsDir: File? = null,
) : PlatformHost by base {
    private val pluginStore = pluginsDir?.let { com.superiorsoftllc.agenthq.ui.voice.plugin.VoicePluginStore(it) }

    override val voicePlugins: com.superiorsoftllc.agenthq.ui.voice.plugin.VoicePlugins? = pluginStore

    /** VOICE-PLUGIN-1: the enabled voice plugin, falling back to the OS voice on any failure; the OS voice when none is enabled. */
    override val speaker: com.superiorsoftllc.agenthq.ui.voice.Speaker? =
        base.speaker?.let { os -> pluginStore?.let { com.superiorsoftllc.agenthq.ui.voice.plugin.PluginSpeaker(it, os) } ?: os }

    override val localServer: LocalServer? = local

    override fun activeServer(): String? = remote?.url

    override fun setActiveServer(url: String?) { activeFile?.write(url) }

    override val savedServers: com.superiorsoftllc.agenthq.ui.pair.SavedServers? = servers

    override val defaultDaemonUrl: String = remote?.url ?: wsUrl

    override fun setting(key: String): String? = when (key) {
        ShellKeys.WS_URL -> remote?.url ?: wsUrl
        // A remote server's token is the one sealed for it, never this machine's own.
        ShellKeys.TOKEN -> if (remote != null) remote.token else base.setting(key)?.takeIf { it.isNotBlank() } ?: storedToken()
        ShellKeys.TOKEN_FILE -> if (remote != null) null else base.setting(key)
        else -> base.setting(key)
    }

    override val logsDir: String? = logsDir?.path

    override val updates: UpdatesEntry? =
        updater?.let { u -> UpdatesEntry(InstalledUpdates.version() ?: "an unknown version") { u.check(manual = true) } }
}
