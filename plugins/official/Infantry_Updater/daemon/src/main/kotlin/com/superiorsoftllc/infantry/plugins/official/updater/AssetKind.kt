// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.updater

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
        private val NAME = Regex("""^(?:InfantryAI|AgentHQ)-(${SemVer.FILE_VERSION})\.msi$""", RegexOption.IGNORE_CASE)
        override fun pick(assets: List<ReleaseAsset>, latest: SemVer) = Updater.installerAsset(assets, latest)
        override fun versionOf(fileName: String) = NAME.matchEntire(fileName)?.let { SemVer.parse(it.groupValues[1]) }
        override fun missing(where: String): UpdateFailure = UpdateFailure.NoWindowsAsset(where)
    }

    /** UPDATER-1: the Android app, `InfantryAI-<version>.apk` (a debug-signed CI build: `InfantryAI-<version>-debug.apk`; releases before the rename use `AgentHQ-`). */
    object AndroidApk : AssetKind {
        private val NAME = Regex("""^(?:InfantryAI|AgentHQ)-(${SemVer.FILE_VERSION})(?:-debug)?\.apk$""", RegexOption.IGNORE_CASE)
        override fun pick(assets: List<ReleaseAsset>, latest: SemVer): ReleaseAsset? {
            val apks = assets.filter { NAME.matches(it.name) }
            // A release-signed APK wins over a debug one of the same version: the two cannot replace each other.
            return apks.filter { versionOf(it.name) == latest }.minByOrNull { if ("-debug" in it.name.lowercase()) 1 else 0 }
                ?: apks.singleOrNull()
        }
        override fun versionOf(fileName: String) = NAME.matchEntire(fileName)?.let { SemVer.parse(it.groupValues[1]) }
        override fun missing(where: String): UpdateFailure = UpdateFailure.NoAndroidAsset(where)
    }

    class ServerArchiveFor(private val platform: ServerArchive.Platform = ServerArchive.hostPlatform()) : AssetKind {
        override fun pick(assets: List<ReleaseAsset>, latest: SemVer) = ServerArchive.pick(assets, latest, platform)
        override fun versionOf(fileName: String): SemVer? =
            ServerArchive.parse(fileName)?.takeIf { it.platform == platform }?.version
        override fun missing(where: String): UpdateFailure = UpdateFailure.NoServerAsset(where, platform.toString())
    }
}
