// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.roles.daemon

import com.superiorsoftllc.infantry.protocol.ImageFormat

import java.io.File

/**
 * What an avatar lookup resolved to. It is a sealed type so a caller must handle **all three tiers**:
 * the type system is what makes "the product works with zero art" enforceable rather than hoped for.
 */
sealed interface ResolvedAvatar {
    val roleId: String

    /** Tier 1: the operator uploaded one for this role id; the client reads the file itself. */
    data class Override(override val roleId: String, val file: File, val mediaType: String) : ResolvedAvatar

    /** Tier 2: a bundled asset; [bytes] are the image, ready to hand to the client's renderer. */
    class Bundled(override val roleId: String, val mediaType: String, val bytes: ByteArray) : ResolvedAvatar

    /** Tier 3: nothing on disk -- a badge painted from the role's colour and initials. */
    data class Generated(override val roleId: String, val color: String, val initials: String) : ResolvedAvatar
}

/**
 * The avatar resolution chain. **First hit wins**, three tiers:
 *
 * 1. an operator **override** for the role id (any id, canonical or custom -- a custom id works
 *    exactly like the 16);
 * 2. a **bundled** asset named by `/avatars/index.json`;
 * 3. a **generated** fallback: role colour + initials.
 *
 * Tier 3 is load-bearing: with an empty manifest and no overrides, every role resolves to a
 * [ResolvedAvatar.Generated], so a build that ships no art still paints every badge. A bundled asset
 * whose bytes are missing from the classpath falls through to tier 3 rather than throwing -- a
 * swapped bundle must degrade, not break the UI ([brokenAssets] names what degraded).
 */
class AvatarResolver(
    private val manifest: AvatarManifest = AvatarManifest.EMPTY,
    private val overrides: AvatarOverrides? = null,
    private val colorFor: (String) -> String = RolePalette::colorFor,
    private val initialsFor: (String) -> String = RolePalette::initials,
    private val readAsset: (String) -> ByteArray? = ::classpathBytes,
) {

    fun resolve(roleId: String): ResolvedAvatar {
        // Tier 1 -- user override, keyed by any role id.
        overrides?.find(roleId)?.let { file ->
            val mediaType = ImageFormat.fromFileName(file.name)?.mediaType ?: "application/octet-stream"
            return ResolvedAvatar.Override(roleId, file, mediaType)
        }

        // Tier 2 -- bundled default named by the manifest.
        manifest.asset(roleId)?.let { asset ->
            val format = ImageFormat.fromFileName(asset.file)
            val bytes = format?.let { readAsset(AvatarManifest.DIR + asset.file) }
            if (format != null && bytes != null) {
                return ResolvedAvatar.Bundled(roleId, format.mediaType, bytes)
            }
        }

        // Tier 3 -- generated: the role's own colour when the catalog knows it, else the palette.
        return ResolvedAvatar.Generated(roleId, colorFor(roleId), initialsFor(roleId))
    }

    /**
     * Role ids the manifest declares but whose asset cannot be read (missing file or bad extension).
     * Empty is the healthy case; a non-empty list means a swapped bundle silently fell back to tier 3.
     */
    fun brokenAssets(): List<String> = manifest.assets.entries
        .filter { (_, asset) ->
            val format = ImageFormat.fromFileName(asset.file)
            format == null || readAsset(AvatarManifest.DIR + asset.file) == null
        }
        .map { it.key }
        .sorted()

    companion object {
        /** Read a classpath resource as bytes, or null. */
        fun classpathBytes(path: String): ByteArray? {
            val loader = Thread.currentThread().contextClassLoader ?: AvatarResolver::class.java.classLoader
            // ClassLoader.getResourceAsStream takes no leading slash (unlike Class.getResourceAsStream).
            return loader.getResourceAsStream(path.removePrefix("/"))?.use { it.readBytes() }
        }

        /**
         * The production wiring: the classpath manifest, a client override directory, and classpath
         * assets. A manifest that exists but does not parse degrades to [AvatarManifest.EMPTY]
         * (tier 3 paints) instead of taking the UI down.
         *
         * Pass [catalog] so a generated badge paints the role's own colour rather than the palette
         * fallback; without it the palette is used, which is correct but less faithful.
         */
        fun fromClasspath(overrides: AvatarOverrides? = null, catalog: RoleCatalog? = null): AvatarResolver {
            val readText = RoleBundle.classpathReader()
            val manifest = runCatching { AvatarManifest.read(readText) }.getOrDefault(AvatarManifest.EMPTY)
            return AvatarResolver(
                manifest = manifest,
                overrides = overrides,
                colorFor = { id -> catalog?.role(id)?.color ?: RolePalette.colorFor(id) },
            )
        }
    }
}
