package com.superiorsoftllc.agenthq.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CONFIG-TABS-1: the configuration registry: every section in one tab, empty tabs hidden, the open tab picked sanely. */
class ConfigTabsTest {
    @Test fun `every section sits in exactly one tab and every tab has a section`() {
        val byTab = ConfigTab.entries.flatMap { ConfigEntry.of(it) }
        assertEquals(ConfigEntry.entries.toList(), byTab.sortedBy { it.ordinal }, "each entry is listed once, under its own tab")
        assertEquals(ConfigEntry.entries.size, byTab.toSet().size)
        ConfigTab.entries.forEach { assertTrue(ConfigEntry.of(it).isNotEmpty(), "tab $it has no section") }
    }

    @Test fun `Appearance is the first tab`() {
        assertEquals(ConfigTab.APPEARANCE, ConfigTab.entries.first())
        assertEquals(ConfigTab.APPEARANCE, pickConfigTab(null, ConfigEntry.entries.toSet()))
    }

    @Test fun `a tab with nothing to show is hidden`() {
        val present = setOf(ConfigEntry.BLUR, ConfigEntry.AGENT_SCOPE, ConfigEntry.VOICE)
        assertEquals(listOf(ConfigTab.APPEARANCE, ConfigTab.GENERAL, ConfigTab.NOTIFICATIONS), visibleConfigTabs(present))
        assertEquals(ConfigTab.entries.toList(), visibleConfigTabs(ConfigEntry.entries.toSet()))
    }

    @Test fun `the wanted tab opens when it has something, else the first visible tab`() {
        val present = setOf(ConfigEntry.AGENT_SCOPE, ConfigEntry.LOGS)
        assertEquals(ConfigTab.ABOUT, pickConfigTab(ConfigTab.ABOUT, present))
        assertEquals(ConfigTab.GENERAL, pickConfigTab(ConfigTab.SECRETS, present), "Secrets is hidden (older daemon): fall back")
        assertNull(pickConfigTab(ConfigTab.GENERAL, emptySet()))
    }

    @Test fun `titles are unique so a section is never ambiguous`() {
        assertEquals(ConfigEntry.entries.size, ConfigEntry.entries.map { it.title }.toSet().size)
    }

    // CONFIG-SEARCH-1 (ticket 3463d1b2): search over the registry.
    private val all = ConfigEntry.entries.toSet()

    @Test fun `search matches the title, the applies-to copy and the keywords, case-insensitively`() {
        assertEquals(listOf(ConfigEntry.BLUR), searchConfig("BLUR behind", all), "title")
        assertTrue(ConfigEntry.THEME in searchConfig("Colour", all), "keyword")
        assertTrue(ConfigEntry.AGENT_SCOPE in searchConfig("sidebar agent list", all), "applies-to copy")
        assertTrue(ConfigEntry.SECRETS in searchConfig("  api KEY ", all), "trimmed, multi-word keyword")
    }

    @Test fun `every word must match, a blank query or an absent section finds nothing`() {
        assertEquals(emptyList(), searchConfig("", all))
        assertEquals(emptyList(), searchConfig("   ", all))
        assertEquals(emptyList(), searchConfig("blur zzqx", all))
        assertEquals(emptyList(), searchConfig("keystore", all - ConfigEntry.SECRETS), "a hidden section is never offered")
    }

    @Test fun `results keep display order`() {
        val r = searchConfig("update", all)
        assertEquals(r.sortedBy { it.ordinal }, r)
        assertTrue(ConfigEntry.UPDATES in r && ConfigEntry.SERVER_UPDATE in r)
    }
}
