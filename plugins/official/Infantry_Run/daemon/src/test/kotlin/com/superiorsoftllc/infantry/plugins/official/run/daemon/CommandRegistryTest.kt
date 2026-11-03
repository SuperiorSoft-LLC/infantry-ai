// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.run.daemon

import com.superiorsoftllc.infantry.protocol.Envelope
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandRegistryTest {
    private val tmp: File = Files.createTempDirectory("infantry-commands-test").toFile()
    private val repoDir: File = File(tmp, "repo").also { File(it, "sub").mkdirs() }
    // The escape targets must EXIST, or a nonexistent path is refused for the wrong reason and the test is vacuous.
    private val outsideDir: File = File(tmp, "outside").also { it.mkdirs() }
    private val secret = "SECRET-ARGV-TOKEN"
    private val bs = 92.toChar().toString() // a backslash, kept out of string literals on purpose

    /** One commands.json entry. A backslash in [cwd] is written as a JSON-escaped pair. */
    private fun entry(id: String = "build", cwd: String? = "sub", argv: String = """["cmd.exe","/c","$secret"]"""): String {
        val cwdJson = cwd?.let { "\"" + it.replace(bs, bs + bs) + "\"" } ?: "null"
        return """{"id":"$id","name":"Build $id","argv":$argv,"cwd":$cwdJson,"graceMillis":2000}"""
    }

    private fun parse(vararg entries: String) = CommandRegistry.parse("[${entries.joinToString(",")}]", repoDir)

    // RUN-CMD-1 (contract 1.94.0, owner decision): the client list carries the argv so the editor can show it.
    // It must still never reach a log line, which is what toString guards.
    @Test fun `a valid file loads and the client list carries the argv but toString never does`() {
        val reg = parse(entry("a"), entry("b", cwd = null))
        assertNull(reg.refusal)
        assertEquals(listOf("a", "b"), reg.specs().map { it.id })
        assertEquals(listOf("sub", null), reg.specs().map { it.cwd })
        assertEquals(listOf("cmd.exe", "/c", secret), reg.specs().first().argv)
        assertEquals(listOf(2000L, 2000L), reg.specs().map { it.graceMillis })
        assertTrue(reg.specsJson().contains(secret), reg.specsJson())
        assertEquals(listOf("cmd.exe", "/c", secret), reg.find("a")!!.argv)
        for (s in listOf(reg.toString(), reg.specs().toString(), reg.commands.toString())) assertFalse(s.contains(secret), s)
    }

    @Test fun `cwd escapes refuse the whole file`() {
        val ok = entry("ok")
        val escapes = listOf(
            "../outside", "sub/../../outside", "..", "..${bs}outside", "/etc", "C:${bs}Windows", "C:/Windows", "$bs$bs" + "host${bs}share",
        )
        for (bad in escapes) {
            val reg = parse(ok, entry("bad", cwd = bad))
            assertTrue(reg.commands.isEmpty(), "cwd '$bad' must refuse the whole file, the good entry included")
            assertNotNull(reg.refusal, "cwd '$bad'")
        }
    }

    @Test fun `a symlink that points out of the repo is refused by canonicalisation`() {
        val outside = outsideDir
        val link = File(repoDir, "link")
        val made = runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess ||
            // Windows without symlink privilege: a directory junction needs none.
            runCatching {
                ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.absolutePath, outside.absolutePath)
                    .redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor() == 0
            }.getOrDefault(false)
        assertTrue(made, "could not create a symlink or junction to test canonicalisation")
        val reg = parse(entry("x", cwd = "link"))
        assertTrue(reg.commands.isEmpty())
        assertNotNull(reg.refusal)
    }

    @Test fun `empty argv, blank id and duplicate id each refuse the file`() {
        assertNotNull(parse(entry("a", argv = "[]")).refusal)
        assertNotNull(parse(entry("  ")).refusal)
        assertNotNull(parse(entry("")).refusal)
        val dup = parse(entry("a"), entry("a"))
        assertNotNull(dup.refusal)
        assertTrue(dup.commands.isEmpty())
    }

    @Test fun `a refusal reason never contains an argv element`() {
        val reasons = listOf(
            parse(entry("a", argv = """["$secret",""]""")).refusal,
            parse(entry("a"), entry("a")).refusal,
            parse(entry("a", cwd = "../x")).refusal,
            CommandRegistry.parse("""[{"id":"a","argv":["$secret""", repoDir).refusal,
            CommandRegistry.parse("""[{"id":"a","argv":"$secret"}]""", repoDir).refusal,
        )
        reasons.forEach { assertNotNull(it); assertFalse(it.contains(secret), it) }
    }

    @Test fun `missing file means no commands and no refusal`() {
        val reg = CommandRegistry.load(File(tmp, "nope.json"), repoDir)
        assertTrue(reg.commands.isEmpty())
        assertNull(reg.refusal)
    }
}
