// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.keystore

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** HARNESS-GAP G18 (ticket 5a69ec0f): the mask every session output passes through, and the keystore's value index behind it. */
class SecretMaskTest {
    private val m = SecretMask.MASK

    @Test
    fun `masks every known value, longest first, and leaves short values alone`() {
        val mask = SecretMask { listOf("abcd1234", "abcd1234XYZ", "yes") }
        assertEquals("key=$m and $m, yes", mask.mask("key=abcd1234XYZ and abcd1234, yes"))
        assertEquals("nothing here", mask.mask("nothing here"))
    }

    @Test
    fun `masks the JSON-escaped form a stream-json line carries`() {
        val value = "pa\"ss\\word"
        val mask = SecretMask { listOf(value) }
        assertEquals("""{"text":"$m"}""", mask.mask("""{"text":"pa\"ss\\word"}"""))
    }

    @Test
    fun `no values or a failing source masks nothing`() {
        assertFalse(SecretMask.NONE.active)
        assertEquals("abc secret", SecretMask.NONE.mask("abc secret"))
        assertEquals("abcd1234", SecretMask { error("keystore unreadable") }.mask("abcd1234"))
    }

    @Test
    fun `a stream masks a value split across chunks and holds back only a possible prefix`() {
        val st = SecretMask { listOf("sk-abcd1234") }.stream()
        assertEquals("the key is ", st.chunk("the key is sk-ab"))
        assertEquals("$m done", st.chunk("cd1234 done"))
        assertEquals("plain", st.chunk("plain"), "ordinary output is never delayed")
        assertEquals("tail ", st.chunk("tail sk-"))
        assertEquals("sk-", st.flush(), "the end of the stream releases what was held")
    }

    @Test
    fun `follows the keystore - a new value is masked, a cleared one no longer is`() {
        val ks = Keystore(Files.createTempDirectory("mask-test").toFile(), env = { null }, vault = null)
        val mask = SecretMask(ks::allValues)
        assertFalse(mask.active, "an empty keystore masks nothing")
        ks.setField("deploy", "TOKEN", "app-scope-value-1")
        ks.setField("deploy", "TOKEN", "project-value-22", project = "project-1")
        assertEquals("$m / $m", mask.mask("app-scope-value-1 / project-value-22"))
        ks.clear("deploy", project = "project-1")
        assertEquals("$m / project-value-22", mask.mask("app-scope-value-1 / project-value-22"))
    }

    @Test
    fun `allValues returns the same set until the keystore changes`() {
        val ks = Keystore(Files.createTempDirectory("mask-test").toFile(), env = { null }, vault = null)
        ks.setField("a", "F", "value-one")
        val first = ks.allValues()
        assertSame(first, ks.allValues(), "unchanged keystore = same object, so the mask does not rebuild")
        ks.setField("b", "F", "value-two")
        assertTrue(ks.allValues().containsAll(listOf("value-one", "value-two")))
    }
}
