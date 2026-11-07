// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * RUNNER-POOL-5 (docs/RUNNER-POOL.md section 7, "Audit"): the runner's own append-only log of every remote operation: who (the
 * caller seat, `orchestrator:<id>`, or `anonymous` for a pairing attempt), what (the link operation), the ticket it was for, and
 * the result (`ok` or `refused: <why>`). One JSON object per line in `<stateDir>/runner-link/audit.jsonl`; `infantry runner audit`
 * prints the newest. It never holds a credential or a code.
 */
class RunnerAudit(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {

    @Serializable
    data class Entry(val at: Long, val who: String, val op: String, val ticket: String? = null, val result: String, val from: String? = null)

    @Synchronized
    fun record(who: String, op: String, result: String, ticket: String? = null, from: String? = null) {
        val entry = Entry(clock(), who, op, ticket?.takeIf { it.isNotBlank() }, result, from)
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(json.encodeToString(Entry.serializer(), entry) + "\n")
        }.onFailure { System.err.println("[infantry] runner audit write failed: ${it.message}") }
    }

    /** The newest [limit] entries, oldest first. A line that does not parse is skipped. */
    fun tail(limit: Int): List<Entry> {
        if (!file.isFile) return emptyList()
        return file.readLines().takeLast(limit).mapNotNull { line -> runCatching { json.decodeFromString(Entry.serializer(), line) }.getOrNull() }
    }

    companion object {
        const val FILE = "audit.jsonl"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    }
}
