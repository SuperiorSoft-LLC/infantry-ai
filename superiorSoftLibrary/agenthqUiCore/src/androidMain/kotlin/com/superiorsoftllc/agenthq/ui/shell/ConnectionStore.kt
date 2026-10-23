package com.superiorsoftllc.agenthq.ui.shell

import android.content.SharedPreferences

/** MOBILE-CONNECT-1: a string key/value file (a SharedPreferences in the app, a map in tests). */
interface KeyValueStore {
    fun get(key: String): String?

    /** Writes [value]; null removes the key. */
    fun put(key: String, value: String?)
}

/** MOBILE-CONNECT-1: seals the bearer token at rest. [decrypt] returns null (never throws) for anything it cannot open. */
interface TokenCipher {
    fun encrypt(plain: String): String
    fun decrypt(sealed: String): String?
}

/** [SharedPreferences] as a [KeyValueStore]. */
class PrefsKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}

data class SavedConnection(val url: String, val token: String?) {
    /** Never print the token. */
    override fun toString(): String = "SavedConnection(url=$url, token=${if (token == null) "none" else "set"})"
}

/**
 * MOBILE-CONNECT-1: the daemon connection a phone keeps between launches. The URL lives in the plain "agenthq"
 * prefs under [ShellKeys.WS_URL] (the key the activity always read); the token lives ONLY in [secure], sealed by
 * [cipher]. The plain prefs never hold [ShellKeys.TOKEN]: a legacy plain token is sealed and removed on load.
 */
class ConnectionStore(private val plain: KeyValueStore, private val secure: KeyValueStore, private val cipher: TokenCipher) {
    fun load(): SavedConnection? {
        migrateLegacyPlainToken()
        val url = plain.get(ShellKeys.WS_URL)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return SavedConnection(url, sealedToken())
    }

    /** Saves [url] (trimmed) and [token]; a null or blank token removes the saved one. */
    fun save(url: String, token: String?) {
        plain.put(ShellKeys.WS_URL, url.trim())
        plain.put(ShellKeys.TOKEN, null)
        val clean = token?.trim()?.takeIf { it.isNotEmpty() }
        secure.put(SEALED_TOKEN, clean?.let { cipher.encrypt(it) })
    }

    fun clear() {
        plain.put(ShellKeys.WS_URL, null)
        plain.put(ShellKeys.TOKEN, null)
        secure.put(SEALED_TOKEN, null)
    }

    /** A settings source for [LayeredSettings], under the CLI's keys. */
    fun setting(key: String): String? = when (key) {
        ShellKeys.WS_URL -> load()?.url
        ShellKeys.TOKEN -> load()?.token
        else -> null
    }

    private fun sealedToken(): String? =
        secure.get(SEALED_TOKEN)?.let { runCatching { cipher.decrypt(it) }.getOrNull() }?.takeIf { it.isNotEmpty() }

    private fun migrateLegacyPlainToken() {
        val legacy = plain.get(ShellKeys.TOKEN) ?: return
        val clean = legacy.trim()
        if (clean.isNotEmpty() && secure.get(SEALED_TOKEN) == null) {
            runCatching { cipher.encrypt(clean) }.getOrNull()?.let { secure.put(SEALED_TOKEN, it) }
        }
        // Removed even if sealing failed: a token in plain prefs is the one outcome that must not survive.
        plain.put(ShellKeys.TOKEN, null)
    }

    companion object {
        const val SEALED_TOKEN = "token_v1"
    }
}

/** MOBILE-CONNECT-1: what the activity shows first. */
sealed interface LaunchDecision {
    /** Nothing saved and no Intent extras: the connect screen. */
    data object Setup : LaunchDecision

    /** Settings to connect with: Intent extras first (tests, adb), then the saved connection. */
    class Connect(val settings: LayeredSettings, val fromExtras: Boolean) : LaunchDecision
}

fun launchDecision(extras: (String) -> String?, store: ConnectionStore): LaunchDecision {
    val saved = store.load()
    val extraUrl = extras(ShellKeys.WS_URL)?.takeIf { it.isNotBlank() }
    if (extraUrl == null && saved == null) return LaunchDecision.Setup
    val fromSaved: (String) -> String? = { key ->
        when (key) {
            ShellKeys.WS_URL -> saved?.url
            ShellKeys.TOKEN -> saved?.token
            else -> null
        }
    }
    return LaunchDecision.Connect(LayeredSettings(extras, fromSaved), fromExtras = extraUrl != null)
}
