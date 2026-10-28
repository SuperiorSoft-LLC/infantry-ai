package com.superiorsoftllc.agenthq.ui.model

/**
 * THEME-1: an accent preset. [argb] is the colour as 0xAARRGGBB, so this model stays free of Compose types.
 * [RED] is the default: a red light enough to read as text on the dark background (5:1). [AMBER] is the app's
 * original look; [INFANTRY] is the deeper red of the Infantry AI emblem (the app icon). [red] marks the red accents,
 * against which the danger colour turns orange so an error never reads as a highlight.
 */
enum class ThemeAccent(val label: String, val argb: Long, val red: Boolean = false) {
    RED("Red (default)", 0xFFEF4444, red = true),
    AMBER("Amber", 0xFFF59E0B),
    INFANTRY("Infantry red", 0xFFE11D2A, red = true),
    BLUE("Blue", 0xFF3B82F6),
    GREEN("Green", 0xFF22C55E),
    VIOLET("Violet", 0xFFA855F7),
    TEAL("Teal", 0xFF14B8A6),
}

/** THEME-1: the user's Material theme choice. The default is a red accent on dark. */
data class ThemeSettings(
    val accent: ThemeAccent = ThemeAccent.RED,
    val dark: Boolean = true,
) {
    val isDefault: Boolean get() = this == ThemeSettings()

    /** The stored form, e.g. `RED;dark`. */
    fun encode(): String = "${accent.name};${if (dark) "dark" else "light"}"

    companion object {
        const val TITLE = "Theme"
        const val APPLIES_TO = "Applies to: the whole app, at once. The accent colours buttons, focus borders and highlights. Remembered across launches."

        /** Reads [encode]'s form; anything missing or unknown falls back to the default for that part. */
        fun decode(stored: String?): ThemeSettings {
            if (stored.isNullOrBlank()) return ThemeSettings()
            val parts = stored.split(';')
            val accent = ThemeAccent.entries.firstOrNull { it.name == parts.getOrNull(0) } ?: ThemeAccent.RED
            val dark = parts.getOrNull(1) != "light"
            return ThemeSettings(accent, dark)
        }
    }
}
