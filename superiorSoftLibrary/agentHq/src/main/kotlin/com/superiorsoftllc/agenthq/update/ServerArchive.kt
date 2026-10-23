package com.superiorsoftllc.agenthq.update

/**
 * SERVER-UPDATE-1: the names of the headless server download, `InfantryAI-server-<x.y.z>-<os>-<arch>.{zip|tar.gz}`
 * (zip on Windows). Releases before the rename used the `AgentHQ-server-` prefix; both are accepted, the new one wins.
 */
object ServerArchive {
    /** The one place the prefix lives; `headlessBaseName` in agentHq/build.gradle.kts must match. */
    const val PREFIX = "InfantryAI-server"
    const val LEGACY_PREFIX = "AgentHQ-server"

    data class Platform(val os: String, val arch: String) {
        override fun toString() = "$os-$arch"
        val extension: String get() = if (os == "windows") "zip" else "tar.gz"
    }

    data class Parsed(val version: SemVer, val platform: Platform, val legacy: Boolean)

    private val NAME = Regex(
        """^(InfantryAI|AgentHQ)-server-(\d+\.\d+\.\d+)-(windows|linux|macos)-([a-z0-9_]+)\.(zip|tar\.gz)$""",
        RegexOption.IGNORE_CASE,
    )

    /** This machine's OS and CPU, spelled as `headlessDist` spells them (`x64` for amd64/x86_64). */
    fun hostPlatform(osName: String = System.getProperty("os.name"), osArch: String = System.getProperty("os.arch")): Platform {
        val os = osName.lowercase().let { if (it.startsWith("windows")) "windows" else if (it.startsWith("mac")) "macos" else "linux" }
        val arch = osArch.lowercase().let { if (it == "amd64" || it == "x86_64") "x64" else if (it == "aarch64") "arm64" else it }
        return Platform(os, arch)
    }

    fun fileName(version: SemVer, platform: Platform) = "$PREFIX-$version-$platform.${platform.extension}"

    fun parse(fileName: String): Parsed? {
        val m = NAME.matchEntire(fileName) ?: return null
        val platform = Platform(m.groupValues[3].lowercase(), m.groupValues[4].lowercase())
        // The extension must be the one this OS ships: a .zip for Linux, or a .tar.gz for Windows, is not ours.
        if (!fileName.endsWith("." + platform.extension, ignoreCase = true)) return null
        val version = SemVer.parse(m.groupValues[2]) ?: return null
        return Parsed(version, platform, legacy = m.groupValues[1].equals("AgentHQ", ignoreCase = true))
    }

    /** The archive for [platform]: the one named for [latest], else the only one the release carries; new prefix over legacy. */
    fun pick(assets: List<ReleaseAsset>, latest: SemVer, platform: Platform): ReleaseAsset? {
        val mine = assets.mapNotNull { a -> parse(a.name)?.takeIf { it.platform == platform }?.let { a to it } }
        val pool = mine.filter { !it.second.legacy }.ifEmpty { mine }
        return (pool.firstOrNull { it.second.version == latest } ?: pool.singleOrNull())?.first
    }
}
