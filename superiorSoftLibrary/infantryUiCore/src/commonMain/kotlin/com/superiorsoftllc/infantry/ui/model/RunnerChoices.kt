// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * RUNNER-PICKER-1: one entry of a runner picker. [value] is what is stored/sent: "" = project default (stores nothing),
 * `local`, `any`, or a registered runner's name. [status] is the registry's word for a registered runner (null for the
 * fixed entries); an entry that is not [selectable] shows greyed with its status.
 */
data class RunnerChoice(val value: String, val label: String, val status: String? = null, val selectable: Boolean = true)

/** The `runner_admin` `list` answer, reduced to what a picker needs. */
data class RunnerStatus(val name: String, val health: String, val paused: Boolean)

object RunnerChoices {
    const val PROJECT_DEFAULT = ""
    const val FRAME = "runner_admin"
    private val lenient = Json { ignoreUnknownKeys = true }
    private val ONLINE = setOf("up", "online", "ok", "healthy", "ready")

    /** `runner_admin` op `list` request data. */
    val listRequest: String = JsonObject(mapOf("op" to JsonPrimitive("list"), "name" to JsonPrimitive(""))).toString()

    /** The registered runners of a `list` answer (the built-in `local` row excluded); empty for anything that is not an ok answer. */
    fun parse(text: String): List<RunnerStatus> {
        val obj = runCatching { lenient.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyList()
        val rows = obj["runners"] as? JsonArray ?: return emptyList()
        if ((obj["ok"] as? JsonPrimitive)?.contentOrNull != "true") return emptyList()
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return rows.mapNotNull { it as? JsonObject }.map { RunnerStatus(it.s("name"), it.s("health"), it.s("paused") == "true") }
            .filter { it.name.isNotBlank() && it.name != "local" }
    }

    /** Project default, This computer, Any available runner, then one entry per registered runner. */
    fun options(registered: List<RunnerStatus>): List<RunnerChoice> =
        listOf(
            RunnerChoice(PROJECT_DEFAULT, "Project default"),
            RunnerChoice("local", "This computer (local)"),
            RunnerChoice("any", "Any available runner"),
        ) + registered.map { r ->
            val status = if (r.paused) "paused" else if (r.health.lowercase() in ONLINE) "online" else "offline"
            RunnerChoice(r.name, r.name, status, selectable = status == "online")
        }

    fun labelFor(options: List<RunnerChoice>, value: String): String =
        options.firstOrNull { it.value == value }?.label ?: value
}
