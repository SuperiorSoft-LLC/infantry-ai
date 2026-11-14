// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.harness.install

import com.superiorsoftllc.infantry.daemon.api.downloads.DownloadRequest
import com.superiorsoftllc.infantry.daemon.api.downloads.Downloads
import com.superiorsoftllc.infantry.executables.ExecutableEnvironment
import com.superiorsoftllc.infantry.executables.HostOs
import com.superiorsoftllc.infantry.plugin.api.ProgressHandle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.zip.ZipInputStream

/**
 * ONBOARD-2: puts the official Node.js LTS runtime into the harness installer's own prefix dir, so a machine with no Node
 * can still install the harness CLIs (they are npm packages). No admin rights, no system change, no permission prompt:
 * the nodejs.org Windows zip is unpacked flat into [prefixDir], which is exactly npm's global layout on Windows
 * (`node.exe`, `npm.cmd` and `node_modules\npm` at the top). `npm install -g --prefix <prefixDir>` then puts each CLI's
 * shim beside `node.exe`, and npm's shims run the `node.exe` that sits next to them, so nothing needs to be on PATH.
 *
 * Why the zip and not winget or the MSI: Windows Sandbox and many clean PCs have no winget, and the MSI is a per-machine
 * install that needs an elevation prompt. The zip works on both with one click and nothing to confirm.
 *
 * Only Windows (x64, arm64) is installed automatically; elsewhere [supported] is false and the caller shows the guidance.
 * The archive is checked against the release's own SHASUMS256.txt before anything is unpacked.
 */
class NodeRuntimeInstaller(
    private val prefixDir: File,
    private val environment: ExecutableEnvironment,
    private val downloads: () -> Downloads,
    /** The release index (nodejs.org/dist by default; a setting or a test may point elsewhere). */
    private val distBase: String = DIST_BASE,
    private val arch: String = System.getProperty("os.arch").orEmpty(),
) {
    val supported: Boolean get() = environment.os == HostOs.WINDOWS && platform() != null

    /** `win-x64` / `win-arm64`, or null when this machine has no automatic install. */
    private fun platform(): String? = when (arch.lowercase()) {
        "amd64", "x86_64", "x64" -> "win-x64"
        "aarch64", "arm64" -> "win-arm64"
        else -> null
    }

    /**
     * Downloads and unpacks the newest Node.js LTS. Returns null on success, else the plain reason it failed. [progress]
     * gets the steps (it is the caller's task: the caller ends it).
     */
    fun install(progress: ProgressHandle?, note: (String) -> Unit): String? {
        val platform = platform()
        if (environment.os != HostOs.WINDOWS || platform == null) return UNSUPPORTED
        if (!prefixDir.isDirectory && !prefixDir.mkdirs()) return "cannot create the install folder ${prefixDir.path}"
        val work = File(prefixDir, ".node-download").apply { mkdirs() }
        try {
            progress?.step(STEP_FIND, 0)
            note("[node] looking up the current Node.js LTS release")
            val indexFile = File(work, "index.json")
            val index = downloads().download(DownloadRequest("$distBase/index.json", indexFile, title = "Node.js release list", progress = progress))
            if (!index.ok) return "could not reach nodejs.org: ${index.error ?: "download failed"}"
            val version = latestLts(indexFile.readText(), platform) ?: return "nodejs.org lists no LTS release for $platform"
            val zipName = "node-$version-$platform.zip"

            val sums = File(work, "SHASUMS256.txt")
            val sumsResult = downloads().download(DownloadRequest("$distBase/$version/SHASUMS256.txt", sums, title = "Node.js checksums", progress = progress))
            if (!sumsResult.ok) return "could not read the Node.js checksums: ${sumsResult.error ?: "download failed"}"
            val sha = shaFor(sums.readText(), zipName) ?: return "the Node.js checksums do not list $zipName"

            progress?.step(STEP_DOWNLOAD, 1)
            note("[node] downloading $zipName")
            val zip = File(work, zipName)
            val got = downloads().download(DownloadRequest("$distBase/$version/$zipName", zip, title = "Node.js $version", sha256 = sha, progress = progress))
            if (!got.ok) return "the Node.js download failed: ${got.error ?: "unknown error"}"

            progress?.step(STEP_UNPACK, 2)
            note("[node] unpacking into ${prefixDir.path}")
            unpackFlat(zip, prefixDir, "node-$version-$platform/")
            if (!File(prefixDir, "node.exe").isFile || !File(prefixDir, "npm.cmd").isFile) return "the Node.js archive did not contain node.exe and npm.cmd"
            note("[node] Node.js $version is ready")
            return null
        } catch (e: Exception) {
            return "installing Node.js failed: ${e.message ?: e::class.simpleName}"
        } finally {
            work.deleteRecursively()
        }
    }

    companion object {
        const val DIST_BASE = "https://nodejs.org/dist"
        const val STEP_FIND = "Find the current release"
        const val STEP_DOWNLOAD = "Download Node.js"
        const val STEP_UNPACK = "Unpack"
        val STEPS = listOf(STEP_FIND, STEP_DOWNLOAD, STEP_UNPACK)
        const val UNSUPPORTED = "automatic Node.js install is only available on Windows; install Node.js 20 or newer from https://nodejs.org"
        private val json = Json { ignoreUnknownKeys = true }

        /** The newest release in nodejs.org's `index.json` that is an LTS and ships a [platform] zip (`win-x64` -> `win-x64-zip`). */
        fun latestLts(indexJson: String, platform: String): String? {
            val releases = json.parseToJsonElement(indexJson) as? JsonArray ?: return null
            return releases.asSequence().mapNotNull { it as? JsonObject }.firstOrNull { r ->
                // `"lts": false` for a current release, `"lts": "<codename>"` for an LTS one.
                val isLts = (r["lts"] as? JsonPrimitive)?.isString == true
                val files = (r["files"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
                isLts && "$platform-zip" in files
            }?.get("version")?.jsonPrimitive?.content?.takeIf { VERSION.matches(it) }
        }

        /** The hex hash SHASUMS256.txt gives [file] (`<hash>  <file>` lines). */
        fun shaFor(sums: String, file: String): String? =
            sums.lineSequence().map { it.trim().split(Regex("\\s+")) }.firstOrNull { it.size == 2 && it[1] == file }?.get(0)?.takeIf { SHA.matches(it) }

        /** Unpacks [zip] into [dest], dropping the archive's [root] folder; refuses an entry that would land outside [dest]. */
        fun unpackFlat(zip: File, dest: File, root: String) {
            val base = dest.canonicalFile
            ZipInputStream(zip.inputStream().buffered()).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    val raw = entry.name.replace('\\', '/')
                    if (!raw.startsWith(root)) continue
                    val name = raw.removePrefix(root)
                    if (name.isEmpty()) continue
                    val out = File(base, name).canonicalFile
                    require(out.path.startsWith(base.path + File.separator)) { "unsafe path in the Node.js archive: ${entry.name}" }
                    if (entry.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zin.copyTo(it) }
                    }
                }
            }
        }

        private val VERSION = Regex("""v\d+\.\d+\.\d+""")
        private val SHA = Regex("[0-9a-fA-F]{64}")
    }
}
