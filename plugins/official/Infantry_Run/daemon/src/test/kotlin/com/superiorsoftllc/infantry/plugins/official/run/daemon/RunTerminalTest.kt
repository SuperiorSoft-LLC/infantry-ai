package com.superiorsoftllc.infantry.plugins.official.run.daemon

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** RUN-CONSOLE (ticket 67f117b6): the run's byte scrollback cap and the plain-text line view of a terminal stream. */
class RunTerminalTest {
    private fun lines(vararg chunks: String): List<String> {
        val out = mutableListOf<String>()
        val d = AnsiLineDecoder { out += it }
        chunks.forEach { d.feed(it.toByteArray()) }
        d.flush()
        return out
    }

    @Test fun `scrollback keeps the newest bytes up to the cap and counts what it dropped`() {
        val s = ByteScrollback { 10 }
        s.append("0123456789".toByteArray())
        s.append("abcde".toByteArray())
        assertEquals("56789abcde", String(s.snapshot()))
        assertEquals(15, s.totalBytes)
        assertEquals(5, s.droppedBytes)
    }

    @Test fun `scrollback cuts to the next line break so a replay opens on a whole line`() {
        val s = ByteScrollback { 9 }
        s.append("aaaa\nbbb\ncc".toByteArray()) // 11 bytes: a plain cut would open on "a\nbbb..."
        assertContentEquals("bbb\ncc".toByteArray(), s.snapshot())
        assertEquals(5, s.droppedBytes)
    }

    @Test fun `scrollback cap is read on every append so a changed setting applies to a running run`() {
        var cap = 100
        val s = ByteScrollback { cap }
        s.append(ByteArray(80) { 'x'.code.toByte() })
        assertEquals(80, s.snapshot().size)
        cap = 20
        s.append("y".toByteArray())
        assertEquals(20, s.snapshot().size)
        assertEquals('y'.code.toByte(), s.snapshot().last())
    }

    @Test fun `a carriage return progress line ends as its last state`() {
        assertEquals(listOf("progress 100%", "done"), lines("progress 0%\rprogress 50%\rprogress 100%\n", "done\n"))
    }

    @Test fun `colours and other escape sequences are dropped`() {
        assertEquals(listOf("ok red"), lines("\u001b[32mok\u001b[0m \u001b[1;31mred\u001b[0m\n"))
    }

    @Test fun `OSC titles hyperlinks and clipboard writes never reach the text`() {
        assertEquals(
            listOf("link here"),
            lines("\u001b]0;title\u0007\u001b]52;c;aGVsbG8=\u0007\u001b]8;;https://x.example\u001b\\link\u001b]8;;\u001b\\ here\n"),
        )
    }

    @Test fun `erase line and backspace are applied`() {
        assertEquals(listOf("abX", "new"), lines("abc\bX\n", "old text\r\u001b[Knew\n"))
    }

    @Test fun `a utf-8 character split across chunks is decoded whole`() {
        val bytes = "héllo ✓\n".toByteArray()
        val out = mutableListOf<String>()
        val d = AnsiLineDecoder { out += it }
        bytes.forEach { d.feed(byteArrayOf(it)) }
        assertEquals(listOf("héllo ✓"), out)
    }

    @Test fun `an escape sequence split across chunks is still dropped`() {
        assertEquals(listOf("ab"), lines("a\u001b[3", "2mb\n"))
    }

    @Test fun `the unfinished last line is handed on at flush`() {
        assertEquals(listOf("spinner |"), lines("spinner -\rspinner |"))
    }
}
