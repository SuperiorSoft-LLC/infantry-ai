// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.updater

import kotlinx.serialization.Serializable

/**
 * UPDATER-1: the answer of `GET /update/client?platform=android&current=<x.y.z>`: what a phone should install.
 *
 * [result]: `available` (then [version], [name], [size], [sha256] and a token-free [url] are set), `up_to_date`,
 * `disabled` (updates are off: [message] says why), `unsupported` (this platform or source has no client download) or
 * `failed` ([message]). The phone downloads [url] itself, refuses a file whose size or SHA-256 differ, and installs it.
 */
@Serializable
data class ClientUpdateOffer(
    val result: String,
    val current: String? = null,
    val version: String? = null,
    val name: String? = null,
    val size: Long? = null,
    val sha256: String? = null,
    val url: String? = null,
    val notes: String? = null,
    val message: String? = null,
)

/**
 * UPDATER-1: the client (phone) side of the same updater. The daemon asks the release source on the phone's behalf, with the
 * same settings (`updater.source`, repo, channel), so a private repo's token stays on the daemon: the phone only ever gets a
 * public download URL or GitHub's short-lived signed redirect ([GitHubReleases.tokenlessUrl]), plus the SHA-256 to check.
 *
 * [resolve] maps the phone's version to the settings to ask with (installed = true: the phone is an installed app even when
 * the daemon is a dev build).
 */
class ClientUpdates(
    private val resolve: (version: String) -> UpdateSettings,
    private val releases: GitHubReleases = GitHubReleases(),
) {
    fun offer(platform: String?, current: String?): ClientUpdateOffer {
        if (platform?.trim()?.lowercase() != ANDROID) return ClientUpdateOffer("unsupported", current, message = "no client updates for platform '${platform ?: ""}' (only $ANDROID)")
        val version = SemVer.parse(current) ?: return ClientUpdateOffer("failed", current, message = "the phone's version '${current ?: ""}' is not x.y.z")
        val config = when (val settings = resolve(version.toString())) {
            is UpdateSettings.Disabled -> return ClientUpdateOffer("disabled", current, message = settings.reason)
            is UpdateSettings.Enabled -> settings.config
        }
        if (config.localDir != null) return ClientUpdateOffer("unsupported", current, message = "a local releases folder cannot serve a phone; use the GitHub source")
        val updater = Updater(config, releases, AssetKind.AndroidApk)
        val update = when (val check = updater.check()) {
            is UpdateCheck.UpToDate -> return ClientUpdateOffer("up_to_date", current, version = check.latest.toString())
            is UpdateCheck.Failed -> return ClientUpdateOffer("failed", current, message = check.failure.message)
            is UpdateCheck.Available -> check.update
        }
        val apk = update.installer
        if (apk.size !in 1..Updater.MAX_INSTALLER_BYTES) return ClientUpdateOffer("failed", current, message = "${apk.name} reports ${apk.size} bytes")
        // The phone must be able to verify what it downloads: no published checksum, no offer.
        val sums = update.checksum ?: return ClientUpdateOffer("failed", current, message = "release ${update.latest} publishes no SHA-256 for ${apk.name}")
        val sha = when (val text = updater.readChecksum(sums)) {
            is Fetched.Failed -> return ClientUpdateOffer("failed", current, message = text.failure.message)
            is Fetched.Ok -> Updater.parseChecksum(text.value, apk.name)
                ?: return ClientUpdateOffer("failed", current, message = "${sums.name} has no SHA-256 for ${apk.name}")
        }
        val url = when (val got = releases.tokenlessUrl(apk, config)) {
            is Fetched.Failed -> return ClientUpdateOffer("failed", current, message = got.failure.message)
            is Fetched.Ok -> got.value
        }
        return ClientUpdateOffer(
            "available", current, version = update.latest.toString(), name = apk.name, size = apk.size,
            sha256 = sha.lowercase(), url = url, notes = listOfNotNull(update.channelNote, update.release.body).joinToString("\n\n").takeIf { it.isNotEmpty() }?.take(NOTES_CHARS),
        )
    }

    companion object {
        const val ANDROID = "android"

        /** The release notes a phone shows before installing; longer notes are cut (the full text is on the release page). */
        const val NOTES_CHARS = 4_000
    }
}
