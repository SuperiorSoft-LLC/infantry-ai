// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.update

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
) {
    /** The release notes as written on GitHub (Markdown, shown as plain text), or a placeholder. */
    val notes: String get() = release.body?.trim()?.takeIf { it.isNotEmpty() } ?: "(no release notes)"
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
        val release = when (val got = releases.latest(config)) {
            is Fetched.Failed -> return UpdateCheck.Failed(got.failure)
            is Fetched.Ok -> got.value
        }
        val latest = SemVer.parse(release.tagName)
            ?: return UpdateCheck.Failed(UpdateFailure.BadResponse("the latest tag '${release.tagName}' is not a version"))
        if (release.draft || release.prerelease || latest <= config.currentVersion) return UpdateCheck.UpToDate(config.currentVersion, latest)
        val installer = kind.pick(release.assets, latest) ?: return UpdateCheck.Failed(kind.missing(release.tagName))
        return UpdateCheck.Available(AvailableUpdate(config.currentVersion, latest, release, installer, checksumAsset(release.assets, installer)))
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

    private fun readChecksum(asset: ReleaseAsset): Fetched<String> {
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
        /** INSTALL-1's artifact name: `InfantryAI-<MAJOR.MINOR.BUILD>.msi` (releases before the rename: `Infantry-...`). */
        private val INSTALLER = Regex("""^(?:InfantryAI|AgentHQ)-(\d+\.\d+\.\d+)\.msi$""", RegexOption.IGNORE_CASE)
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
