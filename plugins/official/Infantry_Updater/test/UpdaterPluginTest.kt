// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.updater

import com.superiorsoftllc.infantry.ui.shell.SELF_UPDATE_REGISTRY
import com.superiorsoftllc.infantry.ui.shell.SelfUpdateProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** UPDATER-1: the UI half fills the self-update registry it declares; off Android its provider offers nothing. */
class UpdaterPluginTest {
    @Test
    fun `the module registers the android self-update it declares, and the desktop provider offers nothing`() {
        val module = InfantryUpdater
        assertTrue(module.manifest.implementations.any { it.registry == SELF_UPDATE_REGISTRY && it.key == ANDROID_KEY })
        val provider = assertIs<SelfUpdateProvider>(assertNotNull(module.implementations[SELF_UPDATE_REGISTRY]?.get(ANDROID_KEY)))
        assertEquals(null, provider.entry("ws://127.0.0.1:0/ws", null, com.superiorsoftllc.infantry.ui.shell.defaultPlatformHost()))
    }
}
