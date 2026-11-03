package com.superiorsoftllc.infantry.cli

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class CliOutputEncodingTest {

    private val message = "daemon not running · start it — now 😀"

    @Test
    fun `output is written as UTF-8 bytes whatever the platform charset`() {
        val bytes = ByteArrayOutputStream()
        val out = encodedStream(bytes) { null }
        out.println(message)
        out.flush()
        assertContentEquals((message + System.lineSeparator()).toByteArray(Charsets.UTF_8), bytes.toByteArray())
        assertEquals(message, bytes.toByteArray().toString(Charsets.UTF_8).trimEnd())
    }

    @Test
    fun `ASCII env var asks for a plain fallback`() {
        val bytes = ByteArrayOutputStream()
        val out = encodedStream(bytes) { if (it == ASCII_OUTPUT_ENV) "1" else null }
        out.print("a·b")
        out.flush()
        assertEquals("a?b", bytes.toByteArray().toString(Charsets.US_ASCII))
    }
}
