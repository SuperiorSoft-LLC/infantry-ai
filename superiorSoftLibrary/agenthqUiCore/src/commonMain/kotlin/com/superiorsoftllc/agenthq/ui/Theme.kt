package com.superiorsoftllc.agenthq.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.superiorsoftllc.agenthq.ui.model.ThemeSettings
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

/** Neutral tones of one theme mode; the accent is chosen separately (THEME-1). */
private class Tones(
    val bg: Color, val panel: Color, val raised: Color, val border: Color, val text: Color, val muted: Color,
    val ok: Color, val danger: Color, val info: Color,
)

private val DarkTones = Tones(
    bg = Color(0xFF0E0F12), panel = Color(0xFF15171B), raised = Color(0xFF1C1F25), border = Color(0xFF262A31),
    text = Color(0xFFE6E7EA), muted = Color(0xFF8B909A), ok = Color(0xFF4ADE80), danger = Color(0xFFF87171), info = Color(0xFF60A5FA),
)

private val LightTones = Tones(
    bg = Color(0xFFF4F5F7), panel = Color(0xFFFFFFFF), raised = Color(0xFFE9EBEF), border = Color(0xFFD3D7DE),
    text = Color(0xFF1A1C20), muted = Color(0xFF5E6470), ok = Color(0xFF15803D), danger = Color(0xFFDC2626), info = Color(0xFF2563EB),
)

/**
 * Dense and developer-oriented: small type, thin borders, no ornament. The default is dark with one warm (amber) accent.
 * THEME-1: every colour reads [theme], a snapshot state, so a change in Configuration > Theme recomposes every
 * composable that drew from the palette, live. [theme] has one writer: the window applying the user's stored choice.
 */
object Palette {
    var theme: ThemeSettings by mutableStateOf(ThemeSettings())

    private val tones: Tones get() = if (theme.dark) DarkTones else LightTones

    val Bg: Color get() = tones.bg
    val Panel: Color get() = tones.panel
    val Raised: Color get() = tones.raised
    val Border: Color get() = tones.border
    val Text: Color get() = tones.text
    val Muted: Color get() = tones.muted
    val Accent: Color get() = Color(theme.accent.argb)
    val Ok: Color get() = tones.ok
    val Danger: Color get() = tones.danger
    val Info: Color get() = tones.info

    /** Text on the accent: the original dark brown on light accents (amber), white on deep ones (the emblem red). */
    val OnAccent: Color get() = if (Accent.luminance() > 0.4f) Color(0xFF1A1200) else Color.White
}

private fun schemeFor(theme: ThemeSettings): ColorScheme {
    val t = if (theme.dark) DarkTones else LightTones
    val accent = Color(theme.accent.argb)
    val onAccent = if (accent.luminance() > 0.4f) Color(0xFF1A1200) else Color.White
    return if (theme.dark) {
        darkColorScheme(
            primary = accent, onPrimary = onAccent, secondary = t.info,
            background = t.bg, onBackground = t.text, surface = t.panel, onSurface = t.text,
            surfaceVariant = t.raised, onSurfaceVariant = t.muted, outline = t.border, error = t.danger,
        )
    } else {
        lightColorScheme(
            primary = accent, onPrimary = onAccent, secondary = t.info,
            background = t.bg, onBackground = t.text, surface = t.panel, onSurface = t.text,
            surfaceVariant = t.raised, onSurfaceVariant = t.muted, outline = t.border, error = t.danger,
            // Neutral greys for dialogs and menus, instead of Material's default lavender tint.
            surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF8F9FA),
            surfaceContainer = Color(0xFFF2F3F5), surfaceContainerHigh = Color(0xFFECEEF1), surfaceContainerHighest = Color(0xFFE5E7EB),
        )
    }
}

val Mono = FontFamily.Monospace

@Composable
fun AgentHqTheme(content: @Composable () -> Unit) {
    val base = Typography()
    val theme = Palette.theme
    val scheme = remember(theme) { schemeFor(theme) }
    MaterialTheme(
        colorScheme = scheme,
        typography = base.copy(
            bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
            bodySmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
            labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 13.sp),
            titleSmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
        ),
        content = content,
    )
}
