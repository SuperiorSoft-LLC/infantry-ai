// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.model

import com.superiorsoftllc.infantry.ui.protocol.CliLocation
import com.superiorsoftllc.infantry.ui.protocol.HarnessInstallReport
import com.superiorsoftllc.infantry.ui.protocol.HarnessInstallStatus
import com.superiorsoftllc.infantry.ui.protocol.ProviderInfo
import com.superiorsoftllc.infantry.ui.protocol.SecretRow

/**
 * FIRST-RUN-HARNESS-CHECKLIST-1: the one list of agent tools that Configuration > Agents and the first-run checklist both draw.
 * Every input is the DAEMON's answer; nothing here names a harness:
 *  - the harnesses are every id in its install report AND its provider list (built-ins, descriptor and plugin harnesses);
 *  - installed = the installer found it (report), else the CLI resolved (`cli_locations`), else the provider is available;
 *  - credentials are the harness's declared secrets (`harness.<id>.key` and `harness.<id>.<credId>.key`, API keys only),
 *    listed ONLY for an installed harness (a tool that is not there is never asked for a key).
 */
object HarnessChecklist {
    /** How a credential stands: green for the first three, red for [NEEDS_SETUP]. */
    enum class Auth(val text: String, val ok: Boolean) {
        SIGNED_IN("Signed in", true),
        KEY_SET("API key set", true),
        SHARED("Uses a shared secret", true),
        NEEDS_SETUP("Needs setup", false),
    }

    data class Credential(val row: SecretRow, val primary: Boolean, val auth: Auth) {
        /** "Claude Code API key" stays as the daemon words it; a sign-in harness reads "Claude Code sign-in". */
        val label: String get() = if (primary && SUBSCRIPTION in row.authModes) row.label.removeSuffix(" API key") + " sign-in" else row.label
    }

    data class Item(
        val id: String,
        val label: String,
        val installed: Boolean,
        /** "Installed 2.1.3", "Outdated 1.0 (want 1.2)", "Not installed". */
        val status: String,
        /** The installer's row: non-null = the built-in installer can install / update it. */
        val check: HarnessInstallStatus?,
        val location: CliLocation?,
        /** For a missing tool the installer cannot install: what to do instead. */
        val howTo: String?,
        val credentials: List<Credential>,
    )

    private const val SUBSCRIPTION = "subscription-login"
    private const val LOGIN_DONE = "done"

    fun items(
        report: HarnessInstallReport?,
        providers: List<ProviderInfo>,
        locations: List<CliLocation>?,
        secrets: List<SecretRow>?,
    ): List<Item> {
        val ids = (report?.harnesses.orEmpty().map { it.id } + providers.map { it.id }).distinct()
        return ids.map { id ->
            val check = report?.harnesses?.firstOrNull { it.id == id }
            val location = locations?.firstOrNull { it.id == id }
            val provider = providers.firstOrNull { it.id == id }
            val installed = when {
                check != null -> SetupWizard.installed(check)
                location != null -> location.path != null
                else -> provider?.available ?: false
            }
            val label = check?.label?.takeIf { it.isNotBlank() } ?: location?.label?.takeIf { it.isNotBlank() } ?: id
            val status = when {
                check != null && installed -> HarnessInstallSetting.statusText(check)
                installed -> "Installed"
                else -> "Not installed"
            }
            val howTo = if (installed || check != null) null else
                (provider?.unavailableReason ?: location?.reason)?.let { "$it. " }.orEmpty() + "Install $label yourself, then press Check again, or set where it is below."
            Item(id, label, installed, status, check, location, howTo, if (installed) credentials(id, secrets.orEmpty()) else emptyList())
        }
    }

    /** The API-key credentials harness [id] declares, primary first. */
    fun credentials(id: String, secrets: List<SecretRow>): List<Credential> {
        val primary = "harness.$id.key"
        return secrets
            .filter { it.forMode.isBlank() && (it.name == primary || (it.name.startsWith("harness.$id.") && it.name.endsWith(".key") && '.' !in it.name.removePrefix("harness.$id.").removeSuffix(".key"))) }
            .sortedBy { if (it.name == primary) 0 else 1 }
            .map { Credential(it, it.name == primary, auth(it)) }
    }

    fun auth(row: SecretRow): Auth = when {
        (row.signedIn == "true" || row.loginState == LOGIN_DONE) && (row.authMode == SUBSCRIPTION || !row.set) -> Auth.SIGNED_IN
        row.set || row.overriddenByEnv -> Auth.KEY_SET
        row.usesSecret.isNotBlank() -> Auth.SHARED
        else -> Auth.NEEDS_SETUP
    }

    /** "You have Claude Code installed, OpenCode installed and Pi installed. Not installed: Foo." */
    fun summary(items: List<Item>): String {
        if (items.isEmpty()) return "The daemon has not listed any agent tools yet."
        val have = items.filter { it.installed }.map { "${it.label} installed" }
        val missing = items.filter { !it.installed }.map { it.label }
        val first = if (have.isEmpty()) "No agent tool is installed yet." else "You have ${join(have)}."
        return if (missing.isEmpty()) first else "$first Not installed: ${join(missing)}."
    }

    private fun join(parts: List<String>): String =
        if (parts.size <= 1) parts.joinToString() else parts.dropLast(1).joinToString(", ") + " and " + parts.last()

    /** The ui pref that records the first-run checklist was shown on this device (kept across app updates). */
    const val SEEN_PREF = "harness.checklist.seen"
}
