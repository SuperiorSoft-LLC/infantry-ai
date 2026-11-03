package com.superiorsoftllc.infantry.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RENAME-2 AliasTest (env half): `INFANTRY_*` is read first, the legacy `AGENTHQ_*` name still works. */
class InfantryEnvTest {
    @Test
    fun `INFANTRY_PORT alone is read`() {
        val env = mapOf("INFANTRY_PORT" to "9123")
        assertEquals("9123", InfantryEnv.get("INFANTRY_PORT", env::get))
    }

    @Test
    fun `legacy AGENTHQ_PORT alone still works`() {
        val env = mapOf("AGENTHQ_PORT" to "8801")
        assertEquals("8801", InfantryEnv.get("INFANTRY_PORT", env::get))
    }

    @Test
    fun `INFANTRY wins when both are set`() {
        val env = mapOf("AGENTHQ_PORT" to "8801", "INFANTRY_PORT" to "9123")
        assertEquals("9123", InfantryEnv.get("INFANTRY_PORT", env::get))
        assertEquals("9123", InfantryEnv.all(env)["AGENTHQ_PORT"])
        assertEquals("9123", InfantryEnv.all(env)["INFANTRY_PORT"])
    }

    @Test
    fun `all exposes both spellings of a legacy-only setting`() {
        val all = InfantryEnv.all(mapOf("AGENTHQ_TOKEN" to "t"))
        assertEquals("t", all["INFANTRY_TOKEN"])
        assertEquals("t", all["AGENTHQ_TOKEN"])
    }

    @Test
    fun `only the two prefixes are aliased`() {
        assertNull(InfantryEnv.get("PATH", mapOf("INFANTRY_PATH" to "x")::get))
        assertNull(InfantryEnv.aliasOf("PATH"))
        assertEquals("AGENTHQ_TOKEN_FILE", InfantryEnv.aliasOf("INFANTRY_TOKEN_FILE"))
        assertEquals("INFANTRY_TOKEN_FILE", InfantryEnv.aliasOf("AGENTHQ_TOKEN_FILE"))
    }

    @Test
    fun `legacy system properties are mirrored to the new name`() {
        val p = java.util.Properties().apply { setProperty("agenthq.installed", "true"); setProperty("agenthq.x", "old"); setProperty("infantry.x", "new") }
        InfantryEnv.mirrorProperties(p)
        assertEquals("true", p.getProperty("infantry.installed"))
        assertEquals("new", p.getProperty("infantry.x"))
        assertTrue(p.getProperty("agenthq.installed") == "true")
    }
}
