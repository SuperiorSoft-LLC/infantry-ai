package com.superiorsoftllc.infantry.ui.voice.plugin

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** VOICE-PLUGIN-1: install / verify / remove of voice plugins. Temp dirs only, no network. */
class VoicePluginStoreTest : VoicePluginTestKit() {
    @Test fun nothingIsDownloadedUntilInstallAndInstallVerifiesUnpacksAndWritesTheManifest() {
        val s = store(manifest("ok"))
        assertEquals("missing", s.rows().single().state)
        assertTrue(downloads.isEmpty(), "listing downloads nothing")
        assertEquals("installed", s.install("fake-ok").result)
        val dir = File(root, "fake-ok")
        assertTrue(File(dir, "plugin.json").isFile)
        assertTrue(File(dir, "bin/readme.txt").isFile, "zip unpacked")
        assertFalse(File(dir, "tools.zip").exists(), "archive deleted after unpack")
        assertFalse(File(root, "fake-ok.partial").exists())
        assertEquals("installed", s.rows().single().state)
        assertNotNull(s.ready("fake-ok"))
    }

    @Test fun aBadChecksumFailsTheInstallAndLeavesNothing() {
        val s = store(manifest("ok", voiceSha = "0".repeat(64)))
        val out = s.install("fake-ok")
        assertEquals("failed", out.result)
        assertTrue(out.message!!.contains("checksum"), out.message)
        assertFalse(File(root, "fake-ok").exists())
        assertFalse(File(root, "fake-ok.partial").exists())
    }

    @Test fun aWrongApiCatalogEntryIsNotInstalled() {
        val out = store(manifest("ok", api = "9.0.0")).install("fake-ok")
        assertEquals("failed", out.result)
        assertTrue(downloads.isEmpty())
    }

    @Test fun anInstalledPluginTamperedAfterInstallIsNotReady() {
        val s = store(manifest("ok"))
        s.install("fake-ok")
        File(root, "fake-ok/voice.bin").writeBytes(ByteArray(1000) { 7 })
        var why = ""
        assertNull(VoicePluginStore(root, emptyList(), platform, downloader).ready("fake-ok") { why = it })
        assertTrue("checksum" in why, why)
    }

    @Test fun anInstalledManifestWithAnotherApiOrNoEntryIsBroken() {
        val s = store(manifest("ok"))
        s.install("fake-ok")
        val file = File(root, "fake-ok/plugin.json")
        file.writeText(VoicePluginManifests.encode(manifest("ok", id = "fake-ok", api = "2.0.0")))
        assertEquals("broken", s.rows().single().state)
        assertNull(s.ready("fake-ok"))
        file.writeText(VoicePluginManifests.encode(manifest("ok", id = "fake-ok").copy(entry = emptyList())))
        assertNull(s.ready("fake-ok"))
    }

    @Test fun uninstallRemovesTheFilesAndTheEnabledChoice() {
        val s = store(manifest("ok"))
        s.install("fake-ok")
        s.setEnabled("fake-ok", true)
        assertEquals("fake-ok", s.enabledId())
        assertEquals("removed", s.remove("fake-ok").result)
        assertFalse(File(root, "fake-ok").exists())
        assertNull(s.enabledId())
        assertEquals("missing", s.rows().single().state)
    }

    @Test fun theShippedCatalogIsValidAndOffersOnlyWhereItRuns() {
        val catalog = VoicePluginCatalog.bundled()
        assertEquals(listOf("piper-kathleen"), catalog.map { it.id })
        assertEquals(emptyList(), VoicePluginManifests.problems(catalog.single(), "windows-x64"))
        assertTrue("GPL-3.0" in catalog.single().license)
        assertTrue(VoicePluginStore(root, catalog, "linux-x64", downloader).rows().isEmpty())
        assertEquals(1, VoicePluginStore(root, catalog, "windows-x64", downloader).rows().size)
        assertTrue(downloads.isEmpty())
    }

    @Test fun zipSlipIsRefused() {
        val evil = File(root, "evil.zip")
        ZipOutputStream(evil.outputStream()).use { z -> z.putNextEntry(ZipEntry("../outside.txt")); z.write(1); z.closeEntry() }
        val into = File(root, "into").apply { mkdirs() }
        assertTrue(runCatching { VoicePluginStore.unzip(evil, into) }.isFailure)
        assertFalse(File(root, "outside.txt").exists())
    }

    @Test fun platformIds() {
        assertEquals("windows-x64", VoicePlatform.current("Windows 11", "amd64"))
        assertEquals("macos-arm64", VoicePlatform.current("Mac OS X", "aarch64"))
        assertEquals("linux-x64", VoicePlatform.current("Linux", "x86_64"))
    }
}
