// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.harness

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Listed in src/test/resources/META-INF/services/com.superiorsoftllc.infantry.harness.HarnessPlugin so
 * the REAL ServiceLoader mechanism has something to find. Must stay public with a public no-arg constructor.
 * Not Claude, not Pi: a throwaway.
 */
class ServiceLoaderTestPlugin : HarnessPlugin {
    override val pluginId = PLUGIN_ID
    override val contractVersion = HARNESS_CONTRACT_VERSION

    override fun register(registrar: HarnessRegistrar) {
        registrar.register(object : Harness {
            override val id = HARNESS_ID
            override val capabilities = emptySet<HarnessCapability>()
            override fun events(request: HarnessRequest): Flow<HarnessEvent> =
                flowOf(HarnessEvent.TextDelta("from-service-loader"), HarnessEvent.Completion())
        })
    }

    companion object {
        const val PLUGIN_ID = "service-loader-test-plugin"
        const val HARNESS_ID = "service-loader-throwaway"
    }
}
