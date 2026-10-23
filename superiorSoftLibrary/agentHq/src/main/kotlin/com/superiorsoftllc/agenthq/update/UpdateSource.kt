package com.superiorsoftllc.agenthq.update

import java.io.File
import java.nio.file.Files

/** UPDATE-2: where a newer build comes from. [Updater] is the GitHub one; [LocalDirSource] reads a releases folder. */
interface UpdateSource {
    fun check(): UpdateCheck
    fun download(update: AvailableUpdate, into: File = Files.createTempDirectory("agenthq-update-").toFile()): Fetched<VerifiedInstaller>
}

/**
 * UPDATE-2: updates from a local releases folder, no GitHub. The folder holds what `releaseAssets` writes, one
 * sub-folder per version (the folder itself also counts):
 * `<dir>/1.0.5/{AgentHQ-1.0.5.msi, SHA256SUMS, RELEASE-NOTES.md}`. The highest `AgentHQ-<x.y.z>.msi` wins; it is only
 * offered when it is newer than [currentVersion] AND a SHA-256 for it is listed in `SHA256SUMS` next to it. The copy
 * to a fresh temp dir is verified again against that hash, so a half-written or tampered file is refused.
 */
class LocalDirSource(
    private val dir: File,
    private val currentVersion: SemVer,
    private val kind: AssetKind = AssetKind.WindowsInstaller,
) : UpdateSource {

    private data class Candidate(val version: SemVer, val msi: File)

    override fun check(): UpdateCheck {
        if (!dir.isDirectory) return UpdateCheck.Failed(UpdateFailure.BadResponse("the releases folder $dir does not exist"))
        val best = (listOf(dir) + (dir.listFiles { f -> f.isDirectory }?.toList() ?: emptyList()))
            .flatMap { d -> d.listFiles { f -> f.isFile }?.toList() ?: emptyList() }
            .mapNotNull { f -> kind.versionOf(f.name)?.let { Candidate(it, f) } }
            .maxByOrNull { it.version }
            ?: return UpdateCheck.Failed(kind.missing("in $dir"))
        if (best.version <= currentVersion) return UpdateCheck.UpToDate(currentVersion, best.version)
        val sums = File(best.msi.parentFile, ReleaseAssets.SUMS_FILE).takeIf { it.isFile }
            ?: return UpdateCheck.Failed(UpdateFailure.BadAsset("${best.msi.name} has no ${ReleaseAssets.SUMS_FILE} next to it"))
        val notes = File(best.msi.parentFile, ReleaseAssets.NOTES_FILE).takeIf { it.isFile }?.readText()
        val installer = ReleaseAsset(best.msi.name, best.msi.length(), url = best.msi.absolutePath)
        val checksum = ReleaseAsset(sums.name, sums.length(), url = sums.absolutePath)
        val release = GitHubRelease(tagName = "v${best.version}", body = notes, assets = listOf(installer, checksum))
        return UpdateCheck.Available(AvailableUpdate(currentVersion, best.version, release, installer, checksum))
    }

    override fun download(update: AvailableUpdate, into: File): Fetched<VerifiedInstaller> {
        val src = File(update.installer.url ?: return Fetched.Failed(UpdateFailure.BadAsset("no path")))
        val sums = File(update.checksum?.url ?: return Fetched.Failed(UpdateFailure.BadAsset("no ${ReleaseAssets.SUMS_FILE}")))
        val expected = runCatching { Updater.parseChecksum(sums.readText(), src.name) }.getOrNull()
            ?: return Fetched.Failed(UpdateFailure.BadAsset("${sums.name} has no SHA-256 for ${src.name}"))
        into.mkdirs()
        val target = File(into, src.name)
        try {
            src.copyTo(target, overwrite = true)
        } catch (io: java.io.IOException) {
            target.delete()
            return Fetched.Failed(UpdateFailure.BadAsset("could not copy ${src.name}: ${io.message}"))
        }
        val actual = ReleaseAssets.sha256(target)
        if (!actual.equals(expected, ignoreCase = true)) {
            target.delete()
            return Fetched.Failed(UpdateFailure.BadAsset("SHA-256 mismatch (got $actual, ${sums.name} says $expected)"))
        }
        return Fetched.Ok(VerifiedInstaller(update, target, actual, checksumVerified = true))
    }
}

/** UPDATE-4: picks the source for a resolved config: a local releases folder ([LocalDirSource]), else GitHub Releases ([Updater]). */
object UpdateSources {
    fun forConfig(config: UpdateConfig, kind: AssetKind = AssetKind.WindowsInstaller): UpdateSource =
        config.localDir?.let { LocalDirSource(it, config.currentVersion, kind) } ?: Updater(config, kind = kind)
}
