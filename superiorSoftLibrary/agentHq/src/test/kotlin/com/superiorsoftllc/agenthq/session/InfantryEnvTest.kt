package com.superiorsoftllc.agenthq.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InfantryEnvTest {
    @Test
    fun `INFANTRY_PORT alone is read as AGENTHQ_PORT`() {
        val env = mapOf("INFANTRY_PORT" to "9123")
        assertEquals("9123", InfantryEnv.get("AGENTHQ_PORT", env::get))
    }

    @Test
    fun `AGENTHQ_PORT alone still works`() {
        val env = mapOf("AGENTHQ_PORT" to "8801")
        assertEquals("8801", InfantryEnv.get("AGENTHQ_PORT", env::get))
    }

    @Test
    fun `INFANTRY wins when both are set`() {
        val env = mapOf("AGENTHQ_PORT" to "8801", "INFANTRY_PORT" to "9123")
        assertEquals("9123", InfantryEnv.get("AGENTHQ_PORT", env::get))
        assertEquals("9123", InfantryEnv.all(env)["AGENTHQ_PORT"])
    }

    @Test
    fun `only AGENTHQ names are aliased`() {
        val env = mapOf("INFANTRY_PATH" to "x")
        assertNull(InfantryEnv.get("PATH", env::get))
        assertNull(InfantryEnv.aliasOf("PATH"))
        assertEquals("INFANTRY_TOKEN_FILE", InfantryEnv.aliasOf("AGENTHQ_TOKEN_FILE"))
    }
}
