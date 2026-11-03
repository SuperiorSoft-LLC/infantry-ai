// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.keystore

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * SECRETS-1: the OS facility that holds the keystore's master key, reached through the platform's own tool (the JVM has no
 * native binding for it). The key goes in on stdin where the tool allows it. A vault that is absent or fails answers
 * null / false: the [Keystore] then falls back to its key file.
 */
object OsVaults {
    fun detect(dir: File, os: String = System.getProperty("os.name").orEmpty().lowercase()): OsVault? = when {
        "win" in os -> Dpapi(File(dir, "keystore.key.dpapi"))
        "mac" in os -> MacKeychain
        "nux" in os -> SecretService
        else -> null
    }

    private fun run(argv: List<String>, stdin: String?): String? = runCatching {
        val p = ProcessBuilder(argv).apply { redirectError(ProcessBuilder.Redirect.DISCARD) }.start()
        p.outputStream.bufferedWriter().use { if (stdin != null) it.write(stdin) }
        if (!p.waitFor(15, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return@runCatching null
        }
        if (p.exitValue() != 0) null else p.inputStream.bufferedReader().readText().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** Windows: the master key wrapped by DPAPI (current-user scope, .NET ProtectedData) in a file only this user can unwrap. */
    class Dpapi(private val blob: File) : OsVault {
        override val label = "Windows DPAPI"

        private fun transform(method: String, stdin: String): String? = run(
            listOf(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command",
                "Add-Type -AssemblyName System.Security; \$b=[Convert]::FromBase64String([Console]::In.ReadToEnd().Trim()); " +
                    "[Convert]::ToBase64String([Security.Cryptography.ProtectedData]::$method(\$b,\$null,'CurrentUser'))",
            ),
            stdin,
        )

        override fun fetch(): String? = if (blob.isFile) transform("Unprotect", blob.readText()) else null

        override fun store(value: String): Boolean {
            val wrapped = transform("Protect", value) ?: return false
            blob.parentFile?.mkdirs()
            blob.writeText(wrapped)
            return true
        }
    }

    private object MacKeychain : OsVault {
        override val label = "macOS Keychain"
        override fun fetch() = run(listOf("security", "find-generic-password", "-s", "infantry-keystore", "-a", "master", "-w"), null)

        // `security` has no stdin form for -w, so the value rides the argument list once. It is only the master key of an
        // encrypted file, and never a user secret.
        override fun store(value: String) =
            run(listOf("security", "add-generic-password", "-U", "-s", "infantry-keystore", "-a", "master", "-w", value), null) != null || fetch() == value
    }

    private object SecretService : OsVault {
        override val label = "Linux Secret Service"
        override fun fetch() = run(listOf("secret-tool", "lookup", "service", "infantry-keystore"), null)

        override fun store(value: String): Boolean = runCatching {
            val p = ProcessBuilder("secret-tool", "store", "--label=Infantry AI keystore", "service", "infantry-keystore").apply {
                redirectError(ProcessBuilder.Redirect.DISCARD)
                redirectOutput(ProcessBuilder.Redirect.DISCARD)
            }.start()
            p.outputStream.bufferedWriter().use { it.write(value) }
            p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0
        }.getOrDefault(false)
    }
}
