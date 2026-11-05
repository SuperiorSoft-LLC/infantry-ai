// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.updater

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * UPDATER-1: the private-repo update token never enters the repo or a build. The repo and the built artifacts may name
 * where the token lives (env `INFANTRY_UPDATE_TOKEN`, keystore `update-token`), never hold one.
 *
 *  - Every tracked file and every jar / archive / installer under the build outputs is scanned for GitHub token shapes
 *    (`ghp_`, `gho_`, `ghu_`, `ghs_`, `ghr_`, `github_pat_`).
 *  - When `INFANTRY_UPDATE_TOKEN` is set in the test's environment (the owner's real token on their machine), the same
 *    files are scanned for that exact value too. The value is never printed: a hit names the file only.
 *  - The config's `toString` shows only `token=set` / `token=none`.
 */
class UpdateTokenGuardTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile && File(it, "plugins/official").isDirectory }
        ?: error("repo root not found from ${File("").absolutePath}")

    private val shapes = Regex("""\b(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{50,})\b""")

    private fun tracked(): List<File> {
        val p = ProcessBuilder("git", "ls-files", "-z").directory(root).redirectErrorStream(false).start()
        val out = p.inputStream.readBytes()
        check(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0) { "git ls-files failed" }
        return String(out, Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }.map { File(root, it) }.filter { it.isFile }
    }

    /** The build outputs that ship: jars, archives and installers under any `build/` folder of the repo (not caches). */
    private fun artifacts(): List<File> = root.walkTopDown()
        .onEnter { d -> d.name !in setOf(".git", ".gradle", "node_modules", ".worktrees", "kotlin", "tmp", "intermediates") }
        .filter { f -> f.isFile && "${File.separatorChar}build${File.separatorChar}" in f.path && f.extension.lowercase() in setOf("jar", "zip", "msi", "apk", "tgz", "properties") }
        .toList()

    /** Text of [file], or of each entry of a jar / zip / apk, as (name, bytes-as-latin1) pairs. Large binaries are skipped. */
    private fun texts(file: File): List<Pair<String, String>> {
        val ext = file.extension.lowercase()
        if (ext in setOf("jar", "zip", "apk")) {
            return runCatching {
                ZipFile(file).use { zip ->
                    zip.entries().asSequence().filter { !it.isDirectory && it.size in 0..MAX_ENTRY_BYTES }
                        .map { e -> "${file.path}!${e.name}" to String(zip.getInputStream(e).readBytes(), Charsets.ISO_8859_1) }
                        .toList()
                }
            }.getOrDefault(emptyList())
        }
        return if (file.length() <= MAX_FILE_BYTES) listOf(file.path to String(file.readBytes(), Charsets.ISO_8859_1)) else emptyList()
    }

    private fun scan(files: List<File>, label: String) {
        val secret = System.getenv(UpdateSettings.TOKEN_ENV)?.trim()?.takeIf { it.length >= MIN_SECRET_CHARS }
        val hits = mutableListOf<String>()
        for (f in files) for ((name, text) in texts(f)) {
            if (shapes.containsMatchIn(text)) hits += "$name (a GitHub token shape)"
            if (secret != null && secret in text) hits += "$name (the value of ${UpdateSettings.TOKEN_ENV})"
        }
        if (hits.isNotEmpty()) fail("$label holds an update token; remove it (the value is not shown):\n" + hits.joinToString("\n"))
    }

    @Test
    fun `no tracked file holds a GitHub token or the configured update token`() {
        val files = tracked()
        assertTrue(files.size > 100, "git ls-files found only ${files.size} files")
        scan(files, "the repo")
    }

    @Test
    fun `no built artifact holds a GitHub token or the configured update token`() {
        scan(artifacts(), "a build output")
    }

    @Test
    fun `the config prints only whether a token is set`() {
        val secret = "ghp_" + "x".repeat(36)
        val config = UpdateConfig("o/r", SemVer.parse("1.0.0")!!, visibility = UpdateVisibility.PRIVATE) { secret }
        assertEquals(secret, config.token)
        assertTrue("token=set" in config.toString() && secret !in config.toString())
        val public = UpdateConfig("o/r", SemVer.parse("1.0.0")!!) { secret }
        assertEquals(null, public.token, "a public source never reads its token")
        assertTrue("token=none" in public.toString())
    }

    private companion object {
        const val MAX_ENTRY_BYTES = 8L * 1024 * 1024
        const val MAX_FILE_BYTES = 64L * 1024 * 1024
        /** A shorter env value is not a real token (and would match by chance). */
        const val MIN_SECRET_CHARS = 20
    }
}
