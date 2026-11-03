// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.update

import java.io.File
import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * RELEASE-ASSETS-1: the GitHub release assets UPDATE-1's [Updater] expects, written to one folder for the owner to
 * upload by hand. Run by `:superiorSoftLibrary:infantryUi:releaseAssets` (a JavaExec of [main]). It never pushes, tags
 * or calls GitHub: publishing stays the owner's step (docs/INFANTRY-INSTALL.md).
 */
object ReleaseAssets {
    const val SUMS_FILE = "SHA256SUMS"
    const val NOTES_FILE = "RELEASE-NOTES.md"

    data class Written(val dir: File, val msi: File, val sums: File, val notes: File, val sha256: String)

    /** INSTALL-1's MSI version rule: MAJOR.MINOR.BUILD, 1..255 . 0..255 . 0..65535. Returns it trimmed, else throws. */
    fun requireVersion(raw: String?): String {
        val version = raw?.trim().orEmpty()
        require(version.isNotEmpty()) { "RELEASE-ASSETS-1: -PinfantryVersion is required (e.g. -PinfantryVersion=1.0.5)" }
        val parts = Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(version)?.groupValues?.drop(1)?.map { it.toIntOrNull() }
        val ok = parts != null && parts[0] in 1..255 && parts[1] in 0..255 && parts[2] in 0..65535
        require(ok) { "RELEASE-ASSETS-1: -PinfantryVersion=$version is not an MSI version (MAJOR.MINOR.BUILD, 1..255 . 0..255 . 0..65535)" }
        return version
    }

    fun msiName(version: String) = "InfantryAI-$version.msi"

    /** One `sha256sum`-format line: `<64 lowercase hex><two spaces><file name>`, what [Updater.parseChecksum] reads. */
    fun sumsLine(sha256: String, fileName: String): String = "$sha256  $fileName"

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Copies [msi] into [outDir] as `InfantryAI-<version>.msi` and writes `SHA256SUMS` and a `RELEASE-NOTES.md` stub. */
    fun write(msi: File, outDir: File, version: String, gitSha: String): Written {
        val v = requireVersion(version)
        require(msi.isFile) { "RELEASE-ASSETS-1: no installer at $msi; build it with packageReleaseMsi -PinfantryVersion=$v" }
        require(msi.name.equals(msiName(v), ignoreCase = true)) {
            "RELEASE-ASSETS-1: ${msi.name} is not ${msiName(v)}; the msi was built with another -PinfantryVersion"
        }
        outDir.mkdirs()
        val target = File(outDir, msiName(v))
        msi.copyTo(target, overwrite = true)
        val hash = sha256(target)
        val sums = File(outDir, SUMS_FILE).apply { writeText(sumsLine(hash, target.name) + "\n") }
        val notes = File(outDir, NOTES_FILE).apply {
            writeText(
                """
                |# Infantry AI $v
                |
                |Tag: `v$v` · Commit: `$gitSha`
                |
                |<!-- RELEASE-ASSETS-1 stub: replace with what changed. The app shows this text in its update dialog. -->
                |
                |- TODO
                |""".trimMargin(),
            )
        }
        return Written(outDir, target, sums, notes, hash)
    }
}

/** `args`: <msi file> <out dir> <version> <git short sha>. Exits non-zero with the reason on any refusal. */
fun main(args: Array<String>) {
    if (args.size != 4) {
        System.err.println("usage: ReleaseAssets <msi> <outDir> <version> <gitSha>")
        exitProcess(2)
    }
    val written = try {
        ReleaseAssets.write(File(args[0]), File(args[1]), args[2], args[3])
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(1)
    }
    println("RELEASE-ASSETS-1: wrote ${written.dir.absolutePath}")
    println("  ${written.msi.name}  (sha256 ${written.sha256})")
    println("  ${written.sums.name}")
    println("  ${written.notes.name}")
    println("Publishing is a manual step: see docs/INFANTRY-INSTALL.md, \"Publishing a release\".")
}
