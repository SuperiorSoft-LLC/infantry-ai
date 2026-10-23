package com.superiorsoftllc.agenthq.keystore

import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.update.UpdateSettings
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeystoreTest {
    private val secret = "ghp_SuperSecretValue1234567890WXYZ"

    private class FakeVault : OsVault {
        override val label = "fake"
        var stored: String? = null
        override fun fetch() = stored
        override fun store(value: String): Boolean { stored = value; return true }
    }

    private fun dir(): File = Files.createTempDirectory("keystore-test").toFile()

    private fun everyFileText(d: File): String = d.walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }

    @Test
    fun `round trips and never writes the value in clear`() {
        val d = dir()
        val ks = Keystore(d, env = { null }, vault = null)
        ks.set(Keystore.UPDATE_TOKEN, "  $secret  ")
        assertEquals(secret, ks.get(Keystore.UPDATE_TOKEN))
        assertFalse(everyFileText(d).contains(secret), "the value must not sit in any file of the state dir")
        assertFalse(everyFileText(d).contains("SuperSecret"))
        // A second instance (a restart) reads it back.
        assertEquals(secret, Keystore(d, env = { null }, vault = null).get(Keystore.UPDATE_TOKEN))
    }

    @Test
    fun `status shows set and a masked tail but not the value`() {
        val ks = Keystore(dir(), env = { null }, vault = null)
        assertFalse(ks.status(listOf(Keystore.UPDATE_TOKEN)).single().set)
        ks.set(Keystore.UPDATE_TOKEN, secret)
        val info = ks.status(listOf(Keystore.UPDATE_TOKEN)).single()
        assertTrue(info.set)
        assertEquals("••••WXYZ", info.tail)
        assertFalse(info.toString().contains(secret))
        ks.set(Keystore.UPDATE_TOKEN, "short")
        assertEquals("••••", ks.status(listOf(Keystore.UPDATE_TOKEN)).single().tail)
    }

    @Test
    fun `clear removes it`() {
        val ks = Keystore(dir(), env = { null }, vault = null)
        ks.set(Keystore.UPDATE_TOKEN, secret)
        assertTrue(ks.clear(Keystore.UPDATE_TOKEN))
        assertNull(ks.get(Keystore.UPDATE_TOKEN))
        assertFalse(ks.clear(Keystore.UPDATE_TOKEN))
    }

    @Test
    fun `an entry copied under another name does not open`() {
        val d = dir()
        val ks = Keystore(d, env = { null }, vault = null)
        ks.set("one", secret)
        val file = File(d, Keystore.FILE_NAME)
        file.writeText(file.readText().replace("\"one\"", "\"two\""))
        assertNull(Keystore(d, env = { null }, vault = null).get("two"), "the name is authenticated data")
    }

    @Test
    fun `protection order is external key then OS vault then key file`() {
        val external = Keystore(dir(), env = { if (it == Keystore.KEY_ENV) "mounted-key" else null }, vault = FakeVault())
        assertEquals(Protection.EXTERNAL_KEY, external.protection())

        val vault = FakeVault()
        val d = dir()
        val os = Keystore(d, env = { null }, vault = vault)
        os.set("a", secret)
        assertEquals(Protection.OS, os.protection())
        assertNotNull(vault.stored)
        assertFalse(File(d, Keystore.KEY_FILE_NAME).exists(), "with an OS vault no key file is written")

        val file = Keystore(dir(), env = { null }, vault = null)
        assertEquals(Protection.KEY_FILE, file.protection())
    }

    @Test
    fun `the external key must be the same to read, and a different key does not read`() {
        val d = dir()
        Keystore(d, env = { if (it == Keystore.KEY_ENV) "k1" else null }, vault = null).set("a", secret)
        assertEquals(secret, Keystore(d, env = { if (it == Keystore.KEY_ENV) "k1" else null }, vault = null).get("a"))
        assertNull(Keystore(d, env = { if (it == Keystore.KEY_ENV) "k2" else null }, vault = null).get("a"))
    }

    @Test
    fun `rejects bad names and empty values without echoing them`() {
        val ks = Keystore(dir(), env = { null }, vault = null)
        assertFailsWithMessageWithout("../evil") { ks.set("../evil", secret) }
        assertFailsWithMessageWithout(secret) { ks.set("ok", "   ") }
    }

    private fun assertFailsWithMessageWithout(text: String, block: () -> Unit) {
        val e = runCatching(block).exceptionOrNull() ?: error("expected a refusal")
        assertFalse((e.message ?: "").contains(text))
    }

    @Test
    fun `wire frames never carry the value back, and a refusal never echoes it`() {
        val ks = Keystore(dir(), env = { null }, vault = null)
        val pushed = mutableListOf<Envelope>()
        val h = KeystoreHandler(ks, env = { null }, push = { pushed += it })
        val set = h.answer(Envelope(Envelope.SECRET_SET, """{"name":"update-token","value":"$secret"}"""))!!
        assertEquals(Envelope.SECRET_STATE, set.type)
        assertFalse(set.data.contains(secret))
        assertTrue(set.data.contains(""""set":true"""))
        assertEquals(1, pushed.size)
        assertFalse(pushed.single().data.contains(secret))
        val status = h.answer(Envelope(Envelope.SECRET_STATUS, "{}"))!!
        assertFalse(status.data.contains(secret))
        assertEquals(1, pushed.size, "a status read is not a change")

        val unknown = h.answer(Envelope(Envelope.SECRET_SET, """{"name":"nope","value":"$secret"}"""))!!
        assertEquals(Envelope.ERROR, unknown.type)
        assertFalse(unknown.data.contains(secret))
        val malformed = h.answer(Envelope(Envelope.SECRET_SET, """{"name":"update-token","value":"$secret","extra":1}"""))!!
        assertEquals(Envelope.ERROR, malformed.type)
        assertFalse(malformed.data.contains(secret), malformed.data)
        assertFalse(SecretSet("update-token", secret).toString().contains(secret))

        val cleared = h.answer(Envelope(Envelope.SECRET_CLEAR, """{"name":"update-token"}"""))!!
        assertTrue(cleared.data.contains(""""set":false"""))
    }

    @Test
    fun `import copies the environment variable and keeps the override flag honest`() {
        val ks = Keystore(dir(), env = { null }, vault = null)
        val h = KeystoreHandler(ks, env = { if (it == "AGENTHQ_UPDATE_TOKEN") secret else null })
        assertTrue(h.state().secrets.single().importable)
        val imported = h.answer(Envelope(Envelope.SECRET_IMPORT, """{"name":"update-token"}"""))!!
        assertFalse(imported.data.contains(secret))
        assertEquals(secret, ks.get(Keystore.UPDATE_TOKEN))
        val row = h.state().secrets.single()
        assertFalse(row.importable)
        assertTrue(row.overriddenByEnv)
        val nothing = KeystoreHandler(ks, env = { null }).answer(Envelope(Envelope.SECRET_IMPORT, """{"name":"update-token"}"""))!!
        assertEquals(Envelope.ERROR, nothing.type)
    }

    @Test
    fun `update token comes from env first then the keystore then the git credential store`() {
        fun token(env: String?, keystore: String?, git: String?): String? {
            val settings = UpdateSettings.resolve(
                installed = true, version = "1.0.0", stateDir = null,
                setting = { if (it == UpdateSettings.REPO) "o/r" else null },
                env = { if (it == UpdateSettings.TOKEN_ENV) env else null },
                keystore = { keystore }, credentialStore = { git },
            ) as UpdateSettings.Enabled
            return settings.config.token
        }
        assertEquals("E", token("E", "K", "G"))
        assertEquals("K", token(null, "K", "G"))
        assertEquals("G", token(null, null, "G"))
        assertNull(token(null, null, null))
    }
}
