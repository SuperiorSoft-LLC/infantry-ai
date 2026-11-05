// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.updater

import java.io.File
import java.security.MessageDigest

/**
 * UPDATER-1: the release-file names and checksum helpers the updater reads. A plugin cannot link the core's release
 * tooling (`com.superiorsoftllc.infantry.update.ReleaseAssets`, which writes these files at release time), so the reader
 * keeps its own copy of the format: `SHA256SUMS` in `sha256sum` form and `RELEASE-NOTES.md`.
 */
object ReleaseAssets {
    const val SUMS_FILE = "SHA256SUMS"
    const val NOTES_FILE = "RELEASE-NOTES.md"

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
}
