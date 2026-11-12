// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.run.daemon

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SECRET-RENDER-1: placeholder syntax, the file render, and the rule that a value reaches ONLY the output file. */
class SecretRenderTest {
    private val dir: File = Files.createTempDirectory("infantry-secret-render").toFile()
    private val fake = "FAKE-VALUE-" + "9f3a1c77d0e24b"
    private val secrets = mapOf("commandcode" to mapOf("value" to fake), "db" to mapOf("USER" to "bob", "PASS" to "p@ss"))
    private var restricted = false
    private val renderer = SecretRenderer({ n, _ -> secrets[n] }, { restricted })

    private fun file(name: String, text: String) = File(dir, name).also { it.writeText(text) }

    @Test fun `an env template is rendered and the template is untouched`() {
        val t = file("a.env", "CMD_API_KEY={{secret:commandcode}}\nUSER={{secret:db#USER}}\n")
        val before = t.readText()
        val r = renderer.render(t, File(dir, "out.env"), null)
        assertTrue(r.ok, r.summary(false))
        assertEquals("CMD_API_KEY=$fake\nUSER=bob\n", File(dir, "out.env").readText())
        assertEquals(before, t.readText())
        assertEquals(listOf("commandcode", "db#USER"), r.filled)
    }

    @Test fun `a jsonc template renders the same way`() {
        val t = file("c.jsonc", "{ // key\n  \"apiKey\": \"{{secret:commandcode}}\" }")
        assertTrue(renderer.render(t, File(dir, "c.out.jsonc"), null).ok)
        assertEquals("{ // key\n  \"apiKey\": \"$fake\" }", File(dir, "c.out.jsonc").readText())
    }

    @Test fun `the escape stays as written`() {
        val t = file("e.txt", "keep {{!secret:commandcode}} fill {{secret:commandcode}}")
        renderer.render(t, File(dir, "e.out"), null)
        assertEquals("keep {{!secret:commandcode}} fill $fake", File(dir, "e.out").readText())
    }

    @Test fun `a missing secret fails the whole render and writes nothing`() {
        val t = file("m.env", "A={{secret:commandcode}}\nB={{secret:nope}}\n")
        val out = File(dir, "m.out")
        val r = renderer.render(t, out, null)
        assertFalse(r.ok)
        assertEquals(listOf("nope"), r.missing)
        assertFalse(out.exists())
        assertFalse(r.summary(false).contains(fake))
    }

    @Test fun `allow-missing leaves the placeholder in`() {
        val t = file("am.env", "A={{secret:commandcode}}\nB={{secret:nope}}\n")
        val r = renderer.render(t, File(dir, "am.out"), null, allowMissing = true)
        assertTrue(r.ok)
        assertEquals("A=$fake\nB={{secret:nope}}\n", File(dir, "am.out").readText())
    }

    @Test fun `check reports names and writes nothing`() {
        val t = file("k.env", "A={{secret:commandcode}} B={{secret:nope}}")
        val r = renderer.render(t, File(dir, "k.out"), null, check = true)
        assertTrue(r.ok)
        assertEquals(listOf("commandcode"), r.filled)
        assertEquals(listOf("nope"), r.missing)
        assertFalse(File(dir, "k.out").exists())
    }

    @Test fun `a binary file is refused`() {
        val nul = File(dir, "b.bin").also { it.writeBytes(byteArrayOf(1, 0, 2)) }
        val bad = File(dir, "b2.bin").also { it.writeBytes(byteArrayOf(0xC3.toByte(), 0x28)) }
        assertTrue(renderer.render(nul, File(dir, "x1"), null).error!!.contains("not a text file"))
        assertTrue(renderer.render(bad, File(dir, "x2"), null).error!!.contains("not a text file"))
        assertFalse(File(dir, "x1").exists())
    }

    @Test fun `the output may not be the template`() {
        val t = file("same.env", "A={{secret:commandcode}}")
        assertTrue(renderer.render(t, t, null).error!!.contains("template itself"))
        assertEquals("A={{secret:commandcode}}", t.readText())
    }

    @Test fun `a restricted daemon refuses`() {
        restricted = true
        val r = renderer.render(file("r.env", "A={{secret:commandcode}}"), File(dir, "r.out"), null)
        assertTrue(r.error!!.contains("restricted"))
        assertFalse(File(dir, "r.out").exists())
    }

    @Test fun `the output file is owner only`() {
        val out = File(dir, "perm.env")
        renderer.render(file("p.env", "A={{secret:commandcode}}"), out, null)
        val posix = runCatching { Files.getPosixFilePermissions(out.toPath()) }.getOrNull()
        if (posix != null) {
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), posix)
        } else {
            val view = Files.getFileAttributeView(out.toPath(), AclFileAttributeView::class.java)
            val acl = view.acl
            assertEquals(1, acl.size)
            assertEquals(view.owner, acl.single().principal())
            assertTrue(AclEntryPermission.READ_DATA in acl.single().permissions())
        }
    }

    // The acceptance test: the fake value is in the output file and in NO result a caller sees.
    @Test fun `the value appears only in the output file`() {
        val t = file("v.env", "K={{secret:commandcode}}")
        val out = File(dir, "v.out")
        val store = CommandStore(File(dir, "commands.json"), dir)
        val tools = RunCommandTools(
            store,
            RunManager({ store.registry }, com.superiorsoftllc.infantry.runner.ScriptedRunner(com.superiorsoftllc.infantry.runner.StubScript(), 1_000), {}),
            {}, {}, { mapOf("commandcode" to "app") }, renderer, { dir },
        ).all().associateBy { it.name }
        fun call(vararg a: Pair<String, JsonPrimitive>) = tools.getValue("infantry_secret_render").call(object : ToolCall {
            override val arguments = JsonObject(a.toMap())
            override val caller: Caller get() = error("unused")
            override val clientProjectId: String? = null
            override fun request(type: String, data: String, sessionId: String?, timeoutMs: Long) = error("unused")
        })
        val ok = call("template" to JsonPrimitive("v.env"), "out" to JsonPrimitive("v.out"))
        val miss = call("template" to JsonPrimitive("v.env"), "out" to JsonPrimitive("v.env"))
        val names = tools.getValue("infantry_run_secrets").call(object : ToolCall {
            override val arguments = JsonObject(emptyMap())
            override val caller: Caller get() = error("unused")
            override val clientProjectId: String? = null
            override fun request(type: String, data: String, sessionId: String?, timeoutMs: Long) = error("unused")
        })
        assertEquals("K=$fake", out.readText())
        listOf(ok.text, miss.text, names.text).forEach { assertFalse(it.contains(fake), it) }
        assertTrue(ok.text.contains("commandcode") && ok.text.contains(out.path))
        assertFalse(ok.isError)
        assertTrue(miss.isError)
        assertNull(names.text.lines().firstOrNull { it.contains(fake) })
    }

    @Test fun `one field secret with a differently named field fills, project wins, multi-field needs FIELD, listed-but-missing says why`() {
        val app = mapOf("commandcode" to mapOf("commandcode_key" to "APPVAL"), "db" to mapOf("USER" to "u", "PASS" to "p"))
        val proj = mapOf("commandcode" to mapOf("commandcode_key" to "PROJVAL"))
        val r = SecretRenderer({ n, p -> (if (p != null) proj[n] else null) ?: app[n] })
        val t = file("n.env", "K={{secret:commandcode}}\nU={{secret:db#USER}}\n")
        assertTrue(r.render(t, File(dir, "n.out"), null).ok)
        assertEquals("K=APPVAL\nU=u\n", File(dir, "n.out").readText())
        assertTrue(r.render(t, File(dir, "n2.out"), "p1").ok)
        assertEquals("K=PROJVAL\nU=u\n", File(dir, "n2.out").readText())
        val bad = r.render(file("b.env", "A={{secret:db}}\nB={{secret:ghost}}"), null, null, check = true)
        val s = bad.summary(true)
        assertTrue(s.contains("db#FIELD") && s.contains("ghost: not found in app scope"), s)
        assertFalse(s.contains("APPVAL"))
    }
}
