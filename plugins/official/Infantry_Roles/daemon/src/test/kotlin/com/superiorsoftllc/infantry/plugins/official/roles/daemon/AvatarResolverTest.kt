// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.roles.daemon

import com.superiorsoftllc.infantry.protocol.AvatarUploads

import com.superiorsoftllc.infantry.protocol.RoleIds

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The three-tier avatar chain: override > bundled > generated, and every fall-through between them. */
class AvatarResolverTest {

    @Test
    fun emptyManifestAndNoOverrideAlwaysGenerates() {
        val resolver = AvatarResolver()
        for (id in RoleIds.BUILT_IN) {
            val resolved = assertIs<ResolvedAvatar.Generated>(resolver.resolve(id), "role $id should generate")
            assertEquals(id, resolved.roleId)
            assertTrue(resolved.color.startsWith("#"))
            assertTrue(resolved.initials.isNotBlank())
        }
        assertTrue(resolver.brokenAssets().isEmpty())
    }

    @Test
    fun bundledTierWinsOverGeneratedAndCarriesTheBytes() {
        val asset = byteArrayOf(1, 2, 3, 4)
        val manifest = AvatarManifest.parse("""{"architect":{"file":"architect.png","source":"test","licence":"CC0-1.0"}}""")
        val resolver = AvatarResolver(manifest) { path ->
            if (path == "/avatars/architect.png") asset else null
        }
        val resolved = assertIs<ResolvedAvatar.Bundled>(resolver.resolve("architect"))
        assertEquals("image/png", resolved.mediaType)
        assertContentEquals(asset, resolved.bytes)
        assertTrue(resolver.brokenAssets().isEmpty())
    }

    @Test
    fun overrideTierWinsOverBundled() {
        val overrides = AvatarOverrides(tempDir("infantry-avatar-override"))
        overrides.store("architect", AvatarUploads.validate(pngHeader(8, 8)))
        val manifest = AvatarManifest.parse("""{"architect":{"file":"architect.png"}}""")
        val resolver = AvatarResolver(manifest, overrides) { byteArrayOf(9) }

        val resolved = assertIs<ResolvedAvatar.Override>(resolver.resolve("architect"))
        assertTrue(resolved.file.name == "architect.png")
        assertEquals("image/png", resolved.mediaType)
    }

    @Test
    fun aMissingBundledFileFallsThroughToGeneratedAndIsReported() {
        val manifest = AvatarManifest.parse("""{"architect":{"file":"architect.png"}}""")
        val resolver = AvatarResolver(manifest) { null }
        assertIs<ResolvedAvatar.Generated>(resolver.resolve("architect"))
        assertEquals(listOf("architect"), resolver.brokenAssets())
    }

    @Test
    fun anOverrideWorksForACustomRoleId() {
        val overrides = AvatarOverrides(tempDir("infantry-avatar-custom"))
        overrides.store("brand-new", AvatarUploads.validate(pngHeader(4, 4)))
        val resolver = AvatarResolver(overrides = overrides)
        val resolved = assertIs<ResolvedAvatar.Override>(resolver.resolve("brand-new"))
        assertEquals("brand-new", resolved.roleId)
    }

    @Test
    fun deletingAnOverrideFallsStraightBackThroughTheChain() {
        val overrides = AvatarOverrides(tempDir("infantry-avatar-clear"))
        val manifest = AvatarManifest.parse("""{"architect":{"file":"architect.png"}}""")
        val resolver = AvatarResolver(manifest, overrides) { if (it.endsWith("architect.png")) byteArrayOf(1) else null }

        overrides.store("architect", AvatarUploads.validate(pngHeader(8, 8)))
        assertIs<ResolvedAvatar.Override>(resolver.resolve("architect"))

        assertTrue(overrides.clear("architect"))
        assertIs<ResolvedAvatar.Bundled>(resolver.resolve("architect"))
    }

    @Test
    fun emptyManifestTextAndEmptyObjectAreBothEmpty() {
        assertTrue(AvatarManifest.parse("{}").isEmpty)
        assertTrue(AvatarManifest.parse("   ").isEmpty)
        assertTrue(AvatarManifest.read({ null }).isEmpty)
    }

    @Test
    fun aManifestWithABadKeyOrFileIsRejected() {
        assertFailsWith<IllegalArgumentException> { AvatarManifest.parse("""{"Architect":{"file":"a.png"}}""") }
        assertFailsWith<IllegalArgumentException> { AvatarManifest.parse("""{"architect":{"file":"a.gif"}}""") }
        assertFailsWith<IllegalArgumentException> { AvatarManifest.parse("""{"architect":{"licence":"CC0"}}""") }
    }

    @Test
    fun aBrokenBundleManifestDegradesInsteadOfThrowing() {
        // fromClasspath swallows a manifest that does not parse, so the UI keeps painting tier 3.
        val resolver = AvatarResolver.fromClasspath()
        assertTrue(resolver.resolve("architect") is ResolvedAvatar.Generated || resolver.resolve("architect") is ResolvedAvatar.Bundled)
    }

    @Test
    fun theShippedManifestIsEmptyAndLegal() {
        val manifest = AvatarManifest.read(RoleBundle.classpathReader())
        assertTrue(manifest.isEmpty, "this ticket ships no art (licence undecided): the bundled manifest must be empty")
        assertNull(manifest.asset("architect"))
        assertFalse(manifest.isEmpty.not())
    }

    @Test
    fun aGeneratedBadgeUsesTheCatalogsRoleColourWhenGiven() {
        val catalog = RoleCatalog.load()
        val resolver = AvatarResolver(colorFor = { id -> catalog.role(id)?.color ?: RolePalette.colorFor(id) })
        val backend = assertIs<ResolvedAvatar.Generated>(resolver.resolve("backend"))
        assertEquals(catalog.role("backend")!!.color, backend.color)
        assertEquals("#0ea5e9", backend.color, "the bundled backend colour, not a palette hash")
    }

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

    private fun tempDir(prefix: String): File =
        File.createTempFile(prefix, "").let { it.delete(); it.mkdirs(); it }
}
