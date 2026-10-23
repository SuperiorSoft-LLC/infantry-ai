package com.superiorsoftllc.agenthq.update

/**
 * SERVER-UPDATE-1: which release asset an update source offers. The desktop app installs the Windows MSI
 * ([WindowsInstaller]); the headless server installs the archive for its own OS and CPU ([ServerArchiveFor]).
 */
interface AssetKind {
    /** The asset to download from a release's [assets], or null when it carries none for this kind. */
    fun pick(assets: List<ReleaseAsset>, latest: SemVer): ReleaseAsset?

    /** The version a release file name carries, or null when the name is not this kind's. */
    fun versionOf(fileName: String): SemVer?

    /** The failure for a release with no asset of this kind; [where] is a tag or a folder. */
    fun missing(where: String): UpdateFailure

    object WindowsInstaller : AssetKind {
        private val NAME = Regex("""^(?:InfantryAI|AgentHQ)-(\d+\.\d+\.\d+)\.msi$""", RegexOption.IGNORE_CASE)
        override fun pick(assets: List<ReleaseAsset>, latest: SemVer) = Updater.installerAsset(assets, latest)
        override fun versionOf(fileName: String) = NAME.matchEntire(fileName)?.let { SemVer.parse(it.groupValues[1]) }
        override fun missing(where: String): UpdateFailure = UpdateFailure.NoWindowsAsset(where)
    }

    class ServerArchiveFor(private val platform: ServerArchive.Platform = ServerArchive.hostPlatform()) : AssetKind {
        override fun pick(assets: List<ReleaseAsset>, latest: SemVer) = ServerArchive.pick(assets, latest, platform)
        override fun versionOf(fileName: String): SemVer? =
            ServerArchive.parse(fileName)?.takeIf { it.platform == platform }?.version
        override fun missing(where: String): UpdateFailure = UpdateFailure.NoServerAsset(where, platform.toString())
    }
}
