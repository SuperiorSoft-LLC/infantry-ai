package com.superiorsoftllc.agenthq.update

import com.superiorsoftllc.agenthq.daemon.UpdateApplyReply
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerUpdateCliTest {
    private fun tmp(): File = Files.createTempDirectory("server-cli-test").toFile()

    private class Fake(
        private val status: ServerUpdateStatus,
        private val replies: MutableList<UpdateApplyReply>,
    ) : UpdateBackend {
        override val label = "fake"
        val applied = mutableListOf<Boolean>()
        override fun check() = status
        override fun apply(confirm: Boolean): UpdateApplyReply { applied += confirm; return replies.removeAt(0) }
    }

    private fun status(
        available: String? = "1.1.0",
        canSelfUpdate: Boolean = true,
        error: String? = null,
        enabled: Boolean = true,
    ) = ServerUpdateStatus(
        version = "1.0.0", distribution = "archive", canSelfUpdate = canSelfUpdate, enabled = enabled, state = "idle",
        available = available?.let { ServerUpdateOffer(it, "notes line") }, error = error,
        instructions = if (canSelfUpdate) null else "docker pull x",
    )

    private fun run(args: List<String>, backend: UpdateBackend, answers: List<Boolean> = emptyList()): Pair<Int, String> {
        val bytes = ByteArrayOutputStream()
        val queue = answers.toMutableList()
        val code = ServerUpdateCli.update(args, backend, PrintStream(bytes, true)) { queue.removeAt(0) }
        return code to bytes.toString()
    }

    @Test fun `up to date exits 0 and offers nothing`() {
        val (code, out) = run(emptyList(), Fake(status(available = null), mutableListOf()))
        assertEquals(0, code)
        assertTrue(out.contains("Up to date"))
    }

    @Test fun `check only reports an available update with exit 10 and applies nothing`() {
        val fake = Fake(status(), mutableListOf())
        val (code, out) = run(listOf("--check-only"), fake)
        assertEquals(10, code)
        assertTrue(out.contains("1.1.0") && out.contains("notes line"))
        assertTrue(fake.applied.isEmpty())
    }

    @Test fun `answering no installs nothing`() {
        val fake = Fake(status(), mutableListOf())
        val (code, _) = run(emptyList(), fake, answers = listOf(false))
        assertEquals(0, code)
        assertTrue(fake.applied.isEmpty())
    }

    @Test fun `yes stages without asking`() {
        val fake = Fake(status(), mutableListOf(UpdateApplyReply("staged", version = "1.1.0", message = "restarting into 1.1.0")))
        val (code, out) = run(listOf("--yes"), fake)
        assertEquals(0, code)
        assertEquals(listOf(false), fake.applied)
        assertTrue(out.contains("restarting into 1.1.0"))
    }

    @Test fun `working agents are asked about and confirmed on a second apply`() {
        val fake = Fake(status(), mutableListOf(UpdateApplyReply("agents_running", running = 2), UpdateApplyReply("staged", version = "1.1.0")))
        val (code, _) = run(emptyList(), fake, answers = listOf(true, true))
        assertEquals(0, code)
        assertEquals(listOf(false, true), fake.applied)
        val declined = Fake(status(), mutableListOf(UpdateApplyReply("agents_running", running = 2)))
        assertEquals(0, run(emptyList(), declined, answers = listOf(true, false)).first)
        assertEquals(listOf(false), declined.applied)
    }

    @Test fun `docker and dev print the instructions and exit 3`() {
        val (code, out) = run(listOf("--yes"), Fake(status(canSelfUpdate = false), mutableListOf()))
        assertEquals(3, code)
        assertTrue(out.contains("docker pull x"))
    }

    @Test fun `a failed check or apply exits 1`() {
        assertEquals(1, run(emptyList(), Fake(status(error = "offline"), mutableListOf())).first)
        assertEquals(1, run(listOf("--yes"), Fake(status(), mutableListOf(UpdateApplyReply("failed", message = "bad sum")))).first)
    }

    @Test fun `adopt copies a fresh unpack into versions, makes it current and drops the stubs`() {
        val parent = tmp()
        val app = File(parent, "InfantryAI-server-1.0.0-linux-x64")
        File(app, "bin").mkdirs(); File(app, "lib").mkdirs()
        File(app, "bin/agenthq-server.sh").writeText("#!/bin/sh")
        File(app, "bin/infantry").writeText("#!/bin/sh stub")
        File(app, "bin/infantry.cmd").writeText("@echo off")
        File(app, "lib/app.jar").writeText("jar")
        val install = ServerInstall(parent)
        install.adopt(app, "1.0.0")
        assertEquals("1.0.0", install.current())
        assertTrue(File(install.dirOf("1.0.0"), "lib/app.jar").isFile)
        assertEquals("#!/bin/sh stub", File(parent, "infantry").readText())
        assertTrue(File(parent, "infantry.cmd").isFile)
        assertTrue(app.isDirectory, "the original folder is left alone")
        install.adopt(app, "1.0.0") // idempotent
        assertNull(install.previous())
    }

    @Test fun `a supervised archive daemon restarts itself after staging, an unsupervised one does not`() {
        val dir = tmp().resolve("1.1.0").also { it.mkdirs() }
        val archive = File(dir, "InfantryAI-server-1.1.0-linux-x64.tar.gz")
        // reuse the tar writer from ServerInstallTest via a minimal one-file-per-required-path archive
        val raw = ByteArrayOutputStream()
        fun entry(name: String, body: String, mode: Int) {
            val h = ByteArray(512)
            fun put(off: Int, t: String) = t.toByteArray().copyInto(h, off)
            put(0, name); put(100, "%07o".format(mode)); put(124, "%011o".format(body.length)); h[156] = '0'.code.toByte(); put(257, "ustar")
            raw.write(h); raw.write(body.toByteArray()); raw.write(ByteArray((512 - body.length % 512) % 512))
        }
        entry("s/bin/agenthq", "x", 0b111_101_101)
        entry("s/lib/a.jar", "x", 0b110_100_100)
        raw.write(ByteArray(1024))
        java.util.zip.GZIPOutputStream(archive.outputStream()).use { it.write(raw.toByteArray()) }
        File(dir, "SHA256SUMS").writeText(ReleaseAssets.sumsLine(ReleaseAssets.sha256(archive), archive.name) + "\n")
        val settings = UpdateSettings.Enabled(UpdateConfig("local/releases", SemVer(1, 0, 0), localDir = dir.parentFile))
        var restarted: String? = null
        fun service(hook: ((String) -> Unit)?) = ServerUpdateService(
            settings, Distribution.ARCHIVE, "1.0.0", ServerInstall(tmp()),
            sourceFor = { UpdateSources.forConfig(it, AssetKind.ServerArchiveFor(ServerArchive.Platform("linux", "x64"))) },
            onStaged = hook,
        )
        val supervised = service { restarted = it }
        assertTrue(supervised.restartsAfterStaging)
        supervised.check()
        assertEquals(ApplyResult.Staged("1.1.0"), supervised.apply(true))
        assertEquals("1.1.0", restarted)
        val plain = service(null)
        assertFalse(plain.restartsAfterStaging)
    }
}
