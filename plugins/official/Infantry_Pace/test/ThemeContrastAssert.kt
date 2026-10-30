package com.superiorsoftllc.agenthq.plugins.official.pace

import androidx.compose.ui.graphics.Color
import com.superiorsoftllc.agenthq.ui.Contrast
import com.superiorsoftllc.agenthq.ui.Palette
import com.superiorsoftllc.agenthq.ui.model.ThemeSettings
import kotlin.test.fail

/**
 * CHART-STYLE-1: shared test helper (docs/CHART-STYLE.md). Asserts the WCAG ratio of a chart's text and graphic colours
 * against its card background in both themes. Copied from Infantry_Stats (PACE-TREND-1): pass the palette tokens the chart draws with.
 */
object ThemeContrastAssert {
    /** A colour a chart draws with, read from the live palette so each theme is checked. */
    class Token(val name: String, val of: () -> Color)

    val TEXT = Token("Palette.Text") { Palette.Text }
    val MUTED = Token("Palette.Muted") { Palette.Muted }
    val ACCENT = Token("Palette.Accent") { Palette.Accent }

    /** Every [text] token must reach 4.5:1 and every [graphics] token 3:1 on [card] (default: the raised card), dark and light. */
    fun assertReadable(
        text: List<Token>, graphics: List<Token> = emptyList(), card: () -> Color = { Palette.Raised },
    ) {
        val saved = Palette.theme
        try {
            for (dark in listOf(true, false)) {
                Palette.theme = ThemeSettings(dark = dark)
                val bg = card()
                val theme = if (dark) "dark" else "light"
                for (t in text) check(t, bg, Contrast.TEXT_AA, "text", theme)
                for (g in graphics) check(g, bg, Contrast.GRAPHIC_AA, "graphic", theme)
            }
        } finally {
            Palette.theme = saved
        }
    }

    private fun check(t: Token, bg: Color, min: Double, kind: String, theme: String) {
        val r = Contrast.ratio(t.of(), bg)
        if (r < min) fail("${t.name} as $kind on the card is ${"%.2f".format(r)}:1 in the $theme theme, needs $min:1")
    }
}
