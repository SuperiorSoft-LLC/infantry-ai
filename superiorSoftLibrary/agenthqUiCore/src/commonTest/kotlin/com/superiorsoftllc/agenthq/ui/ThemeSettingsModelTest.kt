package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.ThemeAccent
import com.superiorsoftllc.agenthq.ui.model.ThemeSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** THEME-1: the stored theme round-trips, and anything unreadable falls back to the default (red on dark). */
class ThemeSettingsModelTest {
    @Test fun `the default is red on dark, and a stored amber stays amber`() {
        val d = ThemeSettings()
        assertEquals(ThemeAccent.RED, d.accent)
        assertTrue(d.dark)
        assertTrue(d.isDefault)
        assertEquals(0xFFEF4444, ThemeAccent.RED.argb)
        assertEquals(ThemeSettings(ThemeAccent.AMBER, dark = true), ThemeSettings.decode("AMBER;dark"))
    }

    @Test fun `every choice round-trips through its stored form`() {
        for (accent in ThemeAccent.entries) for (dark in listOf(true, false)) {
            val t = ThemeSettings(accent, dark)
            assertEquals(t, ThemeSettings.decode(t.encode()))
        }
        assertFalse(ThemeSettings(ThemeAccent.INFANTRY, dark = true).isDefault)
    }

    @Test fun `nothing stored or an unknown value decodes to the default`() {
        assertEquals(ThemeSettings(), ThemeSettings.decode(null))
        assertEquals(ThemeSettings(), ThemeSettings.decode(""))
        assertEquals(ThemeSettings(), ThemeSettings.decode("PINK;sideways"))
        assertEquals(ThemeSettings(ThemeAccent.RED, dark = false), ThemeSettings.decode("PINK;light"))
    }
}
