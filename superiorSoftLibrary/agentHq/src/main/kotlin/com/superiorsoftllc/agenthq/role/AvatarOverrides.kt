package com.superiorsoftllc.agenthq.role

import com.superiorsoftllc.agenthq.protocol.ValidatedAvatar

import com.superiorsoftllc.agenthq.protocol.ImageFormat

import com.superiorsoftllc.agenthq.protocol.RoleIds

import java.io.File

/**
 * Where a **UI client** keeps its uploaded avatars: `<dataDir>/avatars/<roleId>.<png|webp>`.
 *
 * Deliberately client-side. AgentHQ's UI connects to a local or remote daemon, and putting uploads
 * in the daemon would mean new daemon surface area plus blob storage for no benefit until Phase 7.
 * A per-client skin is a feature for a personal orchestration tool, not a bug -- and if the skew
 * across clients ever annoys, moving these bytes to the daemon is a cheap later migration.
 *
 * The storage rule is the resolution rule: **a file named after the role id IS the override**, for
 * any id including a custom one. Setting an override replaces that id's file (and removes a
 * differently-typed one); deleting it removes the file, and the next [AvatarResolver.resolve] falls
 * straight through to the bundled tier. No index file to keep in sync.
 */
class AvatarOverrides(private val dataDir: File) {

    /** The directory overrides live in; created on write, not on construction. */
    fun dir(): File = File(dataDir, "avatars")

    /**
     * The override file for [roleId], or null when there is none. `.png` is preferred over `.webp`.
     *
     * ORIGIN-SCRUB-1a: an override uploaded under a role id that was since renamed ([RoleIds.RETIRED]) still
     * resolves for the new id, and an older daemon's retired id finds one uploaded under the new id. The id's own
     * file always wins over a renamed one.
     */
    fun find(roleId: String): File? {
        if (!RoleIds.isValid(roleId)) return null
        val current = RoleIds.normalize(roleId)
        return (listOf(roleId, current) + RoleIds.retiredNamesOf(current)).distinct().firstNotNullOfOrNull(::own)
    }

    private fun own(roleId: String): File? =
        candidate(roleId, ImageFormat.PNG)?.takeIf { it.isFile }
            ?: candidate(roleId, ImageFormat.WEBP)?.takeIf { it.isFile }

    /**
     * Store a validated [upload] as [roleId]'s override, replacing any existing one (including one
     * of the other format). Returns the file written.
     */
    fun store(roleId: String, upload: ValidatedAvatar): File {
        require(RoleIds.isValid(roleId)) { "'$roleId' is not a role id" }
        val target = requireNotNull(candidate(roleId, upload.format)) { "cannot build a file name for '$roleId'" }
        target.parentFile?.mkdirs()
        // Drop the other format so a png upload never leaves a stale webp behind it.
        ImageFormat.entries.filter { it != upload.format }.forEach { candidate(roleId, it)?.delete() }
        target.writeBytes(upload.bytes)
        return target
    }

    /** Delete [roleId]'s override in every format. True when something was actually removed. */
    fun clear(roleId: String): Boolean {
        if (!RoleIds.isValid(roleId)) return false
        var removed = false
        ImageFormat.entries.forEach { format ->
            val file = candidate(roleId, format)
            if (file != null && file.isFile && file.delete()) removed = true
        }
        return removed
    }

    private fun candidate(roleId: String, format: ImageFormat): File? =
        if (RoleIds.isValid(roleId)) File(dir(), "$roleId.${format.extension}") else null

    companion object {
        /**
         * The default client data directory: `$AGENTHQ_UI_DATA_DIR`, else `~/.agenthq-ui`. Named for
         * the UI on purpose -- it is not the daemon's `~/.agenthq`, because uploads are the client's.
         */
        fun defaultDataDir(): File {
            val override = com.superiorsoftllc.agenthq.session.agentHqSetting("AGENTHQ_UI_DATA_DIR")
            return if (!override.isNullOrBlank()) File(override) else File(System.getProperty("user.home"), ".agenthq-ui")
        }
    }
}
