// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.updater

/**
 * NIGHTLY-1: one update channel (Stable, Nightly), as MANIFEST data: the `channels` map of the Updater's module.json
 * `appUpdate` block, which the build bakes as `channels=<id>,<id>` plus `channels.<id>.<key>=<value>` ([UpdateManifest]).
 * The user picks one with the `updater.releaseChannel` setting; the manifest's `defaultChannel` is the default.
 *
 * - [Mode.LATEST]: GitHub's "latest" release (`GET /releases/latest`), which is never a prerelease.
 * - [Mode.PRERELEASE]: the highest version among the published releases that are either a stable release or a prerelease
 *   whose SemVer pre-release starts with [preRelease] (`1.0.8-nightly.20261107.12`). So a nightly install also moves to a
 *   stable release that is newer than every nightly.
 */
data class ReleaseChannel(val id: String, val label: String, val mode: Mode, val preRelease: String? = null) {
    enum class Mode(val wire: String) {
        LATEST("latest"),
        PRERELEASE("prerelease");

        companion object {
            fun parse(text: String?): Mode? = entries.firstOrNull { it.wire == text?.trim()?.lowercase() }
        }
    }

    /**
     * Whether [version] is a build this channel offers: every channel offers stable versions; a prerelease channel also
     * offers its own pre-releases. A version this channel does not own is a build of ANOTHER channel (the user switched),
     * so [Updater] offers this channel's newest even when it is older ([AvailableUpdate.channelNote]).
     */
    fun owns(version: SemVer): Boolean =
        version.preRelease.isEmpty() || (mode == Mode.PRERELEASE && preRelease != null && version.preRelease.first().equals(preRelease, ignoreCase = true))

    companion object {
        const val STABLE_ID = "stable"

        /** The channel when the manifest names none (a build from before NIGHTLY-1): GitHub's latest release. */
        val STABLE = ReleaseChannel(STABLE_ID, "Stable", Mode.LATEST)

        private val ID = Regex("^[a-z][a-z0-9-]{0,31}$")

        /**
         * The channels [manifest] declares, in its order; a channel with a bad mode or id is skipped with a warning in
         * [warnings]. Stable is always there (the built-in [STABLE] when the manifest has no `stable`).
         */
        fun fromManifest(manifest: (String) -> String?, warnings: MutableList<String> = mutableListOf()): List<ReleaseChannel> {
            val ids = manifest(UpdateManifest.KEY_CHANNELS)?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }.orEmpty()
            val declared = ids.distinct().mapNotNull { id ->
                if (!ID.matches(id)) return@mapNotNull null.also { warnings += "ignored update channel id '$id'" }
                fun value(key: String) = manifest("${UpdateManifest.KEY_CHANNELS}.$id.$key")?.trim()?.takeIf { it.isNotEmpty() }
                val mode = Mode.parse(value("release") ?: Mode.LATEST.wire)
                    ?: return@mapNotNull null.also { warnings += "ignored update channel '$id' (release is not latest or prerelease)" }
                val pre = value("preRelease")?.lowercase()
                if (mode == Mode.PRERELEASE && pre == null) return@mapNotNull null.also { warnings += "ignored update channel '$id' (a prerelease channel needs preRelease)" }
                ReleaseChannel(id, value("label") ?: id.replaceFirstChar { it.uppercase() }, mode, pre)
            }
            return if (declared.any { it.id == STABLE_ID }) declared else listOf(STABLE) + declared
        }
    }
}
