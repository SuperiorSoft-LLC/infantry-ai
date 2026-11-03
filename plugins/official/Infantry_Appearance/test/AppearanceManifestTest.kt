package com.superiorsoftllc.infantry.plugins.official.appearance

import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** CONFIG-3: module.json is the source of the Appearance tab on desktop; the compiled-in Kotlin manifest must say the same. */
class AppearanceManifestTest {
    private val manifest = Json { ignoreUnknownKeys = true }
        .decodeFromString(ModuleManifest.serializer(), File(System.getProperty("infantryAppearance.moduleJson")).readText())

    @Test
    fun `module json parses and its config equals the compiled-in config`() {
        assertEquals("Infantry_Appearance", manifest.name)
        assertEquals(APPEARANCE_CONFIG, manifest.config)
        assertEquals(listOf(THEME_FILL_ID), manifest.fills.map { it.node })
        assertEquals(listOf("Appearance", "Appearance", "Appearance"), manifest.config.map { it.tab })
        assertTrue(manifest.config.flatMap { it.fields }.map { it.key }.containsAll(listOf(ACCENT_KEY, DARK_KEY, BLUR_KEY)))
    }
}
