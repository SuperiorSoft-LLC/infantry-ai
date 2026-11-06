// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.updater

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** UPDATE-1: a newer release, with the Windows installer to download and the checksum asset when the release has one. */
data class AvailableUpdate(
    val current: SemVer,
    val latest: SemVer,
    val release: GitHubRelease,
    val installer: ReleaseAsset,
    val checksum: ReleaseAsset?,
    /**
     * NIGHTLY-1: set when the offer is OLDER than the running build: the user switched channel (a nightly install now on
     * Stable), so the channel's newest is offered anyway. Shown before the notes; null for an ordinary upgrade.
     */
    val channelNote: String? = null,
) {
    /** The release notes as written on GitHub (Markdown, shown as plain text), or a placeholder; the [channelNote] first. */
    val notes: String get() = listOfNotNull(channelNote, release.body?.trim()?.takeIf { it.isNotEmpty() } ?: "(no release notes)").joinToString("\n\n")

    /** NIGHTLY-1: true when installing this goes back to a lower version (a channel switch). */
    val older: Boolean get() = latest < current
}

/** UPDATE-1: the answer to "is there a newer release?". */
sealed interface UpdateCheck {
    data class UpToDate(val current: SemVer, val latest: SemVer) : UpdateCheck
    data class Available(val update: AvailableUpdate) : UpdateCheck
    data class Failed(val failure: UpdateFailure) : UpdateCheck
}

/** UPDATE-1: an installer on disk whose size (and SHA-256, when published) matched the release. */
data class VerifiedInstaller(val update: AvailableUpdate, val file: File, val sha256: String, val checksumVerified: Boolean)

/**
 * UPDATE-1: the updater's logic, with no UI in it (HEADLESS-1): ask GitHub Releases for the latest release, compare its
 * tag with the running version, and download + verify the matching Windows installer. Handing the installer to Windows
 * is [MsiHandoff]'s job.
 */
class Updater(
    private val config: UpdateConfig,
    private val releases: GitHubReleases = GitHubReleases(),
    private val kind: AssetKind = AssetKind.WindowsInstaller,
) : UpdateSource {

    override fun check(): UpdateCheck {
        val channel = config.channel
        // NIGHTLY-1: Stable asks GitHub's "latest" (never a prerelease); a prerelease channel scans the release list.
        val got = if (channel.mode == ReleaseChannel.Mode.LATEST) releases.latest(config) else releases.newest(config, channel)
        val release = when (got) {
            is Fetched.Failed -> return UpdateCheck.Failed(got.failure)
            is Fetched.Ok -> got.value
        }
        val latest = SemVer.parse(release.tagName)
            ?: return UpdateCheck.Failed(UpdateFailure.BadResponse("the latest tag '${release.tagName}' is not a version"))
        val current = config.currentVersion
        if (release.draft || !channel.owns(latest) || (release.prerelease && channel.mode == ReleaseChannel.Mode.LATEST)) return UpdateCheck.UpToDate(current, latest)
        if (latest == current) return UpdateCheck.UpToDate(current, latest)
        // An older release is offered only when the running build belongs to another channel (Nightly -> Stable).
        val switched = !channel.owns(current)
        if (latest < current && !switched) return UpdateCheck.UpToDate(current, latest)
        val installer = kind.pick(release.assets, latest) ?: return UpdateCheck.Failed(kind.missing(release.tagName))
        val note = if (latest < current) switchNote(channel, current, latest, kind) else null
        return UpdateCheck.Available(AvailableUpdate(current, latest, release, installer, checksumAsset(release.assets, installer), note))
    }

    /**
     * Download [update]'s installer into a fresh temp dir and verify it: the byte count must equal the asset's
     * size, and its SHA-256 must equal the checksum asset's when the release publishes one. Anything else deletes the
     * file and is refused ([UpdateFailure.BadAsset]).
     */
    override fun download(update: AvailableUpdate, into: File): Fetched<VerifiedInstaller> {
        val asset = update.installer
        if (asset.size !in 1..MAX_INSTALLER_BYTES) return Fetched.Failed(UpdateFailure.BadAsset("${asset.name} reports ${asset.size} bytes"))
        val expected = update.checksum?.let { sum ->
            when (val text = readChecksum(sum)) {
                is Fetched.Failed -> return text
                is Fetched.Ok -> parseChecksum(text.value, asset.name)
                    ?: return Fetched.Failed(UpdateFailure.BadAsset("${sum.name} has no SHA-256 for ${asset.name}"))
            }
        }
        into.mkdirs()
        val target = File(into, File(asset.name).name)
        val response = when (val got = releases.openAsset(asset, config)) {
            is Fetched.Failed -> return got
            is Fetched.Ok -> got.value
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        try {
            response.body().use { input ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        written += n
                        if (written > asset.size) break
                        digest.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                    }
                }
            }
        } catch (io: java.io.IOException) {
            target.delete()
            return Fetched.Failed(UpdateFailure.Offline("download interrupted"))
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        val refusal = when {
            written != asset.size -> "size ${if (written > asset.size) "over" else written.toString()} bytes, the release says ${asset.size}"
            expected != null && !expected.equals(actual, ignoreCase = true) -> "SHA-256 mismatch (got $actual, the release says $expected)"
            else -> null
        }
        if (refusal != null) {
            target.delete()
            return Fetched.Failed(UpdateFailure.BadAsset(refusal))
        }
        return Fetched.Ok(VerifiedInstaller(update, target, actual, checksumVerified = expected != null))
    }

    override fun expectedSha256(update: AvailableUpdate): Fetched<String> {
        val sum = update.checksum ?: return Fetched.Failed(UpdateFailure.BadAsset("the release publishes no SHA-256 for ${update.installer.name}"))
        return when (val text = readChecksum(sum)) {
            is Fetched.Failed -> text
            is Fetched.Ok -> parseChecksum(text.value, update.installer.name)?.let { Fetched.Ok(it) }
                ?: Fetched.Failed(UpdateFailure.BadAsset("${sum.name} has no SHA-256 for ${update.installer.name}"))
        }
    }

    internal fun readChecksum(asset: ReleaseAsset): Fetched<String> {
        if (asset.size > MAX_CHECKSUM_BYTES) return Fetched.Failed(UpdateFailure.BadAsset("${asset.name} is too large for a checksum file"))
        return when (val got = releases.openAsset(asset, config)) {
            is Fetched.Failed -> got
            is Fetched.Ok -> try {
                got.value.body().use { Fetched.Ok(String(it.readNBytes(MAX_CHECKSUM_BYTES.toInt()), Charsets.UTF_8)) }
            } catch (io: java.io.IOException) {
                Fetched.Failed(UpdateFailure.Offline("checksum download interrupted"))
            }
        }
    }

    companion object {
        /**
         * NIGHTLY-1: the plain message shown when a channel switch offers an OLDER build. Android cannot install a lower
         * version over a higher one, so the phone is told what it must do instead.
         */
        fun switchNote(channel: ReleaseChannel, current: SemVer, latest: SemVer, kind: AssetKind): String = buildString {
            append("You switched to the ${channel.label} channel. Its newest release, v$latest, is OLDER than the build you run (v$current). ")
            append("Installing it moves this copy back to the ${channel.label} line. A newer build may have changed data an older one cannot read: back up your state folder first if you are unsure.")
            if (kind == AssetKind.AndroidApk) append(" Android refuses to install an older version over a newer one: uninstall the app first (its local data is lost), then install v$latest, or stay on this build until a newer ${channel.label} release comes out.")
        }

        /** INSTALL-1's artifact name: `InfantryAI-<MAJOR.MINOR.BUILD>.msi` (releases before the rename: `Infantry-...`). */
        private val INSTALLER = Regex("""^(?:InfantryAI|AgentHQ)-(${SemVer.FILE_VERSION})\.msi$""", RegexOption.IGNORE_CASE)
        private val SHA256 = Regex("""\b([0-9a-fA-F]{64})\b""")
        private val SUMS_FILES = setOf("sha256sums", "sha256sums.txt", "checksums.txt")
        const val MAX_INSTALLER_BYTES = 2L * 1024 * 1024 * 1024
        const val MAX_CHECKSUM_BYTES = 64L * 1024

        /** The release's Windows installer: `AgentHQ-<latest>.msi`, else the only `AgentHQ-<x.y.z>.msi` it carries. */
        fun installerAsset(assets: List<ReleaseAsset>, latest: SemVer): ReleaseAsset? {
            val msis = assets.filter { INSTALLER.matches(it.name) }
            return msis.firstOrNull { SemVer.parse(INSTALLER.matchEntire(it.name)!!.groupValues[1]) == latest } ?: msis.singleOrNull()
        }

        /** `<installer>.sha256`, else a `SHA256SUMS` / `checksums.txt` file. */
        fun checksumAsset(assets: List<ReleaseAsset>, installer: ReleaseAsset): ReleaseAsset? =
            assets.firstOrNull { it.name.equals("${installer.name}.sha256", ignoreCase = true) }
                ?: assets.firstOrNull { it.name.lowercase() in SUMS_FILES }

        /**
         * The SHA-256 for [assetName] in a checksum file: the `sha256sum` line naming it, else (a single-file `.sha256`)
         * the file's only hash. Null when neither is there.
         */
        fun parseChecksum(text: String, assetName: String): String? {
            val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            lines.firstOrNull { line -> line.split(Regex("\\s+")).any { it.trimStart('*').equals(assetName, ignoreCase = true) } }
                ?.let { line -> SHA256.find(line)?.groupValues?.get(1)?.let { return it.lowercase() } }
            val all = lines.mapNotNull { SHA256.find(it)?.groupValues?.get(1) }
            return all.singleOrNull()?.takeIf { lines.size == 1 }?.lowercase()
        }
    }
}
