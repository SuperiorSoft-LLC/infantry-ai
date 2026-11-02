package com.superiorsoftllc.agenthq.plugins.official.run.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SecretScrubberTest {
    private val pw = "hunter2-correct-horse"

    @Test
    fun `masks a value in a line, and leaves short values and other text alone`() {
        val s = SecretScrubber(listOf(pw, "abc"))
        assertEquals("pass=${SecretScrubber.MASK} abc", s.line("pass=$pw abc"))
        assertEquals("nothing here", s.line("nothing here"))
    }

    @Test
    fun `masks a value split across two chunks and holds back only a possible prefix`() {
        val s = SecretScrubber(listOf(pw))
        val first = s.bytes("key: hunter2-cor".toByteArray())
        assertEquals("key: ", String(first), "the tail that may start the value waits for the next chunk")
        val second = s.bytes("rect-horse done\r\n".toByteArray())
        assertEquals("${SecretScrubber.MASK} done\r\n", String(second))
    }

    @Test
    fun `a held prefix that turns out not to be the value comes out unchanged`() {
        val s = SecretScrubber(listOf(pw))
        val out = String(s.bytes("say hunt".toByteArray())) + String(s.bytes("ing season".toByteArray())) + String(s.flush())
        assertEquals("say hunting season", out)
    }

    @Test
    fun `flush releases what is held at the end of the run`() {
        val s = SecretScrubber(listOf(pw))
        assertEquals("", String(s.bytes("hunter".toByteArray())))
        assertEquals("hunter", String(s.flush()))
    }

    @Test
    fun `no values means bytes pass straight through`() {
        val s = SecretScrubber(emptyList())
        val chunk = "hunter2".toByteArray()
        assertEquals("hunter2", String(s.bytes(chunk)))
        assertFalse(s.active)
    }

    @Test
    fun `toString never shows a value`() {
        assertFalse(SecretScrubber(listOf(pw)).toString().contains(pw))
    }
}
