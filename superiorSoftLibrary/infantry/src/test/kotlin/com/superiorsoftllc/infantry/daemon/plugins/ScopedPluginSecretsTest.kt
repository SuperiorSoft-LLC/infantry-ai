// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.daemon.api.pluginsecrets.SecretValue
import com.superiorsoftllc.infantry.keystore.Keystore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScopedPluginSecretsTest {
    private val value = "tok_SuperSecretValue1234567890"

    private fun store(): Pair<Keystore, DaemonServices> {
        val ks = Keystore(Files.createTempDirectory("pluginsecrets").toFile(), env = { null }, vault = null)
        val services = object : DaemonServices by DaemonServices.NONE {
            override fun keystoreField(name: String, field: String) = ks.field(name, field)
            override fun setKeystoreField(name: String, field: String, value: String) = ks.setField(name, field, value)
            override fun clearKeystoreField(name: String, field: String) = ks.deleteField(name, field)
        }
        return ks to services
    }

    @Test fun writesReadsAndClearsItsOwnField() {
        val (ks, services) = store()
        val a = ScopedPluginSecrets("Infantry_Account", services)
        assertNull(a.get("refresh"))
        a.set("refresh", SecretValue(value))
        assertEquals(value, a.get("refresh")?.reveal())
        assertEquals(value, ks.field("plugin.infantry_account", "refresh"))
        assertTrue(a.clear("refresh"))
        assertNull(a.get("refresh"))
        assertFalse(a.clear("refresh"))
    }

    @Test fun anotherModulesEntryIsUnreachable() {
        val (ks, services) = store()
        val a = ScopedPluginSecrets("mod_a", services)
        val b = ScopedPluginSecrets("mod_b", services)
        a.set("token", SecretValue(value))
        assertNull(b.get("token"))
        assertFalse(b.clear("token"))
        assertEquals(value, a.get("token")?.reveal())
        // A field name cannot carry an entry name or a path.
        for (bad in listOf("plugin.mod_a/token", "../mod_a", "a.b", "", "INFANTRY_X")) {
            assertFailsWith<IllegalArgumentException> { b.get(bad) }
            assertFailsWith<IllegalArgumentException> { b.set(bad, SecretValue("x")) }
            assertFailsWith<IllegalArgumentException> { b.clear(bad) }
        }
        assertEquals(setOf("plugin.mod_a"), ks.names().filter { it.startsWith("plugin.") }.toSet())
    }

    @Test fun valueNeverAppearsInToStringOrErrors() {
        val (_, services) = store()
        val a = ScopedPluginSecrets("mod_a", services)
        val v = SecretValue(value)
        a.set("token", v)
        assertFalse(v.toString().contains(value))
        assertFalse(a.get("token").toString().contains(value))
        assertFalse(a.toString().contains(value))
        val e = assertFailsWith<IllegalArgumentException> { a.set("bad field", v) }
        assertFalse((e.message ?: "").contains(value))
    }
}
