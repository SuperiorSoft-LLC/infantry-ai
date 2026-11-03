package com.superiorsoftllc.infantry.ui.shell

/**
 * WEB-1: the browser host. The daemon serves this page, so the daemon is the page's own origin: the WebSocket is
 * `ws(s)://<location.host>/ws`. The token is entered on first load and kept in the browser's localStorage (the only
 * secret store a page has; it is per origin, so another site cannot read it). No files, no env, no clipboard images:
 * every optional capability keeps its interface default, and the UI hides or notes what needs the desktop.
 */
class WasmPlatformHost(
    /** The bearer token to connect with; null reads [BrowserStorage.TOKEN_KEY]. */
    private val token: String? = BrowserStorage.get(BrowserStorage.TOKEN_KEY),
    /** The title bar's connection chip: back to the token screen. Null = the shared connection dialog. */
    override val onChangeConnection: (() -> Unit)? = null,
) : PlatformHost {
    override val defaultDaemonUrl: String = pageWebSocketUrl()

    override fun setting(key: String): String? = when (key) {
        ShellKeys.TOKEN -> token
        ShellKeys.WS_URL -> BrowserStorage.get(BrowserStorage.URL_KEY)
        else -> null
    }

    override fun readTextFile(path: String): String? = null

    override fun uiPref(key: String): String? = BrowserStorage.get(UI_PREFIX + key)

    override fun saveUiPref(key: String, value: String) = BrowserStorage.set(UI_PREFIX + key, value)

    override fun windowFocused(): Boolean = documentHasFocus()

    private companion object {
        const val UI_PREFIX = "infantry.ui."
    }
}

/** WEB-1: the page's localStorage. Every call tolerates a browser that refuses storage (private mode): null / no-op. */
object BrowserStorage {
    const val TOKEN_KEY = "infantry.token"
    const val URL_KEY = "infantry.wsUrl"

    fun get(key: String): String? = runCatching { storageGet(key) }.getOrNull()?.takeIf { it.isNotBlank() }
    fun set(key: String, value: String) { runCatching { storageSet(key, value) } }
    fun remove(key: String) { runCatching { storageRemove(key) } }
}

/** `ws://host/ws` for an http page, `wss://host/ws` for an https one. */
fun pageWebSocketUrl(): String = pageWebSocketUrlJs()

private fun storageGet(key: String): String? = js("window.localStorage.getItem(key)")
private fun storageSet(key: String, value: String): Unit = js("window.localStorage.setItem(key, value)")
private fun storageRemove(key: String): Unit = js("window.localStorage.removeItem(key)")
private fun documentHasFocus(): Boolean = js("document.hasFocus()")
private fun pageWebSocketUrlJs(): String =
    js("(window.location.protocol === 'https:' ? 'wss://' : 'ws://') + (window.location.host || 'localhost:8787') + '/ws'")

actual fun defaultPlatformHost(): PlatformHost = WasmPlatformHost()
