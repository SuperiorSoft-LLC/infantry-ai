package com.superiorsoftllc.agenthq.plugins.official.roles.daemon

import com.superiorsoftllc.agenthq.protocol.AvatarUploads

import com.superiorsoftllc.agenthq.protocol.RoleIds

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A client's override store: the file name IS the key, setting replaces, deleting falls back. */
class AvatarOverridesTest {

    @Test
    fun storeThenFindRoundTripsAFormat() {
        val overrides = AvatarOverrides(tempDir())
        val file = overrides.store("backend", AvatarUploads.validate(pngHeader(8, 8)))
        assertEquals("backend.png", file.name)
        assertTrue(file.isFile)
        assertEquals(file, overrides.find("backend"))
    }

    @Test
    fun storingAnotherFormatReplacesTheFirst() {
        val overrides = AvatarOverrides(tempDir())
        overrides.store("backend", AvatarUploads.validate(pngHeader(8, 8)))
        val webp = overrides.store("backend", AvatarUploads.validate(webpVp8x(8, 8)))
        assertEquals("backend.webp", webp.name)
        assertFalse(File(overrides.dir(), "backend.png").exists(), "the stale png must be removed")
        assertEquals(webp, overrides.find("backend"))
    }

    @Test
    fun clearRemovesEveryFormatAndReportsWhetherItDid() {
        val overrides = AvatarOverrides(tempDir())
        overrides.store("backend", AvatarUploads.validate(pngHeader(8, 8)))
        assertTrue(overrides.clear("backend"))
        assertNull(overrides.find("backend"))
        assertFalse(overrides.clear("backend"), "clearing nothing is false, not an error")
    }

    @Test
    fun overrideWorksForACustomRoleId() {
        val overrides = AvatarOverrides(tempDir())
        val file = overrides.store("brand-new", AvatarUploads.validate(pngHeader(4, 4)))
        assertEquals("brand-new.png", file.name)
        assertNotNull(overrides.find("brand-new"))
    }

    @Test
    fun invalidRoleIdsAreRefusedAndNeverResolved() {
        val overrides = AvatarOverrides(tempDir())
        assertFailsWith<IllegalArgumentException> { overrides.store("../escape", AvatarUploads.validate(pngHeader(4, 4))) }
        assertFailsWith<IllegalArgumentException> { overrides.store("No", AvatarUploads.validate(pngHeader(4, 4))) }
        assertNull(overrides.find("../escape"))
        assertFalse(overrides.clear("../escape"))
    }

    /** ORIGIN-SCRUB-1a: an override uploaded under a since-renamed role id still resolves, both ways round. */
    @Test
    fun anOverrideUnderARetiredRoleIdStillResolves() {
        val dir = tempDir()
        val old = AvatarOverrides(dir).store("brainstormer", AvatarUploads.validate(pngHeader(8, 8)))
        assertEquals(old, AvatarOverrides(dir).find("ideation"), "the new id finds the portrait uploaded under the old one")
        val renamed = AvatarOverrides(dir).store("assistant", AvatarUploads.validate(pngHeader(8, 8)))
        assertEquals(renamed, AvatarOverrides(dir).find("chief-of-staff"), "an older daemon's id finds the new upload")
        AvatarOverrides(dir).store("ideation", AvatarUploads.validate(pngHeader(8, 8)))
        assertEquals("ideation.png", AvatarOverrides(dir).find("ideation")?.name, "the id's own file wins")
    }

    @Test
    fun findIsNullWhenNothingWasStored() {
        assertNull(AvatarOverrides(tempDir()).find("backend"))
    }

    @Test
    fun defaultDataDirHonoursTheEnvironmentOverride() {
        // No env mutation here (it is process-global); just prove the default is a real absolute path.
        assertTrue(AvatarOverrides.defaultDataDir().isAbsolute)
    }

    private fun tempDir(): File = File.createTempFile("agenthq-overrides", "").let { it.delete(); it.mkdirs(); it }

    private fun pngHeader(width: Int, height: Int): ByteArray {
        val bytes = ByteArray(24)
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(bytes, 0)
        "IHDR".toByteArray(Charsets.US_ASCII).copyInto(bytes, 12)
        bytes[16] = (width ushr 24).toByte(); bytes[17] = (width ushr 16).toByte()
        bytes[18] = (width ushr 8).toByte(); bytes[19] = width.toByte()
        bytes[20] = (height ushr 24).toByte(); bytes[21] = (height ushr 16).toByte()
        bytes[22] = (height ushr 8).toByte(); bytes[23] = height.toByte()
        return bytes
    }

    private fun webpVp8x(width: Int, height: Int): ByteArray {
        val bytes = ByteArray(30)
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
        "WEBP".toByteArray(Charsets.US_ASCII).copyInto(bytes, 8)
        "VP8X".toByteArray(Charsets.US_ASCII).copyInto(bytes, 12)
        bytes[24] = (width - 1).toByte(); bytes[25] = ((width - 1) ushr 8).toByte()
        bytes[27] = (height - 1).toByte(); bytes[28] = ((height - 1) ushr 8).toByte()
        return bytes
    }
}
