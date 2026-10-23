package com.superiorsoftllc.agenthq.ui.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.superiorsoftllc.agenthq.ui.pair.SavedServer
import com.superiorsoftllc.agenthq.ui.pair.SavedServers

/** SWITCH-1: one entry of the server switcher. [url] null is "This computer" (the in-process daemon). */
class ServerChoice(val name: String, val url: String?, val token: String?) {
    val isLocal: Boolean get() = url == null
    override fun toString(): String = "ServerChoice(name=$name, url=$url, token=${if (token == null) "none" else "set"})"
}

/**
 * SWITCH-1: the model behind the switcher: "This computer" (when the host has a [LocalServer]) plus the saved servers,
 * which one is active, and add / edit / remove / select. Shared by desktop, Android and the browser; tokens live only in
 * [store] (the platform's secure storage), never here beyond the choice handed to a connect call.
 */
class ServerSwitcher(
    private val store: SavedServers?,
    private val local: LocalServer?,
    activeUrl: String?,
    private val persistActive: (String?) -> Unit = {},
) {
    private var version by mutableIntStateOf(0)

    /** The active server's URL; null = this computer (also the fallback when the saved one was removed). */
    var activeUrl: String? = activeUrl?.takeIf { url -> store?.list().orEmpty().any { it.url == url } }
        private set

    val hasLocal: Boolean get() = local != null

    fun choices(): List<ServerChoice> {
        version // read: recompose on change
        val saved = store?.list().orEmpty().map { ServerChoice(it.name, it.url, it.token) }
        return (if (local != null) listOf(ServerChoice(LOCAL_NAME, null, null)) else emptyList()) + saved
    }

    /** The active entry, or null when there is none (no local daemon and nothing saved). */
    fun active(): ServerChoice? = choices().firstOrNull { it.url == activeUrl } ?: choices().firstOrNull()

    fun add(name: String, url: String, token: String?): String? {
        val u = url.trim()
        if (!(u.startsWith("ws://") || u.startsWith("wss://"))) return "The address must start with ws:// or wss://"
        if (store == null) return "This device cannot save servers"
        store.save(SavedServer(name.trim().ifBlank { com.superiorsoftllc.agenthq.ui.pair.PairingHosts.labelOf(u) }, u, token?.trim()?.ifEmpty { null }))
        version++
        return null
    }

    /** Replaces the server at [oldUrl]. Returns an error, or null; [wasActive] says whether the live connection must be redone. */
    fun edit(oldUrl: String, name: String, url: String, token: String?): String? {
        val error = add(name, url, token)
        if (error != null) return error
        if (url.trim() != oldUrl) store?.remove(oldUrl)
        if (activeUrl == oldUrl) { activeUrl = url.trim(); persistActive(activeUrl) }
        version++
        return null
    }

    /** Removes the saved server at [url]; removing the active one falls back to this computer (the caller reconnects). */
    fun remove(url: String): Boolean {
        store?.remove(url)
        val wasActive = activeUrl == url
        if (wasActive) { activeUrl = null; persistActive(null) }
        version++
        return wasActive
    }

    /**
     * Makes [choice] the active server and calls [connect] with its URL and token (for this computer: starts the local
     * daemon first; leaving it keeps that daemon and its agents running). Returns an error message when it could not, else null.
     */
    fun select(choice: ServerChoice, connect: (url: String, token: String?) -> Unit): String? {
        if (choice.isLocal) {
            val server = local ?: return "This device has no local server"
            val endpoint = server.start() ?: return "The local daemon would not start"
            activeUrl = null
            persistActive(null)
            version++
            connect(endpoint.url, endpoint.token)
        } else {
            activeUrl = choice.url
            persistActive(choice.url)
            version++
            connect(choice.url!!, choice.token)
        }
        return null
    }

    companion object {
        const val LOCAL_NAME = "This computer"
    }
}
