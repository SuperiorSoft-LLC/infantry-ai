// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.harness

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * HARNESS-PLUGIN-1: the plugin [JarHarnessPluginSourceTest] packs into a jar at test time and loads from OUTSIDE the class
 * path (its parent loader hides every class named `JarSourceTestPlugin*`). Deliberately NOT listed in the test
 * META-INF/services, so only the jar source can find it. Public, public no-arg constructor, a named harness class (an
 * anonymous one would be one more class file to pack).
 */
class JarSourceTestPlugin : HarnessPlugin {
    override val pluginId = PLUGIN_ID
    override val contractVersion = HARNESS_CONTRACT_VERSION

    override fun register(registrar: HarnessRegistrar) = registrar.register(Echo())

    class Echo : Harness {
        override val id = HARNESS_ID
        override val capabilities = emptySet<HarnessCapability>()
        override fun events(request: HarnessRequest): Flow<HarnessEvent> =
            flowOf(HarnessEvent.TextDelta("from-jar:${request.prompt}"), HarnessEvent.Completion())
    }

    companion object {
        const val PLUGIN_ID = "jar-source-test-plugin"
        const val HARNESS_ID = "jar-throwaway"
    }
}
