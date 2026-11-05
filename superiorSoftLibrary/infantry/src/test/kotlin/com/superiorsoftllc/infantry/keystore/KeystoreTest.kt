// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.keystore

import com.superiorsoftllc.infantry.protocol.Envelope
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
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
        val h = KeystoreHandler(ks, env = { if (it == "INFANTRY_UPDATE_TOKEN") secret else null })
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

    // SECRETS-2 (ticket e5d1b57f): key/value secrets.

    @Test
    fun `a secret holds named fields, each sealed, never in clear on disk`() {
        val d = dir()
        val ks = Keystore(d, env = { null }, vault = null)
        ks.setField("android-signing", "storePassword", secret)
        ks.setField("android-signing", "keyAlias", "release-key")
        assertEquals(mapOf("keyAlias" to "release-key", "storePassword" to secret), ks.fields("android-signing"))
        assertEquals(secret, Keystore(d, env = { null }, vault = null).field("android-signing", "storePassword"))
        assertFalse(everyFileText(d).contains(secret))
        assertFalse(everyFileText(d).contains("release-key"))
        assertEquals(listOf("android-signing"), ks.names())
        val described = ks.describe("android-signing")
        assertEquals(listOf("keyAlias", "storePassword"), described.map { it.name })
        assertEquals("••••WXYZ", described.last().tail)
        assertFalse(described.toString().contains(secret))
    }

    @Test
    fun `a field copied under another field or secret does not open`() {
        val d = dir()
        Keystore(d, env = { null }, vault = null).setField("s", "A", secret)
        val file = File(d, Keystore.FILE_NAME)
        val text = file.readText()
        file.writeText(text.replace("\"A\"", "\"B\""))
        assertNull(Keystore(d, env = { null }, vault = null).field("s", "B"), "the field is authenticated data")
        file.writeText(text.replace("\"s\"", "\"t\""))
        assertNull(Keystore(d, env = { null }, vault = null).field("t", "A"), "the secret name is authenticated data")
    }

    @Test
    fun `deleting the last field removes the secret, and clear removes every field`() {
        val ks = Keystore(dir(), env = { null }, vault = null)
        ks.setField("env-set", "A", "one-value")
        ks.setField("env-set", "B", "two-value")
        assertTrue(ks.deleteField("env-set", "A"))
        assertFalse(ks.deleteField("env-set", "A"))
        assertEquals(listOf("B"), ks.fields("env-set")!!.keys.toList())
        assertTrue(ks.deleteField("env-set", "B"))
        assertNull(ks.fields("env-set"))
        assertEquals(emptyList(), ks.names())
        ks.setField("env-set", "A", "one-value")
        assertTrue(ks.clear("env-set"))
        assertNull(ks.fields("env-set"))
    }

    @Test
    fun `a SECRETS-1 entry reads as one field named value and is re-sealed as v2 on the next write`() {
        val d = dir()
        val ks = Keystore(d, env = { null }, vault = null)
        ks.set(Keystore.UPDATE_TOKEN, secret)
        // Turn the file back into the v1 shape: one sealed value per name, the name alone as the AAD.
        val v1Dir = dir()
        File(d, Keystore.KEY_FILE_NAME).copyTo(File(v1Dir, Keystore.KEY_FILE_NAME))
        val nonce = ByteArray(12) { it.toByte() }
        val key = java.util.Base64.getDecoder().decode(File(d, Keystore.KEY_FILE_NAME).readText().trim())
        val ct = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, nonce))
            updateAAD("legacy".toByteArray())
        }.doFinal(secret.toByteArray())
        val b64 = java.util.Base64.getEncoder()
        File(v1Dir, Keystore.FILE_NAME).writeText("""{"v":1,"entries":{"legacy":{"nonce":"${b64.encodeToString(nonce)}","ct":"${b64.encodeToString(ct)}"}}}""")

        val old = Keystore(v1Dir, env = { null }, vault = null)
        assertEquals(secret, old.get("legacy"))
        assertEquals(mapOf(Keystore.VALUE_FIELD to secret), old.fields("legacy"))
        old.setField("legacy", "extra", "another-one")
        val text = File(v1Dir, Keystore.FILE_NAME).readText()
        assertTrue(text.contains("\"secrets\"") && text.contains("\"v\":2"), text)
        assertFalse(text.contains("\"entries\":{\"legacy\""), "the v1 entry moved into v2")
        assertEquals(mapOf("extra" to "another-one", Keystore.VALUE_FIELD to secret), Keystore(v1Dir, env = { null }, vault = null).fields("legacy"))
    }

    @Test
    fun `bad field names are refused without echoing the value`() {
        val ks = Keystore(dir(), env = { null }, vault = null)
        assertFailsWithMessageWithout(secret) { ks.setField("ok", "1bad", secret) }
        assertFailsWithMessageWithout(secret) { ks.setField("ok", "has space", secret) }
        assertFailsWithMessageWithout(secret) { ks.setField("ok", "fine", "  ") }
    }

    @Test
    fun `field frames edit user secrets, never return a value, and leave built-ins alone`() {
        val ks = Keystore(dir(), env = { null }, vault = null)
        val pushed = mutableListOf<Envelope>()
        val h = KeystoreHandler(ks, env = { null }, push = { pushed += it })
        val set = h.answer(Envelope(Envelope.SECRET_FIELD_SET, """{"name":"android-signing","field":"storePassword","value":"$secret"}"""))!!
        assertEquals(Envelope.SECRET_STATE, set.type, set.data)
        assertFalse(set.data.contains(secret))
        assertTrue(set.data.contains(""""entries":[{"name":"android-signing","fields":[{"name":"storePassword","tail":"••••WXYZ"}]}]"""), set.data)
        assertEquals(1, pushed.size)

        val builtIn = h.answer(Envelope(Envelope.SECRET_FIELD_SET, """{"name":"update-token","field":"x","value":"$secret"}"""))!!
        assertEquals(Envelope.ERROR, builtIn.type)
        assertFalse(builtIn.data.contains(secret))
        val malformed = h.answer(Envelope(Envelope.SECRET_FIELD_SET, """{"name":"a","field":"b","value":"$secret","x":1}"""))!!
        assertEquals(Envelope.ERROR, malformed.type)
        assertFalse(malformed.data.contains(secret))
        assertFalse(SecretFieldSet("a", "b", secret).toString().contains(secret))

        h.answer(Envelope(Envelope.SECRET_FIELD_SET, """{"name":"android-signing","field":"keyAlias","value":"rel"}"""))
        val del = h.answer(Envelope(Envelope.SECRET_FIELD_DELETE, """{"name":"android-signing","field":"keyAlias"}"""))!!
        assertTrue(del.data.contains("storePassword") && !del.data.contains("keyAlias"), del.data)
        val gone = h.answer(Envelope(Envelope.SECRET_DELETE, """{"name":"android-signing"}"""))!!
        assertTrue(gone.data.contains(""""entries":[]"""), gone.data)
        assertEquals(Envelope.ERROR, h.answer(Envelope(Envelope.SECRET_DELETE, """{"name":"android-signing"}"""))!!.type)
    }

    @Test
    fun `a restricted policy refuses every change and says so in the state`() {
        val ks = Keystore(dir(), env = { null }, vault = null)
        ks.setField("deploy", "TOKEN", secret)
        val h = KeystoreHandler(ks, env = { null }, restricted = { true })
        for (frame in listOf(
            Envelope(Envelope.SECRET_FIELD_SET, """{"name":"deploy","field":"TOKEN","value":"x-y-z-w"}"""),
            Envelope(Envelope.SECRET_FIELD_DELETE, """{"name":"deploy","field":"TOKEN"}"""),
            Envelope(Envelope.SECRET_DELETE, """{"name":"deploy"}"""),
            Envelope(Envelope.SECRET_SET, """{"name":"update-token","value":"$secret"}"""),
        )) {
            val reply = h.answer(frame)!!
            assertEquals(Envelope.ERROR, reply.type, frame.type)
            assertTrue(reply.data.contains("restricted policy"))
        }
        assertEquals(secret, ks.field("deploy", "TOKEN"))
        val status = h.answer(Envelope(Envelope.SECRET_STATUS, "{}"))!!
        assertTrue(status.data.contains(""""locked":true"""))
        assertFalse(status.data.contains(secret))
    }

    @Test
    fun `plugin_secret_get answers the field of plugin dot module, never pushed`() {
        val ks = Keystore(dir(), env = { null }, vault = null)
        val pushed = mutableListOf<Envelope>()
        val handler = KeystoreHandler(ks, push = { pushed += it })
        ks.setField("plugin.acme_echo", "apiKey", secret)
        val reply = handler.answer(Envelope(Envelope.PLUGIN_SECRET_GET, """{"module":"Acme_Echo","field":"apiKey"}"""))!!
        assertEquals(Envelope.PLUGIN_SECRET, reply.type)
        assertTrue(reply.data.contains(secret))
        assertTrue(pushed.isEmpty(), "a plugin secret is a direct answer only")
    }

    // SECRETS-2b: the project scope.

    @Test
    fun `a project secret wins inside its project, the app secret elsewhere, and both stay sealed`() {
        val d = dir()
        val ks = Keystore(d, env = { null }, vault = null)
        ks.setField("deploy", "TOKEN", "app-value-1234")
        ks.setField("deploy", "TOKEN", "proj-value-5678", project = "p1")
        assertEquals("proj-value-5678", ks.resolve("deploy", "p1")?.get("TOKEN"))
        assertEquals("app-value-1234", ks.resolve("deploy", "p2")?.get("TOKEN"), "another project falls back to the app")
        assertEquals("app-value-1234", ks.resolve("deploy", null)?.get("TOKEN"))
        assertEquals(Keystore.SCOPE_PROJECT, ks.scopeOf("deploy", "p1"))
        assertEquals(Keystore.SCOPE_APP, ks.scopeOf("deploy", "p2"))
        assertNull(ks.scopeOf("nothing", "p1"))
        val text = everyFileText(d)
        assertFalse(text.contains("app-value-1234") || text.contains("proj-value-5678"), "no value in clear on disk")
        assertEquals(listOf("deploy"), ks.names(), "app names never list a project's key")
        assertEquals(listOf("deploy"), ks.names("p1"))
        assertEquals(listOf("p1"), ks.projects())
    }

    @Test
    fun `existing secrets stay in the app scope`() {
        val d = dir()
        Keystore(d, env = { null }, vault = null).setField("android-signing", "storePassword", secret)
        val ks = Keystore(d, env = { null }, vault = null)
        assertEquals(Keystore.SCOPE_APP, ks.scopeOf("android-signing", "any-project"))
        assertTrue(ks.names("any-project").isEmpty())
    }

    @Test
    fun `move re-seals every field under the other scope and refuses a clash or a missing source`() {
        val d = dir()
        val ks = Keystore(d, env = { null }, vault = null)
        ks.setField("deploy", "TOKEN", secret)
        ks.setField("deploy", "USER", "bot-user-42")
        ks.move("deploy", null, "p1")
        assertNull(ks.fields("deploy"), "gone from the app scope")
        assertEquals(mapOf("TOKEN" to secret, "USER" to "bot-user-42"), Keystore(d, env = { null }, vault = null).fields("deploy", "p1"))
        assertFailsWith<IllegalArgumentException> { ks.move("missing", null, "p1") }
        ks.setField("deploy", "TOKEN", "other-app-9999")
        assertFailsWith<IllegalArgumentException> { ks.move("deploy", "p1", null) }
        ks.clear("deploy")
        ks.move("deploy", "p1", null)
        assertEquals(secret, ks.field("deploy", "TOKEN"))
        assertTrue(ks.projects().isEmpty())
    }

    @Test
    fun `a bad project id is refused`() {
        val ks = Keystore(dir(), env = { null }, vault = null)
        assertFailsWith<IllegalArgumentException> { ks.setField("deploy", "TOKEN", secret, project = "../x") }
        assertFailsWith<IllegalArgumentException> { ks.setField("deploy", "TOKEN", secret, project = "a/b") }
    }

    @Test
    fun `the handler edits and moves project secrets, lists their scope, and a run resolves project first`() {
        val ks = Keystore(dir(), env = { null }, vault = null)
        val h = KeystoreHandler(ks, env = { null })
        val set = h.answer(Envelope(Envelope.SECRET_FIELD_SET, """{"name":"deploy","field":"TOKEN","value":"$secret","project":"p1"}"""))!!
        assertEquals(Envelope.SECRET_STATE, set.type, set.data)
        assertTrue(set.data.contains(""""project":"p1""""), set.data)
        assertFalse(set.data.contains(secret))
        assertNull(ks.fields("deploy"), "a project set never writes the app scope")
        val run = KeystoreHandler.projectRunSecrets(ks)
        assertEquals(secret, run("deploy", "p1")?.get("TOKEN"))
        assertNull(run("deploy", "p2"))
        assertFailsWith<IllegalArgumentException> { run(Keystore.UPDATE_TOKEN, "p1") }
        val moved = h.answer(Envelope(Envelope.SECRET_MOVE, """{"name":"deploy","from":"p1"}"""))!!
        assertEquals(Envelope.SECRET_STATE, moved.type, moved.data)
        assertEquals(secret, ks.field("deploy", "TOKEN"))
        assertEquals(secret, run("deploy", "p2")?.get("TOKEN"), "now shared by every project")
        assertEquals(Envelope.ERROR, h.answer(Envelope(Envelope.SECRET_MOVE, """{"name":"deploy","from":"p1"}"""))!!.type)
        val del = h.answer(Envelope(Envelope.SECRET_DELETE, """{"name":"deploy"}"""))!!
        assertTrue(del.data.contains(""""entries":[]"""), del.data)
    }
}

