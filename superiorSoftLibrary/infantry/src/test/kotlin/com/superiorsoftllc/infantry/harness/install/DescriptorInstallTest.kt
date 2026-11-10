// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.harness.install

import com.superiorsoftllc.infantry.executables.ExecutableEnvironment
import com.superiorsoftllc.infantry.executables.ExecutableOverrides
import com.superiorsoftllc.infantry.executables.ExecutableResolver
import com.superiorsoftllc.infantry.executables.HostOs
import com.superiorsoftllc.infantry.harness.descriptor.HarnessDescriptor
import com.superiorsoftllc.infantry.harness.descriptor.InvalidDescriptor
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** HARNESS-DESCRIPTOR-INSTALL-1: the descriptor `install` field, and the installer listing and installing descriptor harnesses. */
class DescriptorInstallTest {
    private val root: File = Files.createTempDirectory("desc-install").toFile()
    private val userDir = File(root, "harnesses")
    private val samplesDir = File(root, "samples").also { it.mkdirs() }
    private val prefix = File(root, "harness-tools")

    @AfterTest fun clean() { root.deleteRecursively() }

    private fun descriptor(extra: String) = Json.parseToJsonElement("""{"id":"cc","name":"Command Code","command":"command-code" $extra}""")

    @Test
    fun installFieldParsesAndIsOptional() {
        assertNull(HarnessDescriptor.parse(descriptor("")).install)
        val d = HarnessDescriptor.parse(descriptor(""","install":{"npm":"command-code"}"""))
        assertEquals("command-code", d.install?.npm)
    }

    @Test
    fun badInstallIsRefusedWithAFixedReason() {
        for (bad in listOf(
            ""","install":"command-code"""",
            ""","install":{}""",
            ""","install":{"npm":"--global evil"}""",
            ""","install":{"npm":"ok","bin":"../x"}""",
        )) assertFailsWith<InvalidDescriptor>(bad) { HarnessDescriptor.parse(descriptor(bad)) }
    }

    @Test
    fun shippedSampleIsOfferedUntilAdoptedAndTheUserCopyWins() {
        File(samplesDir, "cc.json").writeText("""{"id":"cc","command":"command-code","install":{"npm":"command-code"}}""")
        File(samplesDir, "noinstall.json").writeText("""{"id":"x","command":"x"}""")
        val installables = DescriptorInstallables(userDir, samplesDir)
        val first = installables.list().single()
        assertEquals("cc", first.id)
        assertEquals("command-code", first.npmPackage)
        assertTrue(first.sample != null)

        installables.adopt(first)
        assertTrue(File(userDir, "cc.json").isFile)
        val after = installables.list().single()
        assertNull(after.sample, "once copied it is the user's own descriptor, not a sample")
    }

    @Test
    fun installerListsAndInstallsASampleDescriptorHarness() {
        File(samplesDir, "cc.json").writeText("""{"id":"cc","command":"command-code","install":{"npm":"command-code"}}""")
        val installables = DescriptorInstallables(userDir, samplesDir)
        val files = mutableSetOf("/usr/bin/npm", "/usr/bin/node")
        val env = object : ExecutableEnvironment {
            override val os = HostOs.LINUX
            override fun env(name: String) = if (name == "PATH") "/usr/bin" else null
            override fun home() = "/home/u"
            override fun exists(path: String) = path in files
            override fun isDirectory(path: String) = false
            override fun isExecutable(path: String) = path in files
            override fun isAbsolute(path: String) = true
        }
        val calls = ArrayList<List<String>>()
        val runner = HarnessProcessRunner { argv, _, _, _ ->
            calls += argv
            when {
                argv.last() == "--version" -> ProcessResult(0, "1.2.3")
                argv.getOrNull(1) == "install" -> { files += env.join(prefix.path, "bin", "command-code"); ProcessResult(0, "") }
                else -> ProcessResult(1, "unexpected")
            }
        }
        var adopted = 0
        val installer = HarnessInstaller(
            prefix, ExecutableResolver(env, ExecutableOverrides.InMemory(), prefix.path), env, ExecutableOverrides.InMemory(), runner,
            fromDescriptors = installables::list, onSampleAdopted = { installables.adopt(it); adopted++ },
        )
        val listed = installer.status().harnesses.single { it.id == "cc" }
        assertEquals("missing", listed.state)
        assertTrue(listed.note!!.startsWith("available, not installed"))

        val result = installer.install(listOf("cc"), update = false)
        assertEquals("installed", result.outcomes.single().result)
        assertEquals(1, adopted)
        assertTrue(File(userDir, "cc.json").isFile)
        assertEquals("command-code@latest", calls.single { it.getOrNull(1) == "install" }.last())
    }
}
