package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.graphics.Color
import com.superiorsoftllc.infantry.ui.model.ThemeAccent
import com.superiorsoftllc.infantry.ui.model.ThemeSettings
import kotlin.test.Test
import kotlin.test.assertEquals

/** Ticket b4735325: danger is the same plain red under every accent (it used to turn pink under a red one). */
class DangerColourTest {
    @Test
    fun dangerIsThePlainRedForRedAndNonRedAccents() {
        val saved = Palette.theme
        try {
            for (dark in listOf(true, false)) {
                val plain = if (dark) Color(0xFFEF4444) else Color(0xFFDC2626)
                for (accent in listOf(ThemeAccent.RED, ThemeAccent.INFANTRY, ThemeAccent.AMBER, ThemeAccent.BLUE)) {
                    Palette.theme = ThemeSettings(accent, dark)
                    assertEquals(plain, Palette.Danger, "$accent dark=$dark")
                }
            }
        } finally {
            Palette.theme = saved
        }
    }
}
