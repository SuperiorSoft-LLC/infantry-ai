// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.keystore

import com.superiorsoftllc.infantry.harness.BoundedCommand
import com.superiorsoftllc.infantry.settings.LimitRegistry
import java.io.File
import java.util.Base64

/** VAULT-REVEAL-1: what one OS user verification came to. [method] names how (windows-hello, windows-password); [detail] why not. */
data class Verification(val outcome: Outcome, val method: String, val detail: String = "") {
    enum class Outcome { VERIFIED, DENIED, UNAVAILABLE }

    val verified: Boolean get() = outcome == Outcome.VERIFIED

    companion object {
        fun unavailable(detail: String) = Verification(Outcome.UNAVAILABLE, "", detail)
    }
}

/**
 * VAULT-REVEAL-1: the port that asks the person at this computer to prove they are the signed-in OS user (Windows Hello,
 * later Touch ID / polkit), before the vault hands a stored value to the owner's window. A platform plugs in here; the vault
 * never knows which one answered. [verify] blocks until the person answers or the prompt fails; it never throws.
 */
interface OsUserVerifier {
    /** Shown to the owner ("Windows Hello"). */
    val label: String

    /** What a [verify] would use now, without showing any prompt; [Verification.Outcome.UNAVAILABLE] when none can be shown. */
    fun check(): Verification

    /** Shows the OS prompt with [reason] and waits for the person. */
    fun verify(reason: String): Verification
}

/**
 * VAULT-REVEAL-1: the platform verifiers. Windows is the first one. macOS (LocalAuthentication) and Linux (polkit) answer
 * "not available yet" with a plain message, so reveal, copy and export are refused there rather than unprotected.
 */
object OsUserVerifiers {
    fun detect(os: String = System.getProperty("os.name").orEmpty().lowercase(), timeoutMs: () -> Long = { DEFAULT_TIMEOUT_MS }): OsUserVerifier = when {
        "win" in os -> Windows(timeoutMs)
        "mac" in os -> NotYet("macOS Touch ID", "Reveal and copy need macOS Touch ID / password verification, which is not available yet.")
        else -> NotYet("OS user verification", "Reveal and copy need OS user verification, which is not available on this system yet.")
    }

    /** The prompt's wait when no setting is passed: `limits.secrets.verifyTimeoutSeconds`'s default. */
    val DEFAULT_TIMEOUT_MS: Long get() = LimitRegistry.SECRET_VERIFY_TIMEOUT_SECONDS.default * 1_000L

    /** A platform with no verifier yet: every call says so, nothing is ever verified. */
    class NotYet(override val label: String, private val message: String) : OsUserVerifier {
        override fun check() = Verification.unavailable(message)
        override fun verify(reason: String) = Verification.unavailable(message)
    }

    /**
     * Windows: Windows Hello (WinRT `UserConsentVerifier`: PIN, fingerprint or face), else the Windows credential prompt
     * (`CredUIPromptForWindowsCredentials` on the current user, the password checked with `LogonUser`).
     *
     * Design: the JVM has no WinRT binding, and JNA would add a native dependency to core for one call. Windows PowerShell 5.1
     * ships on every Windows 10/11 and reaches both WinRT and Win32 (Add-Type), the same way [OsVaults.Dpapi] reaches DPAPI.
     * The helper is a resource script run as an argv list (never a shell string), its one input (the prompt line) base64 on the
     * argument list, its answer one line on stdout. The password stays inside that child process and is never printed.
     * The Hello call has no window handle; where Windows refuses it to a desktop process, the helper falls back to the
     * credential prompt, so a verification is always offered on a desktop session.
     */
    class Windows(private val timeoutMs: () -> Long = { DEFAULT_TIMEOUT_MS }) : OsUserVerifier {
        override val label = "Windows Hello"

        override fun check(): Verification = run("check", "")

        override fun verify(reason: String): Verification = run("verify", reason)

        private fun run(mode: String, reason: String): Verification {
            val script = runCatching { scriptFile() }.getOrElse { return Verification.unavailable("the verification helper is missing") }
            return try {
                val reason64 = Base64.getEncoder().encodeToString(reason.toByteArray(Charsets.UTF_8))
                val out = BoundedCommand.runBounded(
                    listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.absolutePath, mode, reason64),
                    timeoutMs = timeoutMs(),
                )
                parse(out)
            } catch (e: Exception) {
                Verification.unavailable("the verification prompt did not answer (${e.message ?: e::class.simpleName})")
            } finally {
                script.delete()
            }
        }

        private fun scriptFile(): File {
            val text = OsUserVerifiers::class.java.getResourceAsStream(SCRIPT)?.use { it.readBytes() } ?: error("missing $SCRIPT")
            return File.createTempFile("infantry-verify-", ".ps1").apply { deleteOnExit(); writeBytes(text) }
        }
    }

    private const val SCRIPT = "/com/superiorsoftllc/infantry/keystore/os-user-verify.ps1"

    /** The helper's one-line answer (its last non-blank line), see the script's header. Anything else is unavailable. */
    fun parse(out: String): Verification {
        val line = out.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty()
        val parts = line.split(':', limit = 3)
        return when (parts.firstOrNull()) {
            "verified", "available" -> Verification(Verification.Outcome.VERIFIED, parts.getOrElse(1) { "" })
            "denied" -> Verification(Verification.Outcome.DENIED, parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" })
            "unavailable" -> Verification.unavailable(line.substringAfter(':'))
            else -> Verification.unavailable("the verification helper gave no answer")
        }
    }
}
