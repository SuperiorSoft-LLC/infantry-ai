// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.harness.install

import com.superiorsoftllc.infantry.daemon.api.downloads.DownloadRequest
import com.superiorsoftllc.infantry.daemon.api.downloads.DownloadResult
import com.superiorsoftllc.infantry.daemon.api.downloads.Downloads
import com.superiorsoftllc.infantry.executables.ExecutableEnvironment
import com.superiorsoftllc.infantry.executables.ExecutableResolver
import com.superiorsoftllc.infantry.executables.HostOs
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ONBOARD-2: the Node.js runtime install against a fake nodejs.org (local bytes through a fake downloader); no network. */
class NodeRuntimeInstallerTest {
    private val root: File = Files.createTempDirectory("node-runtime").toFile()
    private val prefix = File(root, "harness-tools")

    @AfterTest
    fun cleanup() { root.deleteRecursively() }

    private class Env(override val os: HostOs) : ExecutableEnvironment {
        override fun env(name: String): String? = null
        override fun home() = "/home/u"
        override fun exists(path: String) = false
        override fun isDirectory(path: String) = false
        override fun isExecutable(path: String) = false
        override fun isAbsolute(path: String) = true
    }

    private val version = "v22.11.0"
    private val zipName = "node-$version-win-x64.zip"

    private fun nodeZip(rootFolder: String = "node-$version-win-x64/"): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, body) in listOf("node.exe" to "MZ", "npm.cmd" to "@echo npm", "node_modules/npm/package.json" to "{}")) {
                z.putNextEntry(ZipEntry(rootFolder + name)); z.write(body.toByteArray()); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val index = """[
        {"version":"v23.1.0","lts":false,"files":["win-x64-zip"]},
        {"version":"$version","lts":"Jod","files":["linux-x64","win-x64-zip","win-arm64-zip"]},
        {"version":"v20.18.0","lts":"Iron","files":["win-x64-zip"]}
    ]"""

    /** Serves [files] by URL suffix and checks a requested hash, like the core downloader. */
    private class FakeDownloads(private val files: Map<String, ByteArray>) : Downloads {
        val urls = ArrayList<String>()
        override fun download(request: DownloadRequest): DownloadResult {
            urls += request.url
            val body = files.entries.firstOrNull { request.url.endsWith(it.key) }?.value
                ?: return DownloadResult(ok = false, error = "HTTP 404", status = 404)
            val got = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
            if (request.sha256 != null && !request.sha256.equals(got, ignoreCase = true)) return DownloadResult(ok = false, error = "sha256 mismatch")
            request.dest.parentFile?.mkdirs()
            request.dest.writeBytes(body)
            return DownloadResult(ok = true, file = request.dest, bytes = body.size.toLong(), sha256 = got)
        }
    }

    private fun installer(files: Map<String, ByteArray>, os: HostOs = HostOs.WINDOWS, arch: String = "amd64") =
        FakeDownloads(files).let { d -> d to NodeRuntimeInstaller(prefix, Env(os), { d }, "https://example.test/dist", arch) }

    @Test
    fun `the newest LTS with a zip for the platform is picked, never a current release`() {
        assertEquals(version, NodeRuntimeInstaller.latestLts(index, "win-x64"))
        assertEquals(version, NodeRuntimeInstaller.latestLts(index, "win-arm64"))
        assertNull(NodeRuntimeInstaller.latestLts("""[{"version":"v23.1.0","lts":false,"files":["win-x64-zip"]}]""", "win-x64"))
    }

    @Test
    fun `the checksum line is found by exact file name`() {
        val h = "a".repeat(64)
        assertEquals(h, NodeRuntimeInstaller.shaFor("${"b".repeat(64)}  node-x.zip.extra\n$h  node-x.zip\n", "node-x.zip"))
        assertNull(NodeRuntimeInstaller.shaFor("$h  other.zip", "node-x.zip"))
    }

    @Test
    fun `install unpacks the checked zip flat into the prefix`() {
        val zip = nodeZip()
        val (d, node) = installer(mapOf("/index.json" to index.toByteArray(), "/$version/SHASUMS256.txt" to "${sha(zip)}  $zipName\n".toByteArray(), "/$version/$zipName" to zip))
        val lines = ArrayList<String>()
        assertNull(node.install(null) { lines += it })
        assertTrue(File(prefix, "node.exe").isFile)
        assertTrue(File(prefix, "npm.cmd").isFile)
        assertTrue(File(prefix, "node_modules/npm/package.json").isFile)
        assertFalse(File(prefix, ".node-download").exists(), "the download folder is removed")
        assertEquals("https://example.test/dist/$version/$zipName", d.urls.last())
        assertTrue(lines.any { "ready" in it })
    }

    @Test
    fun `a hash mismatch fails with a reason and unpacks nothing`() {
        val zip = nodeZip()
        val (_, node) = installer(mapOf("/index.json" to index.toByteArray(), "/$version/SHASUMS256.txt" to "${"0".repeat(64)}  $zipName\n".toByteArray(), "/$version/$zipName" to zip))
        val error = assertNotNull(node.install(null) {})
        assertTrue("download failed" in error, error)
        assertFalse(File(prefix, "node.exe").exists())
    }

    @Test
    fun `offline says it could not reach nodejs org`() {
        val (_, node) = installer(emptyMap())
        assertTrue(assertNotNull(node.install(null) {}).startsWith("could not reach nodejs.org"))
    }

    @Test
    fun `only Windows x64 and arm64 install automatically`() {
        assertTrue(installer(emptyMap()).second.supported)
        assertTrue(installer(emptyMap(), arch = "aarch64").second.supported)
        assertFalse(installer(emptyMap(), os = HostOs.LINUX).second.supported)
        assertFalse(installer(emptyMap(), arch = "x86").second.supported)
        assertEquals(NodeRuntimeInstaller.UNSUPPORTED, installer(emptyMap(), os = HostOs.MAC).second.install(null) {})
    }

    @Test
    fun `an entry that escapes the folder is refused`() {
        val zip = File(root, "bad.zip")
        ZipOutputStream(zip.outputStream()).use { z -> z.putNextEntry(ZipEntry("node-v1-win-x64/../../evil.txt")); z.write(1); z.closeEntry() }
        assertFailsWith<IllegalArgumentException> { NodeRuntimeInstaller.unpackFlat(zip, prefix.apply { mkdirs() }, "node-v1-win-x64/") }
        assertFalse(File(root, "evil.txt").exists())
    }

    @Test
    fun `the harness installer offers the runtime when npm is missing and installs it alone on request`() {
        val zip = nodeZip()
        val (_, node) = installer(mapOf("/index.json" to index.toByteArray(), "/$version/SHASUMS256.txt" to "${sha(zip)}  $zipName\n".toByteArray(), "/$version/$zipName" to zip))
        val env = Env(HostOs.WINDOWS)
        val harness = HarnessInstaller(prefix, ExecutableResolver(env, harnessPrefix = prefix.path), env, runner = { _, _, _, _ -> ProcessResult(1, "no") }, nodeRuntime = node)
        val report = harness.status()
        assertNull(report.npmPath)
        assertTrue(report.nodeInstallable)
        assertEquals(HarnessInstaller.NODE_AUTO_GUIDANCE, report.guidance)
        val result = harness.install(emptyList(), update = false, runtime = true)
        assertEquals(listOf("node" to "installed"), result.outcomes.map { it.id to it.result })
        assertTrue(File(prefix, "node.exe").isFile)
    }

    @Test
    fun `without a runtime installer the old guidance stays and nothing is offered`() {
        val env = Env(HostOs.WINDOWS)
        val report = HarnessInstaller(prefix, ExecutableResolver(env), env, runner = { _, _, _, _ -> ProcessResult(1, "no") }).status()
        assertFalse(report.nodeInstallable)
        assertEquals(HarnessInstaller.NODE_GUIDANCE, report.guidance)
    }
}
