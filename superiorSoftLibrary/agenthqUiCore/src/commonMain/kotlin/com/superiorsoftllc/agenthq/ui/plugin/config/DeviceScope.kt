package com.superiorsoftllc.agenthq.ui.plugin.config

import com.superiorsoftllc.agenthq.plugin.api.ConfigScopes
import com.superiorsoftllc.agenthq.ui.model.CHAT_MARKDOWN_PREF_KEY
import com.superiorsoftllc.agenthq.ui.model.PROGRESS_STALE_PREF_KEY
import com.superiorsoftllc.agenthq.ui.model.REASONING_PLACEMENT_PREF_KEY
import com.superiorsoftllc.agenthq.ui.model.ThemeSettings
import com.superiorsoftllc.agenthq.ui.BLUR_PREF_KEY
import com.superiorsoftllc.agenthq.ui.THEME_PREF_KEY
import com.superiorsoftllc.agenthq.ui.shell.PlatformHost
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** CONFIG-4: where `scope:"device"` overrides live, a string store; [PlatformHost.uiPref] / [PlatformHost.saveUiPref] on every host. */
interface DevicePrefs {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

/** [DevicePrefs] over the host's ui prefs; the store cannot delete, so a cleared override is stored as the empty string. */
class HostDevicePrefs(private val host: PlatformHost) : DevicePrefs {
    override fun get(key: String): String? = host.uiPref(key)
    override fun put(key: String, value: String) = host.saveUiPref(key, value)
}

/** CONFIG-4 (docs/CONFIG-MANIFEST-DESIGN.md sections 0a, 2, 8): device-scope key naming, value codec and the one-time pref migration. */
object DeviceScope {
    const val DEVICE = ConfigScopes.DEVICE

    /** Marker: set once the old pref keys have been read, so the migration never runs twice. */
    const val MIGRATED_MARKER = "cfg.@migrated.v1"

    fun prefKey(node: String, key: String): String = "cfg.$node.$key"

    fun encode(value: JsonElement): String = value.toString()

    /** The stored JSON, or null for unset / cleared / unreadable. */
    fun decode(raw: String?): JsonElement? =
        raw?.takeIf { it.isNotEmpty() }?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }

    private const val THEME_NODE = "infantry.shell.theme"
    private const val CHAT_NODE = "infantry.shell.chat"
    private const val BOARD_NODE = "infantry.shell.board"

    /**
     * Copies THEME, BLUR, MARKDOWN, REASONING and PROGRESS_STALE into their `cfg.<node>.<key>` keys, once. The old keys
     * are left as they are (the shell still reads them), and a new key that already holds a value is never overwritten.
     */
    fun migrate(prefs: DevicePrefs) {
        if (prefs.get(MIGRATED_MARKER) != null) return
        fun copy(node: String, key: String, value: JsonElement?) {
            val k = prefKey(node, key)
            if (value != null && prefs.get(k) == null) prefs.put(k, encode(value))
        }
        prefs.get(THEME_PREF_KEY)?.takeIf { it.isNotBlank() }?.let {
            val theme = ThemeSettings.decode(it)
            copy(THEME_NODE, "accent", JsonPrimitive(theme.accent.name))
            copy(THEME_NODE, "dark", JsonPrimitive(theme.dark))
        }
        prefs.get(BLUR_PREF_KEY)?.let { copy(THEME_NODE, "blur", JsonPrimitive(it != "off")) }
        prefs.get(CHAT_MARKDOWN_PREF_KEY)?.let { copy(CHAT_NODE, "markdown", JsonPrimitive(it)) }
        prefs.get(REASONING_PLACEMENT_PREF_KEY)?.let { copy(CHAT_NODE, "reasoning", JsonPrimitive(it)) }
        prefs.get(PROGRESS_STALE_PREF_KEY)?.toIntOrNull()?.let { copy(BOARD_NODE, "progressStale", JsonPrimitive(it)) }
        prefs.put(MIGRATED_MARKER, "1")
    }
}
