// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugin.host

import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.PanelDeclaration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** PLUGIN-TREE-UI-1: the parent relation comes from slots and `parents`, never from a table of ids. */
class PluginTreeTest {
    private val chat = ModuleManifest("Infantry_Chat", "1.0.0", slots = listOf("chat.feed"))
    private val feed = ModuleManifest("Infantry_ChatFeed", "1.0.0", panels = listOf(PanelDeclaration("infantry.chatfeed", "chat.feed")))
    private val flat = ModuleManifest("Infantry_Flat", "1.0.0")

    @Test fun aSlotFillerIsADirectChildOfTheSlotOwner() {
        val t = PluginTree(listOf(chat, feed, flat))
        assertEquals(listOf("Infantry_ChatFeed"), t.childrenOf("Infantry_Chat"))
        assertEquals(listOf("Infantry_Chat"), t.parentsOf("Infantry_ChatFeed"))
        assertEquals(emptyList(), t.childrenOf("Infantry_Flat"))
        assertEquals(listOf("Infantry_Chat", "Infantry_Flat"), t.roots())
    }

    @Test fun aDeclaredParentAppearsWithNoCodeChange() {
        val extra = ModuleManifest("Acme_Sub", "1.0.0", parents = listOf("Infantry_Flat"))
        val grand = ModuleManifest("Acme_Grand", "1.0.0", parents = listOf("Acme_Sub"))
        val t = PluginTree(listOf(chat, feed, flat, extra, grand))
        assertEquals(listOf("Acme_Sub"), t.childrenOf("Infantry_Flat"))
        assertEquals(listOf("Acme_Grand"), t.childrenOf("Acme_Sub"))
        assertEquals(false, "Acme_Grand" in t.childrenOf("Infantry_Flat"))
    }

    @Test fun aSharedChildStaysOnWhileAnyParentIsEnabled() {
        val shared = ModuleManifest("Acme_Shared", "1.0.0", parents = listOf("Infantry_Chat", "Infantry_Flat"))
        val t = PluginTree(listOf(chat, flat, shared))
        assertNotNull(t.disableRefusal("Acme_Shared") { it == "Infantry_Chat" })
        assertNull(t.disableRefusal("Acme_Shared") { false })
        // A child with one parent is not shared: it can be switched off on its own.
        assertNull(PluginTree(listOf(chat, feed)).disableRefusal("Infantry_ChatFeed") { true })
    }
}
