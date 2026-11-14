// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.protocol

import kotlinx.serialization.Serializable

/**
 * Contract 1.100.0 (HARNESS-INSTALL-1): one harness CLI as the built-in installer sees it.
 *
 *  - [state]: `installed` | `missing` | `outdated` (installed, but older than the pin / the registry's latest) | `unknown`
 *    (a CLI was found but its version could not be read).
 *  - [path]: the executable a launch uses right now; null = missing. [managed] = it lives in the app's own prefix dir.
 *  - [wanted]: the version an install would put there (the pin, or null = whatever npm's `latest` is).
 *  - [latest]: npm's current version; only filled when the request asked to [HarnessInstallRequest.checkLatest].
 */
@Serializable
data class HarnessInstallStatus(
    val id: String,
    val label: String,
    val npmPackage: String,
    val state: String,
    val installedVersion: String? = null,
    val path: String? = null,
    val managed: Boolean = false,
    val wanted: String? = null,
    val latest: String? = null,
    val note: String? = null,
)

/** The `harness_install_status` answer: every installable harness, the app prefix dir, and what npm needs. */
@Serializable
data class HarnessInstallReport(
    val harnesses: List<HarnessInstallStatus>,
    val prefixDir: String,
    /** The npm executable the installer would run; null = Node/npm not found, then [guidance] says what to do. */
    val npmPath: String? = null,
    val guidance: String? = null,
    /** ONBOARD-2: npm is missing AND this daemon can install the Node.js runtime itself (an install puts it in first). */
    val nodeInstallable: Boolean = false,
)

/** The `harness_install_status` payload (empty data = defaults). */
@Serializable
data class HarnessInstallStatusRequest(val checkLatest: Boolean = false) {
    companion object {
        fun decode(text: String): HarnessInstallStatusRequest =
            if (text.isBlank()) HarnessInstallStatusRequest() else StrictJson.decode(serializer(), text, "harness_install_status payload")
    }
}

/**
 * The `harness_install` payload. [ids] empty = every harness that is missing (or, with [update], every one that is
 * outdated too). Naming an id installs/updates exactly that one. Installs are idempotent: an up-to-date CLI is skipped.
 */
@Serializable
data class HarnessInstallRequest(
    val ids: List<String> = emptyList(),
    val update: Boolean = false,
    /** ONBOARD-2: install only the Node.js runtime (sent only when the report says [HarnessInstallReport.nodeInstallable]). */
    val runtime: Boolean = false,
) {
    companion object {
        fun decode(text: String): HarnessInstallRequest =
            if (text.isBlank()) HarnessInstallRequest() else StrictJson.decode(serializer(), text, "harness_install payload")
    }
}

/** One harness's outcome: `installed` | `updated` | `skipped` (already satisfied) | `failed` ([message] says why). */
@Serializable
data class HarnessInstallOutcome(
    val id: String,
    val result: String,
    val version: String? = null,
    val message: String? = null,
)

/** The `harness_install_result` answer: per-harness outcomes, the npm log (capped), and the status after the run. */
@Serializable
data class HarnessInstallResult(
    val outcomes: List<HarnessInstallOutcome>,
    val log: List<String> = emptyList(),
    val report: HarnessInstallReport,
)
