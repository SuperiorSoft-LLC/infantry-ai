package com.superiorsoftllc.agenthq.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BgCliTest {
    @Test fun `run takes a name and everything after -- verbatim`() {
        val p = BgCli.parse(listOf("run", "--name", "gate", "--", "gradlew", "test", "--name", "x"))
        assertEquals(BgCli.Parsed.Run("gate", listOf("gradlew", "test", "--name", "x")), p)
    }

    @Test fun `run --keep marks the job to survive its session closing`() {
        assertEquals(BgCli.Parsed.Run("dev", listOf("npm", "run", "dev"), keep = true), BgCli.parse(listOf("run", "--keep", "--name", "dev", "--", "npm", "run", "dev")))
        assertEquals(false, (BgCli.parse(listOf("run", "--", "x")) as BgCli.Parsed.Run).keep)
    }

    @Test fun `run without a command or with a stray option is refused`() {
        assertTrue(BgCli.parse(listOf("run", "--name", "gate")) is BgCli.Parsed.Bad)
        assertTrue(BgCli.parse(listOf("run", "--bogus", "--", "x")) is BgCli.Parsed.Bad)
        assertTrue(BgCli.parse(listOf("run", "--name")) is BgCli.Parsed.Bad)
    }

    @Test fun `list status logs stop`() {
        assertEquals(BgCli.Parsed.Show("list", null), BgCli.parse(listOf("list")))
        assertEquals(BgCli.Parsed.Show("stop", "ab12"), BgCli.parse(listOf("stop", "ab12")))
        assertTrue(BgCli.parse(listOf("logs")) is BgCli.Parsed.Bad)
        assertTrue(BgCli.parse(listOf("nope")) is BgCli.Parsed.Bad)
        assertTrue(BgCli.parse(emptyList()) is BgCli.Parsed.Bad)
    }
}
