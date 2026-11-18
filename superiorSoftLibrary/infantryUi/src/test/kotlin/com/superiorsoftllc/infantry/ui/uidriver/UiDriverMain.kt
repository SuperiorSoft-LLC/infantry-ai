// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.uidriver

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.daemon.InfantryDaemon
import com.superiorsoftllc.infantry.mailbox.JsonMailStore
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.plugin.host.PluginFolders
import com.superiorsoftllc.infantry.provider.StubProvider
import com.superiorsoftllc.infantry.runner.StubScript
import com.superiorsoftllc.infantry.session.JsonSessionStore
import com.superiorsoftllc.infantry.ui.App
import com.superiorsoftllc.infantry.ui.LocalWindowChrome
import com.superiorsoftllc.infantry.ui.WindowChrome
import com.superiorsoftllc.infantry.ui.dismissSetupWizard
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.pinShotUiPrefs
import com.superiorsoftllc.infantry.ui.plugin.DesktopPlugins
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.GraphicsEnvironment
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.system.exitProcess

/**
 * UI-DRIVER-1: a TEST instance of the desktop app that a worker drives through an API instead of the owner's mouse
 * and keyboard. It boots an in-process daemon (temp state, its own repo, commands file and owner credential, as the
 * tab shots do), renders the real `App` with the plugins offscreen (desktop Compose UI test scene: Skia raster, no OS
 * window, headless AWT), and serves UiDriverServer on 127.0.0.1:<port> with a per-run token.
 *
 * Args: `<port 18900-18999> [<width>x<height>] [<out dir>]`. Gradle: `runUiDriver -PuiDriver=1 -PuiDriverPort=<port>`;
 * the wrapper is scripts/ui-driver.sh. It writes `ui-driver.json` (port, token, pid, out, state) in its state dir and a
 * copy at `<tmp>/infantry-ui-driver/<port>.json` for the wrapper, and deletes both at exit. It stops on `quit`, or after
 * [UiDriverGuard.IDLE_ENV] minutes (default [DEFAULT_IDLE_MIN]) without a request. docs/wiki/UI-driver.md.
 */
@OptIn(ExperimentalTestApi::class)
fun main(args: Array<String>) {
    val ownerHome = System.getenv("USERPROFILE")?.takeIf { it.isNotBlank() }?.let(::File)
        ?: System.getenv("HOME")?.takeIf { it.isNotBlank() }?.let(::File) ?: File(System.getProperty("user.home"))
    val port = args.getOrNull(0)?.toIntOrNull() ?: -1
    val (width, height) = args.getOrNull(1)?.split('x')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
        ?: (DEFAULT_WIDTH to DEFAULT_HEIGHT)
    val tmp = File(System.getProperty("java.io.tmpdir"))
    val state = Files.createTempDirectory("infantry-ui-driver").toFile()
    val problems = UiDriverGuard.problems(
        optIn = System.getenv(UiDriverGuard.OPT_IN_ENV), headless = GraphicsEnvironment.isHeadless(),
        port = port, stateDir = state, tmpRoot = tmp, ownerHome = ownerHome,
    )
    if (problems.isNotEmpty()) {
        state.deleteRecursively()
        System.err.println("ui-driver: refused to start:\n" + problems.joinToString("\n") { "  - $it" })
        exitProcess(2)
    }
    // Everything that would read the user's home or the default state dir reads the temp state instead
    // (infantrySetting: a system property wins over the environment).
    System.setProperty("user.home", File(state, "home").apply { mkdirs() }.absolutePath)
    System.setProperty("INFANTRY_STATE_DIR", state.absolutePath)
    System.setProperty("INFANTRY_HOME", state.absolutePath)
    val out = File(args.getOrNull(2) ?: File(state, "shots").path).apply { mkdirs() }
    pinShotUiPrefs()

    // The plugins: a temp copy of the repository's plugins root, so toggling one in Settings never writes the repo.
    val plugins = File(state, "plugins")
    System.getProperty(PluginFolders.ROOT_PROPERTY)?.let { File(it).copyRecursively(plugins) }
    val env = mapOf(PluginFolders.ROOT_ENV to plugins.absolutePath)
    val tabs = DesktopPlugins.load(state, env = env::get, property = { if (it == PluginFolders.DEV_TRUST_PROPERTY) System.getProperty(it) else null }).factory

    val daemonPort = ServerSocket(0).use { it.localPort }
    val owner = UiDriverServer.newToken()
    val provider = StubProvider(StubScript.default(), delayMs = 0)
    val daemon = InfantryDaemon.build(
        port = daemonPort, host = "127.0.0.1", provider = provider, runner = provider.transport()!!,
        repo = File(state, "repo").apply { mkdirs() }, commandsFile = File(state, "commands.json"), ownerToken = owner,
        sessionStore = JsonSessionStore(File(state, "sessions.json")), mailStore = JsonMailStore(File(state, "mailbox.json")),
        store = SqlInfantryStore.open(File(state, "infantry.db").absolutePath), mailBaseUrl = "http://127.0.0.1:1", token = null,
    )
    daemon.start()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val controller = Controller(scope)
    val wsUrl = "ws://127.0.0.1:$daemonPort/ws"
    controller.client.ownerFor(wsUrl, owner)

    val server = UiDriverServer(port)
    val instance = buildJsonObject {
        put("port", server.port); put("token", server.token); put("pid", ProcessHandle.current().pid())
        put("out", out.absolutePath); put("state", state.absolutePath)
    }.toString()
    val lookup = File(tmp, "infantry-ui-driver/${server.port}.json")
    File(state, "ui-driver.json").writeText(instance)
    lookup.parentFile.mkdirs()
    lookup.writeText(instance)
    val idleMin = System.getenv(UiDriverGuard.IDLE_ENV)?.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_IDLE_MIN
    try {
        runDesktopComposeUiTest(width, height) {
            setContent { CompositionLocalProvider(LocalWindowChrome provides DriverChrome) { App(controller, wsUrl, null, tabs = tabs) } }
            waitUntil(timeoutMillis = CONNECT_TIMEOUT_MS) { controller.state.value.connection.isLive }
            dismissSetupWizard(controller)
            waitForIdle()
            val session = UiDriverSession(this, out) {
                put("port", server.port); put("pid", ProcessHandle.current().pid()); put("width", width); put("height", height)
                put("out", out.absolutePath)
            }
            // The token is never printed: the wrapper reads it from the instance file.
            println("ui-driver: ready on 127.0.0.1:${server.port} (${width}x$height); instance file ${lookup.absolutePath}")
            session.serve(server, idleMin * 60_000)
        }
    } finally {
        server.close()
        runCatching { daemon.close() }
        lookup.delete()
        println("ui-driver: stopped")
    }
    exitProcess(0)
}

private const val DEFAULT_WIDTH = 1400
private const val DEFAULT_HEIGHT = 860
private const val DEFAULT_IDLE_MIN = 30L
private const val CONNECT_TIMEOUT_MS = 30_000L

private object DriverChrome : WindowChrome {
    override val isMaximized: Boolean = false
    override fun minimize() {}
    override fun toggleMaximize() {}
    override fun close() {}
}
