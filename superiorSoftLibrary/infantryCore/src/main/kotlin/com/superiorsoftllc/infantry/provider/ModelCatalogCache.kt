// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.provider

import com.superiorsoftllc.infantry.protocol.ProviderModels
import com.superiorsoftllc.infantry.settings.LimitRegistry
import java.util.concurrent.Executor

/** BUG MODEL-CATALOG-1: one source's model list as the last listing left it ([CachedListing] of model rows). */
typealias CachedCatalog = CachedListing<ProviderModels>

/**
 * BUG MODEL-CATALOG-1: the `models` answer must never wait on a harness's own CLI. The rules live in [ListingCache] (MCP-REGISTRY-1
 * moved them there unchanged so the MCP server lists reuse them); this cache adds the model bounds and the picker's note.
 */
class ModelCatalogCache(
    ttlMs: () -> Long = { LimitRegistry.MODELS_CATALOG_TTL_MS.default },
    waitMs: () -> Long = { LimitRegistry.MODELS_CATALOG_WAIT_MS.default },
    clock: () -> Long = System::currentTimeMillis,
    executor: Executor = ListingCache.REFRESHER,
) : ListingCache<ProviderModels>(ttlMs, waitMs, clock, executor) {
    /** What the answer says about [id] beside [cached]; null when its list is current. */
    fun noteFor(id: String, cached: CachedCatalog): String? = when {
        cached.rows == null && cached.error != null -> "could not list models: ${cached.error}"
        cached.rows == null -> "$id has not listed its models yet; either wait for that listing (limit " +
            "${LimitRegistry.MODELS_CATALOG_WAIT_MS.key}) or type the model id its --model accepts"
        cached.ageMs == null -> null
        cached.listing -> "showing the list from ${ageText(cached.ageMs)} ago; refreshing now"
        cached.error != null -> "showing the list from ${ageText(cached.ageMs)} ago: the last refresh failed: ${cached.error}"
        else -> null
    }

}
