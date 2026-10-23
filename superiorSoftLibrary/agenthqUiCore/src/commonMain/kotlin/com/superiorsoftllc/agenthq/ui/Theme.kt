package com.superiorsoftllc.agenthq.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

/** Dark theme, one warm accent. Dense and developer-oriented: small type, thin borders, no ornament. */
object Palette {
    val Bg = Color(0xFF0E0F12)
    val Panel = Color(0xFF15171B)
    val Raised = Color(0xFF1C1F25)
    val Border = Color(0xFF262A31)
    val Text = Color(0xFFE6E7EA)
    val Muted = Color(0xFF8B909A)
    val Accent = Color(0xFFF59E0B)
    val Ok = Color(0xFF4ADE80)
    val Danger = Color(0xFFF87171)
    val Info = Color(0xFF60A5FA)
}

private val Scheme = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Color(0xFF1A1200),
    secondary = Palette.Info,
    background = Palette.Bg,
    onBackground = Palette.Text,
    surface = Palette.Panel,
    onSurface = Palette.Text,
    surfaceVariant = Palette.Raised,
    onSurfaceVariant = Palette.Muted,
    outline = Palette.Border,
    error = Palette.Danger,
)

val Mono = FontFamily.Monospace

@Composable
fun AgentHqTheme(content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = Scheme,
        typography = base.copy(
            bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
            bodySmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
            labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 13.sp),
            titleSmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
        ),
        content = content,
    )
}
