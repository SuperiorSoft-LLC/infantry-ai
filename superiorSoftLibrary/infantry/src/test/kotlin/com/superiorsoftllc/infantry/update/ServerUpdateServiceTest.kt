// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.update

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerUpdateServiceTest {
    private val linux = ServerArchive.Platform("linux", "x64")
    private fun tmp(): File = Files.createTempDirectory("server-update-test").toFile()

    private fun tarEntry(out: ByteArrayOutputStream, name: String, body: ByteArray, mode: Int) {
        val h = ByteArray(512)
        fun put(off: Int, text: String) = text.toByteArray().copyInto(h, off)
        put(0, name); put(100, "%07o".format(mode)); put(124, "%011o".format(body.size)); h[156] = '0'.code.toByte(); put(257, "ustar")
        out.write(h); out.write(body); out.write(ByteArray((512 - body.size % 512) % 512))
    }

    /** A releases folder holding one server archive of [version] and its SHA256SUMS. */
    private fun releases(version: String): File {
        val dir = tmp()
        val ver = File(dir, version).also { it.mkdirs() }
        val archive = File(ver, "InfantryAI-server-$version-linux-x64.tar.gz")
        val raw = ByteArrayOutputStream()
        tarEntry(raw, "InfantryAI-server-$version-linux-x64/bin/infantry", "#!/bin/sh".toByteArray(), 0b111_101_101)
        tarEntry(raw, "InfantryAI-server-$version-linux-x64/lib/app.jar", "jar".toByteArray(), 0b110_100_100)
        raw.write(ByteArray(1024))
        GZIPOutputStream(archive.outputStream()).use { it.write(raw.toByteArray()) }
        File(ver, "SHA256SUMS").writeText(ReleaseAssets.sumsLine(ReleaseAssets.sha256(archive), archive.name) + "\n")
        return dir
    }

    private fun service(
        distribution: Distribution,
        dir: File?,
        install: ServerInstall? = ServerInstall(tmp()),
        running: Int = 0,
        settings: UpdateSettings? = null,
    ) = ServerUpdateService(
        settings = settings ?: dir?.let { UpdateSettings.Enabled(UpdateConfig("local/releases", SemVer(1, 0, 0), localDir = it)) }
            ?: UpdateSettings.Disabled("not configured"),
        distribution = distribution,
        version = "1.0.0",
        install = install,
        runningAgents = { running },
        sourceFor = { UpdateSources.forConfig(it, AssetKind.ServerArchiveFor(linux)) },
        clock = { 42L },
    )

    @Test fun `archive install checks, then stages the verified archive`() {
        val install = ServerInstall(tmp())
        val svc = service(Distribution.ARCHIVE, releases("1.1.0"), install)
        assertTrue(svc.status().canSelfUpdate)
        val checked = svc.check()
        assertEquals("available", checked.state)
        assertEquals("1.1.0", checked.available?.version)
        assertEquals(42L, checked.lastChecked)
        assertEquals(ApplyResult.Staged("1.1.0"), svc.apply(confirm = false))
        assertEquals("1.1.0", install.current())
        assertEquals("staged", svc.status().state)
        assertEquals("1.1.0", svc.status().staged)
    }

    @Test fun `up to date offers nothing and apply has nothing to do`() {
        val svc = service(Distribution.ARCHIVE, releases("1.0.0"))
        val checked = svc.check()
        assertEquals("idle", checked.state)
        assertNull(checked.available)
        assertEquals(ApplyResult.NothingToApply, svc.apply(true))
    }

    @Test fun `working agents need a confirmation`() {
        val install = ServerInstall(tmp())
        val svc = service(Distribution.ARCHIVE, releases("1.1.0"), install, running = 3)
        svc.check()
        assertEquals(ApplyResult.AgentsRunning(3), svc.apply(confirm = false))
        assertNull(install.current())
        assertIs<ApplyResult.Staged>(svc.apply(confirm = true))
    }

    @Test fun `docker reports the newer release and the pull command but never installs`() {
        val install = ServerInstall(tmp())
        val svc = service(Distribution.DOCKER, releases("1.1.0"), install)
        val checked = svc.check()
        assertFalse(checked.canSelfUpdate)
        assertEquals("1.1.0", checked.available?.version)
        assertTrue(checked.instructions!!.contains("docker pull ${ServerUpdateService.DEFAULT_IMAGE}:1.1.0"))
        val applied = assertIs<ApplyResult.Unsupported>(svc.apply(true))
        assertTrue(applied.instructions.contains("docker pull"))
        assertNull(install.current())
    }

    @Test fun `a tampered archive is refused and nothing is installed`() {
        val dir = releases("1.1.0")
        val install = ServerInstall(tmp())
        val svc = service(Distribution.ARCHIVE, dir, install)
        svc.check()
        File(dir, "1.1.0/InfantryAI-server-1.1.0-linux-x64.tar.gz").appendBytes(byteArrayOf(1))
        assertIs<ApplyResult.Failed>(svc.apply(true))
        assertNull(install.current())
        assertEquals("error", svc.status().state)
        assertNotNull(svc.status().error)
    }

    @Test fun `dev run and unconfigured updates cannot self-update and say why`() {
        val dev = service(Distribution.DEV, null, null, settings = UpdateSettings.Disabled("this is a dev build"))
        assertFalse(dev.status().canSelfUpdate)
        assertIs<ApplyResult.Unsupported>(dev.apply(true))
        val off = service(Distribution.ARCHIVE, null)
        assertFalse(off.status().enabled)
        assertEquals("not configured", off.status().disabledReason)
        assertEquals("not configured", off.check().error)
    }

    @Test fun `environment detection picks the distribution, version and install root`() {
        val home = tmp().resolve("root/versions/2.0.0").also { it.mkdirs() }
        File(home, "VERSION").writeText("2.0.0\n")
        val env = mapOf("INFANTRY_APP_HOME" to home.absolutePath, "INFANTRY_UPDATE_DIR" to tmp().absolutePath)
        val svc = ServerUpdateService.fromEnvironment(runningAgents = { 0 }, setting = { env[it] }, dockerEnvExists = { false }, stateDir = tmp())
        assertEquals(Distribution.ARCHIVE, svc.distribution)
        assertEquals("2.0.0", svc.version)
        assertTrue(svc.status().canSelfUpdate)

        val docker = ServerUpdateService.fromEnvironment(
            runningAgents = { 0 },
            setting = { mapOf("INFANTRY_VERSION" to "3.1.4", "INFANTRY_DISTRIBUTION" to "docker")[it] },
            dockerEnvExists = { false },
            stateDir = tmp(),
        )
        assertEquals(Distribution.DOCKER, docker.distribution)
        assertEquals("3.1.4", docker.status().version)
        assertEquals(Distribution.DEV, ServerUpdateService.fromEnvironment({ 0 }, { null }, { false }, tmp()).distribution)
    }
}
