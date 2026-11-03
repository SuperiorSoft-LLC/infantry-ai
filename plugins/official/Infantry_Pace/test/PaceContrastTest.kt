package com.superiorsoftllc.infantry.plugins.official.pace

import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.plugins.official.pace.ThemeContrastAssert.Token
import kotlin.test.Test

/**
 * PACE-TREND-1 (docs/CHART-STYLE.md): every colour the pace chart draws with must be readable on its card (`Palette.Raised`) in BOTH
 * themes: text 4.5:1, graphics 3:1. Never dark text on a dark surface. A colour added to the drawing goes into [PaceColors] and
 * here.
 */
class PaceContrastTest {
    @Test fun `every text colour the pace chart uses reaches 4_5 to 1 in dark and light`() {
        ThemeContrastAssert.assertReadable(
            text = listOf(
                ThemeContrastAssert.TEXT, ThemeContrastAssert.MUTED, // titles, axis labels, active chips (Text, bold, on a highlight)
                Token("momentum rising") { PaceColors.trend(Trend.RISING) },
                Token("momentum falling") { PaceColors.trend(Trend.FALLING) },
                Token("momentum flat") { PaceColors.trend(Trend.FLAT) },
                Token("clip warning") { PaceColors.clipWarning },
            ),
        )
    }

    @Test fun `every line and marker the pace chart draws reaches 3 to 1 in dark and light`() {
        ThemeContrastAssert.assertReadable(
            text = emptyList(),
            graphics = listOf(
                Token("actual line") { PaceColors.actual },
                Token("current-rate forecast") { PaceColors.currentForecast },
                Token("recent-rate forecast and band") { PaceColors.recentForecast },
                Token("even-pace / axis line") { Palette.Muted },
                Token("100% cap line") { Palette.Warn },
            ),
        )
    }
}
