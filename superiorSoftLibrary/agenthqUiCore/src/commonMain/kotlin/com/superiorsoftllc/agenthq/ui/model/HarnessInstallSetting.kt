package com.superiorsoftllc.agenthq.ui.model

import com.superiorsoftllc.agenthq.ui.protocol.HarnessInstallStatus

/**
 * HARNESS-INSTALL-1 (contract 1.100.0): what the Configuration modal says about a harness CLI's install state. The
 * state is the DAEMON's own check on its own machine (never a stored flag, never this client's disk); the client only
 * words it and offers the matching button.
 */
object HarnessInstallSetting {
    const val SINCE_MAJOR = 1
    const val SINCE_MINOR = 100

    // HARNESS-PLUGIN-4 H5a: no fixed installable list here. The ids the daemon's installer can install are exactly the ids
    // in its report (the built-ins and every loaded harness module's `harness.cli.install`); git and node rows get no controls.

    fun statusText(s: HarnessInstallStatus): String = when (s.state) {
        "installed" -> "Installed" + versionSuffix(s)
        "outdated" -> "Outdated" + versionSuffix(s) + (s.wanted ?: s.latest)?.let { " (want $it)" }.orEmpty()
        "missing" -> "Not installed"
        "user-managed" -> if (s.installedVersion != null) "Your install: works" + versionSuffix(s) else "Your install: ${s.note ?: "the check failed"}"
        else -> "Found, but its version could not be read"
    }

    /** The button label for [s], or null when there is nothing to do (installed, or the user manages it). */
    fun action(s: HarnessInstallStatus): String? = when (s.state) {
        "missing" -> "Install"
        "outdated" -> "Update"
        else -> null
    }

    /** Whether the install action must refresh (`update`) rather than install. */
    fun isUpdate(s: HarnessInstallStatus): Boolean = s.state == "outdated"

    private fun versionSuffix(s: HarnessInstallStatus) = s.installedVersion?.let { " $it" }.orEmpty()
}
