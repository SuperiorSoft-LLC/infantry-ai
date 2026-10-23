package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.daemon.RemoteAccess
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

/**
 * INSTALL-REFOCUS-1: a second launch of the installed app brings the running window forward.
 *
 * The channel is the one the holder already publishes: its daemon port, recorded in `daemon.lock`
 * ([com.superiorsoftllc.agenthq.daemon.StateDirLock.Holder.port]). The installed app adds [PATH] to its own daemon
 * ([route], through `AgentHqDaemon.fromEnv(extraRoutes)`), behind the daemon's AccessPolicy like every other route.
 * The refused launch posts to it on loopback ([request]); when that cannot reach a window, it shows a native
 * "already running" dialog instead ([notifyAlreadyRunning]).
 */
object WindowActivation {
    const val PATH = "/app/activate"

    /** What brings the window forward; null while no window is open (headless, or before the first frame). */
    private val target = AtomicReference<(() -> Unit)?>(null)

    fun register(activate: (() -> Unit)?) = target.set(activate)

    /** The holder's side. 204 = a window was brought forward; 409 = the request arrived but there is no window. */
    val route: Route.() -> Unit = {
        post(PATH) {
            println("[agentHq-install] activation requested by a second launch")
            val activate = target.get()
            if (activate == null) {
                call.respond(HttpStatusCode.Conflict, "no window to show")
            } else {
                activate()
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }

    /** Restore, show and raise [frame] on the AWT thread. The always-on-top flip gets past Windows' foreground lock. */
    fun bringToFront(frame: Frame, restore: () -> Unit) = SwingUtilities.invokeLater {
        restore()
        if (frame.extendedState and Frame.ICONIFIED != 0) frame.extendedState = frame.extendedState and Frame.ICONIFIED.inv()
        frame.isVisible = true
        frame.isAlwaysOnTop = true
        frame.toFront()
        frame.requestFocus()
        frame.isAlwaysOnTop = false
    }

    /**
     * The refused launch's side: ask the holder on [port] to show its window. Returns the HTTP status, or null when
     * nothing answered. Loopback only; carries the configured bearer when the daemon requires one.
     */
    fun request(port: Int, token: String? = runCatching { RemoteAccess.clientToken() }.getOrNull(), timeoutMillis: Int = 3_000): Int? =
        runCatching {
            val connection = URI("http://127.0.0.1:$port$PATH").toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = timeoutMillis
                connection.readTimeout = timeoutMillis
                connection.doOutput = true
                token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                connection.outputStream.close()
                connection.responseCode
            } finally {
                connection.disconnect()
            }
        }.getOrNull()

    /**
     * Signal the holder on [port] (null = it has not bound one yet) and return the line to print. A window brought
     * forward is the whole answer; anything else falls back to a native dialog where a display exists.
     */
    fun notifyAlreadyRunning(port: Int?, dialog: (String) -> Unit = ::showDialog): String {
        val status = port?.let { request(it) }
        val asked = if (port != null && status != null) {
            "[agentHq-install] asked the running Infantry AI (port $port) to show its window (HTTP $status)"
        } else {
            "[agentHq-install] could not reach the running Infantry AI${port?.let { " on port $it" } ?: " (no port recorded yet)"}"
        }
        if (status == HttpStatusCode.NoContent.value) return asked
        return if (GraphicsEnvironment.isHeadless()) {
            "$asked; no display for an 'already running' dialog"
        } else {
            dialog(ALREADY_RUNNING)
            "$asked; showed '$ALREADY_RUNNING'"
        }
    }

    const val ALREADY_RUNNING = "Infantry AI is already running."

    private fun showDialog(message: String) =
        JOptionPane.showMessageDialog(null, message, "Infantry AI", JOptionPane.INFORMATION_MESSAGE)
}
