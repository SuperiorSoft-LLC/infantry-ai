package com.superiorsoftllc.infantry.daemon.api.harness

import kotlinx.serialization.Serializable

/**
 * RUNTIME-LOAD-1: the `harness_install_status` / `harness_install` answers as a daemon plugin reads them. Same fields and
 * defaults as the wire types in `infantryProtocol` (HarnessInstall.kt, contract 1.100.0), which stay the wire's definition;
 * the API cannot reach that module, so the plugin decodes the same JSON into these (a parity test pins the two together).
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

@Serializable
data class HarnessInstallReport(
    val harnesses: List<HarnessInstallStatus>,
    val prefixDir: String,
    val npmPath: String? = null,
    val guidance: String? = null,
)

@Serializable
data class HarnessInstallOutcome(
    val id: String,
    val result: String,
    val version: String? = null,
    val message: String? = null,
)

@Serializable
data class HarnessInstallResult(
    val outcomes: List<HarnessInstallOutcome>,
    val log: List<String> = emptyList(),
    val report: HarnessInstallReport,
)
