// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.ProviderInfo
import com.superiorsoftllc.infantry.protocol.ProviderModels
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * BUG-ASSIGN-1: what an agent may pin on a ticket -- every harness (= provider id) the daemon can run, and each one's
 * models -- shared by `infantry harnesses` and the MCP `infantry_list_harnesses`, so both read the same list.
 *
 * The `providers` answer is the authority on WHICH ids exist (configured providers and registry harnesses alike, the
 * default first, each with its CLI's availability) and on a provider's own model list, the one the daemon checks a
 * ticket's model against when the provider lists models. The `models` catalog fills in the harnesses that have no list
 * of their own there (a registry harness the daemon adopts on demand). A catalog that cannot be read leaves those without
 * a list and says why; it never hides a harness. Existing frames only: no contract bump.
 */
internal object HarnessCatalog {

    data class Harness(
        val id: String,
        val default: Boolean,
        val defaultModel: String?,
        /** The models to pin; null = none listed (the harness takes any model id its CLI accepts). */
        val models: List<String>?,
        val available: Boolean,
        val unavailableReason: String?,
        /** What the daemon said about this harness's models (a last-known list, a listing that failed), if anything. */
        val note: String?,
        /** CONTEXT-LIMIT-1: how a contextLimit is honoured here (`supported` / `best_effort` / `unsupported`) and how; null = older daemon. */
        val contextLimit: String? = null,
        val contextLimitHow: String? = null,
        /** EFFORT-1: how a reasoning effort is honoured here (`supported` / `best_effort` / `unsupported`); null = older daemon. */
        val effort: String? = null,
        val effortHow: String? = null,
    )

    suspend fun read(stream: FrameStream): List<Harness> {
        val providers = stream.request(Envelope(Envelope.PROVIDERS), answerType = Envelope.PROVIDERS, what = "providers", idempotent = true)
            .let { json.decodeFromString(ListSerializer(ProviderInfo.serializer()), it.data) }
        var catalogError: String? = null
        val catalog = try {
            stream.request(Envelope(Envelope.MODELS), answerType = Envelope.MODELS, what = "models", idempotent = true)
                .let { json.decodeFromString(ListSerializer(ProviderModels.serializer()), it.data) }
        } catch (e: Exception) {
            catalogError = e.message ?: e.javaClass.simpleName
            emptyList()
        }
        return com.superiorsoftllc.infantry.daemon.api.harness.HarnessCatalogText.merge(
            providers.map { json.decodeFromString(com.superiorsoftllc.infantry.daemon.api.harness.ProviderInfo.serializer(), json.encodeToString(ProviderInfo.serializer(), it)) },
            catalog.map { json.decodeFromString(com.superiorsoftllc.infantry.daemon.api.harness.ProviderModels.serializer(), json.encodeToString(ProviderModels.serializer(), it)) },
            catalogError,
        ).map { Harness(it.id, it.default, it.defaultModel, it.models, it.available, it.unavailableReason, it.note, it.contextLimit, it.contextLimitHow, it.effort, it.effortHow) }
    }

    /** How a listed harness is used, said the same way by the CLI and the MCP tool. */
    const val HOW_TO_PIN = com.superiorsoftllc.infantry.daemon.api.harness.HarnessCatalogText.HOW_TO_PIN

    private val json = Json { ignoreUnknownKeys = true }
}
