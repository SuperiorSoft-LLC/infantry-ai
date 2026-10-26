package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.RunBytesPayload
import com.superiorsoftllc.agenthq.ui.model.RunConsoleFeed
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals

/** RUN-CONSOLE (ticket 67f117b6): a console's byte order, and the `run_bytes` frame as the daemon writes it. */
@OptIn(ExperimentalEncodingApi::class)
class RunConsoleFeedTest {
    private val out = StringBuilder()
    private var reattached = 0
    private val feed = RunConsoleFeed("r", { out.append(it.decodeToString()) }, { reattached++ })

    private fun live(seq: Long, text: String, id: String = "r") = RunBytesPayload(id, seq, Base64.encode(text.encodeToByteArray()))
    private fun replay(seq: Long, text: String) = RunBytesPayload("r", seq, Base64.encode(text.encodeToByteArray()), replay = true)

    @Test fun `live chunks before the replay wait for it, and those inside it are not written twice`() {
        feed.accept(live(3, "C"))
        feed.accept(live(2, "B"))
        assertEquals("", out.toString())
        feed.accept(replay(2, "AB"))
        feed.accept(live(4, "D"))
        assertEquals("ABCD", out.toString())
    }

    @Test fun `a duplicate is dropped and another run's bytes are ignored`() {
        feed.accept(replay(0, ""))
        feed.accept(live(1, "x")); feed.accept(live(1, "x")); feed.accept(live(1, "y", id = "other"))
        assertEquals("x", out.toString())
    }

    @Test fun `a hole asks for a fresh replay, which starts with a terminal reset`() {
        feed.accept(replay(1, "a"))
        feed.accept(live(3, "c"))
        assertEquals(1, reattached)
        assertEquals("a", out.toString())
        feed.accept(replay(3, "abc"))
        assertEquals("a\u001bcabc", out.toString())
        feed.accept(live(4, "d"))
        assertEquals("a\u001bcabcd", out.toString())
    }

    @Test fun `the run_bytes frame the daemon writes decodes to the bytes it carries`() {
        // As the daemon's RunBytes serialises: base64 of raw bytes, including ESC and a split UTF-8 sequence.
        val raw = byteArrayOf(0x1b, '['.code.toByte(), '3'.code.toByte(), '2'.code.toByte(), 'm'.code.toByte(), 0xE2.toByte(), 0x9C.toByte())
        val wire = """{"runId":"r","sequence":1,"data":"${Base64.encode(raw)}","replay":false,"droppedBytes":0}"""
        val p = Json { ignoreUnknownKeys = true }.decodeFromString(RunBytesPayload.serializer(), wire)
        val got = mutableListOf<Byte>()
        RunConsoleFeed("r", { got += it.toList() }, {}).apply { accept(replay(0, "")); accept(p) }
        assertEquals(raw.toList(), got)
    }
}
