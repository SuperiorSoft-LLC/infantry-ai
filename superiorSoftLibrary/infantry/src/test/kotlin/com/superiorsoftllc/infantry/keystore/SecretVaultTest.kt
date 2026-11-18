// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.keystore

import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.settings.LimitDef
import com.superiorsoftllc.infantry.settings.LimitRegistry
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** VAULT-REVEAL-1: reveal / copy / backup behind the [OsUserVerifier] port, with a fake verifier (never a real prompt). */
class SecretVaultTest {
    private val value = "sk-live-VaultRevealTestValue-7f3a9c"
    private val pass = "correct horse battery staple"

    private class FakeVerifier(var answer: Verification) : OsUserVerifier {
        override val label = "Fake Hello"
        var calls = 0
        override fun check() = answer
        override fun verify(reason: String): Verification { calls++; return answer }
    }

    private class FakeOsVault : OsVault {
        override val label = "fake"
        var stored: String? = null
        override fun fetch() = stored
        override fun store(value: String): Boolean { stored = value; return true }
    }

    private val ok = Verification(Verification.Outcome.VERIFIED, "windows-hello")
    private val no = Verification(Verification.Outcome.DENIED, "windows-hello", "Canceled")

    private class Rig(val dir: File, val ks: Keystore, val verifier: FakeVerifier, val vault: SecretVault, val audit: File, val clock: LongArray)

    private fun rig(answer: Verification, limits: Map<LimitDef, Long> = emptyMap(), locked: Boolean = false): Rig {
        val d = Files.createTempDirectory("vault-test").toFile()
        val ks = Keystore(File(d, "state"), env = { null }, vault = FakeOsVault())
        val v = FakeVerifier(answer)
        val audit = File(d, "state/${SecretVault.AUDIT_FILE_NAME}")
        val clock = longArrayOf(1_000_000L)
        // The KDF count's floor (the setting's min) keeps the test quick and still the real derivation.
        val all = mapOf(LimitRegistry.SECRET_BACKUP_KDF_ITERATIONS to LimitRegistry.SECRET_BACKUP_KDF_ITERATIONS.min) + limits
        val vault = SecretVault(ks, v, audit, restricted = { locked }, limit = { all[it] ?: it.default }, clock = { clock[0] }, home = d.absolutePath)
        return Rig(d, ks, v, vault, audit, clock)
    }

    private fun reveal(r: Rig, name: String = "openai", field: String = Keystore.VALUE_FIELD, purpose: String = "reveal", project: String? = null): Envelope =
        r.vault.answer(Envelope(Envelope.SECRET_REVEAL, JsonObject(buildMap {
            put("name", JsonPrimitive(name)); put("field", JsonPrimitive(field)); put("purpose", JsonPrimitive(purpose))
            project?.let { put("project", JsonPrimitive(it)) }
        }).toString()))!!

    private fun export(r: Rig, path: String, passphrase: String = pass): Envelope = r.vault.answer(Envelope(Envelope.SECRET_BACKUP_EXPORT,
        JsonObject(mapOf("path" to JsonPrimitive(path), "passphrase" to JsonPrimitive(passphrase))).toString()))!!

    private fun import(r: Rig, path: String, passphrase: String = pass, answers: Map<String, String> = emptyMap()): Envelope = r.vault.answer(Envelope(Envelope.SECRET_BACKUP_IMPORT,
        JsonObject(mapOf("path" to JsonPrimitive(path), "passphrase" to JsonPrimitive(passphrase),
            "resolutions" to JsonObject(answers.mapValues { JsonPrimitive(it.value) }))).toString()))!!

    private fun obj(e: Envelope) = Json.parseToJsonElement(e.data).jsonObject
    private fun JsonObject.s(key: String) = this[key]!!.jsonPrimitive.content
    private fun JsonObject.list(key: String) = this[key]!!.jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun `a verified reveal returns the value with the window's timers`() {
        val r = rig(ok, mapOf(LimitRegistry.SECRET_REVEAL_SECONDS to 12L, LimitRegistry.SECRET_CLIPBOARD_CLEAR_SECONDS to 9L))
        r.ks.set("openai", value)
        val answer = reveal(r)
        assertEquals(Envelope.SECRET_REVEAL, answer.type, answer.data)
        val o = obj(answer)
        assertEquals(value, o.s("value"))
        assertEquals("windows-hello", o.s("method"))
        assertEquals("12", o.s("showSeconds"))
        assertEquals("9", o.s("clearSeconds"))
    }

    @Test
    fun `a denied verification returns no value`() {
        val r = rig(no)
        r.ks.set("openai", value)
        val answer = reveal(r, purpose = "copy")
        assertEquals(Envelope.ERROR, answer.type)
        assertFalse(value in answer.data)
        assertTrue("not passed" in answer.data, answer.data)
    }

    @Test
    fun `an unavailable verifier refuses and says why`() {
        val r = rig(Verification.unavailable("Reveal and copy need macOS Touch ID / password verification, which is not available yet."))
        r.ks.set("openai", value)
        val answer = reveal(r)
        assertEquals(Envelope.ERROR, answer.type)
        assertTrue("not available yet" in answer.data, answer.data)
    }

    @Test
    fun `a missing field is refused before any prompt`() {
        val r = rig(ok)
        assertEquals(Envelope.ERROR, reveal(r, name = "nope").type)
        assertEquals(0, r.verifier.calls)
    }

    @Test
    fun `project scope reveals the project's value`() {
        val r = rig(ok)
        r.ks.setField("db", "password", value, "proj-1")
        assertEquals(value, obj(reveal(r, name = "db", field = "password", project = "proj-1")).s("value"))
    }

    @Test
    fun `every reveal and copy is audited and the audit never holds the value`() {
        val r = rig(ok)
        r.ks.set("openai", value)
        reveal(r)
        reveal(r, purpose = "copy")
        r.verifier.answer = no
        reveal(r)
        val lines = r.audit.readLines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(3, lines.size)
        assertEquals("reveal", lines[0].s("action"))
        assertEquals("openai", lines[0].s("secret"))
        assertEquals("windows-hello", lines[0].s("method"))
        assertTrue(lines[0].containsKey("at"))
        assertEquals("copy", lines[1].s("action"))
        assertEquals("denied", lines[2].s("outcome"))
        val all = r.dir.walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
        assertFalse(value in all, "no file holds the value in clear")
        assertFalse("VaultRevealTestValue" in all)
    }

    @Test
    fun `cache 0 asks every time and a cache window reuses one verification`() {
        val r0 = rig(ok)
        r0.ks.set("openai", value)
        reveal(r0); reveal(r0)
        assertEquals(2, r0.verifier.calls, "verifyCacheSeconds 0 = every time")

        val r = rig(ok, mapOf(LimitRegistry.SECRET_VERIFY_CACHE_SECONDS to 60L))
        r.ks.set("openai", value)
        reveal(r)
        r.clock[0] += 30_000
        val cached = obj(reveal(r))
        assertEquals(1, r.verifier.calls)
        assertTrue("cached" in cached.s("method"))
        r.clock[0] += 31_000
        reveal(r)
        assertEquals(2, r.verifier.calls, "past the window it asks again")
    }

    @Test
    fun `a restricted policy refuses every vault frame`() {
        val r = rig(ok, locked = true)
        r.ks.set("openai", value)
        assertEquals(Envelope.ERROR, reveal(r).type)
        assertEquals(0, r.verifier.calls)
    }

    @Test
    fun `a malformed frame never echoes its body`() {
        val r = rig(ok)
        val answer = r.vault.answer(Envelope(Envelope.SECRET_BACKUP_EXPORT, """{"path": 1, "passphrase": "$pass", "x": 2}"""))!!
        assertEquals(Envelope.ERROR, answer.type)
        assertFalse(pass in answer.data)
    }

    @Test
    fun `other frames are not the vault's`() {
        assertNull(rig(ok).vault.answer(Envelope(Envelope.SECRET_STATUS, "{}")))
    }

    @Test
    fun `export needs verification, a passphrase and a new file`() {
        val r = rig(no)
        r.ks.set("openai", value)
        val target = File(r.dir, "backup.json")
        assertEquals(Envelope.ERROR, export(r, target.absolutePath).type)
        assertFalse(target.exists())
        r.verifier.answer = ok
        assertTrue("at least" in export(r, target.absolutePath, "short").data)
        val done = export(r, target.absolutePath)
        assertEquals(Envelope.SECRET_BACKUP_EXPORT, done.type, done.data)
        assertEquals("1", obj(done).s("secrets"))
        assertFalse(value in target.readText(), "the backup holds no value in clear")
        assertEquals(Envelope.ERROR, export(r, target.absolutePath).type, "an existing file is never overwritten")
        assertTrue("\"export\"" in r.audit.readText())
    }

    @Test
    fun `export expands the home folder`() {
        val r = rig(ok)
        r.ks.set("openai", value)
        assertEquals(Envelope.SECRET_BACKUP_EXPORT, export(r, "~/b.json").type)
        assertTrue(File(r.dir, "b.json").isFile)
    }

    @Test
    fun `import adds, reports conflicts, then applies the answers`() {
        val src = rig(ok)
        src.ks.set("openai", value)
        src.ks.setField("db", "password", "db-pass-1", "proj-1")
        src.ks.set("same", "same-value")
        src.ks.set("keepme", "backup-keep")
        val file = File(src.dir, "b.json")
        assertEquals(Envelope.SECRET_BACKUP_EXPORT, export(src, file.absolutePath).type)

        val dst = rig(no) // import needs no OS check
        dst.ks.set("openai", "other-value")
        dst.ks.set("same", "same-value")
        dst.ks.set("keepme", "mine")
        val wrong = import(dst, file.absolutePath, "wrong passphrase!!")
        assertEquals(Envelope.ERROR, wrong.type)
        assertTrue("passphrase" in wrong.data)

        val first = obj(import(dst, file.absolutePath))
        assertEquals("false", first.s("applied"))
        assertEquals(setOf("openai", "keepme"), first.list("conflicts").toSet())
        assertNull(dst.ks.fields("db", "proj-1"), "nothing written while a conflict is open")

        val done = obj(import(dst, file.absolutePath, answers = mapOf("openai" to "replace", "keepme" to "keep")))
        assertEquals("true", done.s("applied"))
        assertEquals(listOf("proj-1/db"), done.list("added"))
        assertEquals(listOf("openai"), done.list("replaced"))
        assertEquals(listOf("same"), done.list("same"))
        assertEquals(value, dst.ks.get("openai"))
        assertEquals("mine", dst.ks.get("keepme"))
        assertEquals("db-pass-1", dst.ks.field("db", "password", "proj-1"))
        assertFalse(value in dst.audit.readText())
    }

    @Test
    fun `import refuses a file over the size limit`() {
        val r = rig(ok, mapOf(LimitRegistry.SECRET_BACKUP_MAX_BYTES to 10L))
        val f = File(r.dir, "big.json").apply { writeText("x".repeat(100)) }
        assertTrue("bigger" in import(r, f.absolutePath).data)
    }
}
