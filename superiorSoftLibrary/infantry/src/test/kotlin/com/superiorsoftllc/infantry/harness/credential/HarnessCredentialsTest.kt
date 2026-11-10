// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.harness.credential

import com.superiorsoftllc.infantry.harness.descriptor.HarnessDescriptor
import com.superiorsoftllc.infantry.harness.descriptor.InvalidDescriptor
import com.superiorsoftllc.infantry.keystore.KeystoreHandler
import com.superiorsoftllc.infantry.keystore.KnownSecret
import com.superiorsoftllc.infantry.keystore.Keystore
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.protocol.Envelope
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class HarnessCredentialsTest {
    private val key = "cc-SuperSecretKey1234567890WXYZ"
    private val decl = HarnessCredential("commandcode", "COMMANDCODE_API_KEY", "Command Code", "Get one at the vendor's site")

    private fun store(declared: List<HarnessCredential> = listOf(decl)): Pair<Keystore, HarnessCredentials> {
        val d = Files.createTempDirectory("hc-test").toFile()
        val ks = Keystore(d, env = { null }, vault = null)
        return ks to HarnessCredentials(ks) { declared }
    }

    @Test
    fun `unset then set, env carries the key, state shows only a masked tail`() {
        val (ks, c) = store()
        assertEquals(emptyMap(), c.env("commandcode"))
        assertEquals(listOf(decl), c.missing())
        c.set("commandcode", " $key ")
        assertEquals(mapOf("COMMANDCODE_API_KEY" to key), c.env("commandcode"))
        val s = c.states().single()
        assertTrue(s.set)
        assertFalse(s.tail.contains("SuperSecret"))
        assertEquals(emptyList(), c.missing())
        assertEquals(key, ks.field("harness.commandcode.key", "value"))
        assertTrue(c.clear("commandcode"))
        assertEquals(emptyMap(), c.env("commandcode"))
    }

    @Test
    fun `a harness that declares nothing cannot be given a key`() {
        val (_, c) = store()
        assertFailsWith<IllegalArgumentException> { c.set("claude", key) }
        assertEquals(emptyMap(), c.env("claude"))
    }

    @Test
    fun `env names that would hijack the daemon or system are refused`() {
        assertNull(HarnessCredential.envRefusal("OPENCODE_API_KEY"))
        for (bad in listOf("PATH", "INFANTRY_TOKEN", "AGENTHQ_TOKEN", "lower", "NODE_OPTIONS", "A B")) assertNotNull(HarnessCredential.envRefusal(bad), bad)
    }

    @Test
    fun `the secret frames set and report it as a known secret, no value comes back, the key never goes to a run`() {
        val (ks, c) = store()
        val pushed = mutableListOf<Envelope>()
        val h = KeystoreHandler(ks, extraKnown = { c.declared().map { KnownSecret(it.secretName, "${it.label} API key") } }, push = { pushed += it })
        val before = h.state().secrets.single { it.name == "harness.commandcode.key" }
        assertFalse(before.set)
        val reply = h.answer(Envelope(Envelope.SECRET_SET, """{"name":"harness.commandcode.key","value":"$key"}"""))!!
        assertEquals(Envelope.SECRET_STATE, reply.type)
        assertFalse(reply.data.contains(key), "the value never comes back")
        assertTrue(h.state().secrets.single { it.name == "harness.commandcode.key" }.set)
        assertEquals(mapOf("COMMANDCODE_API_KEY" to key), c.env("commandcode"))
        assertFailsWith<IllegalArgumentException> { KeystoreHandler.runSecrets({ ks.fields(it) })("harness.commandcode.key") }
    }

    @Test
    fun `a descriptor declares its credential`() {
        val json = Json.parseToJsonElement("""{"id":"commandcode","command":"cmd","credential":{"env":"COMMANDCODE_API_KEY","label":"Command Code","help":"h"}}""")
        val d = HarnessDescriptor.parse(json)
        assertEquals(HarnessCredential("commandcode", "COMMANDCODE_API_KEY", "Command Code", "h"), d.credential)
        assertNull(HarnessDescriptor.parse(Json.parseToJsonElement("""{"id":"x","command":"c"}""")).credential)
        assertFailsWith<InvalidDescriptor> { HarnessDescriptor.parse(Json.parseToJsonElement("""{"id":"x","command":"c","credential":{"env":"PATH"}}""")) }
    }

    @Test
    fun `module json declares its credential`() {
        val m = Json { ignoreUnknownKeys = true }.decodeFromString(ModuleManifest.serializer(),
            """{"name":"Infantry_X","version":"1.0.0","apiVersion":2,"harness":{"ids":["x"],"credential":{"env":"X_API_KEY","label":"X","help":"h"}}}""")
        assertEquals("X_API_KEY", m.harness?.credential?.env)
    }
}
