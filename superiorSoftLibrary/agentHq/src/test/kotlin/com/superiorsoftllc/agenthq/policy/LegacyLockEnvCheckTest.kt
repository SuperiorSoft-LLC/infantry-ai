package com.superiorsoftllc.agenthq.policy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyLockEnvCheckTest {
    private fun env(vararg kv: Pair<String, String>): (String) -> String? = mapOf(*kv)::get

    @Test fun `the retired list is read from the resource`() {
        assertTrue(LegacyLockEnvCheck.retiredNames.isNotEmpty())
    }

    @Test fun `a retired flag with no pinned policy refuses to start`() {
        val name = LegacyLockEnvCheck.retiredNames.first()
        val v = assertNotNull(LegacyLockEnvCheck.verdict(env(name to "1")))
        assertTrue(v.refuse)
        assertTrue(name in v.message && "NOTHING is locked" in v.message)
    }

    @Test fun `a pinned policy or an unset flag is fine`() {
        val name = LegacyLockEnvCheck.retiredNames.first()
        assertNull(LegacyLockEnvCheck.verdict(env(name to "1", "AGENTHQ_POLICY" to "x")))
        assertNull(LegacyLockEnvCheck.verdict(env()))
        assertNull(LegacyLockEnvCheck.verdict(env(name to "0")))
    }

    @Test fun `the opt-out starts with a warning only`() {
        val name = LegacyLockEnvCheck.retiredNames.first()
        val v = assertNotNull(LegacyLockEnvCheck.verdict(env(name to "1", LegacyLockEnvCheck.OPT_OUT_ENV to "1")))
        assertFalse(v.refuse)
        assertEquals(true, "starting anyway" in v.message)
    }
}
