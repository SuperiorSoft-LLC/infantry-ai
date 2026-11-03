// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.api.project

import kotlinx.serialization.Serializable

/**
 * RUNTIME-LOAD-3: the `project_config` request and the part of the `project` answer the Infantry_Settings daemon half reads. Field
 * order and defaults equal the daemon's own `ProjectConfigRequest` / `RoutingRule`, so what the tool sends and prints is byte-for-byte
 * what the old in-core MCP tool did. The answer's other `Project` fields are ignored.
 */
@Serializable
data class RoutingRuleRecord(
    val name: String,
    val weights: List<Int> = emptyList(),
    val ticketTypes: List<String> = emptyList(),
    val role: String? = null,
    val provider: String,
    val model: String,
    val effort: String? = null,
    val setRole: String? = null,
)

@Serializable
data class ProjectConfigPatch(
    val projectId: String,
    val laneCaps: Map<String, Int>? = null,
    val autoRemoveDoneWorkers: Boolean? = null,
    val routingRules: List<RoutingRuleRecord>? = null,
    val experiments: Map<String, String>? = null,
    val data: Map<String, String>? = null,
    val unsetData: List<String>? = null,
)

@Serializable
data class ProjectConfigView(
    val id: String,
    val laneCaps: Map<String, Int> = emptyMap(),
    val autoRemoveDoneWorkers: Boolean = false,
    val routingRules: List<RoutingRuleRecord> = emptyList(),
    val experiments: Map<String, String> = emptyMap(),
    val data: Map<String, String> = emptyMap(),
)
