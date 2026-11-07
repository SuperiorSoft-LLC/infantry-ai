// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugin.host

import com.superiorsoftllc.infantry.plugin.api.InjectDeclaration
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.PanelDeclaration
import com.superiorsoftllc.infantry.plugin.api.TabDeclaration
import kotlin.test.Test
import kotlin.test.assertEquals

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

    @Test fun anInjectIntoANodeOfAnotherModuleMakesAnEdge() {
        val byPrefix = ModuleManifest("Acme_Inj", "1.0.0", inject = listOf(InjectDeclaration("Infantry_Chat.toolbar", "x")))
        val byTab = ModuleManifest("Acme_Tab", "1.0.0", inject = listOf(InjectDeclaration("chat.main", "x")))
        val host = ModuleManifest("Infantry_Chat", "1.0.0", tabs = listOf(TabDeclaration("chat.main", "center")))
        val t = PluginTree(listOf(host, byPrefix, byTab))
        assertEquals(listOf("Infantry_Chat"), t.parentsOf("Acme_Inj"))
        assertEquals(listOf("Infantry_Chat"), t.parentsOf("Acme_Tab"))
    }

    @Test fun aShellNodeAnUninstalledParentAndASelfEdgeMakeNoEdge() {
        val m = ModuleManifest(
            "Acme_X", "1.0.0", parents = listOf("Acme_Gone", "Acme_X"),
            inject = listOf(InjectDeclaration("infantry.shell.sidebar", "x"), InjectDeclaration("Acme_X.own", "y")),
        )
        val t = PluginTree(listOf(m))
        assertEquals(emptyList(), t.parentsOf("Acme_X"))
        assertEquals(listOf("Acme_X"), t.roots())
    }

    @Test fun aCycleDropsTheSameEdgeEveryRun() {
        val a = ModuleManifest("Acme_A", "1.0.0", parents = listOf("Acme_B"))
        val b = ModuleManifest("Acme_B", "1.0.0", parents = listOf("Acme_A"))
        val first = PluginTree(listOf(a, b))
        val second = PluginTree(listOf(b, a))
        assertEquals(listOf("plugin parent cycle: Acme_A -> Acme_B -> Acme_A, edge Acme_B -> Acme_A ignored"), first.problems)
        assertEquals(first.problems, second.problems)
        assertEquals(listOf("Acme_B"), first.parentsOf("Acme_A"))
        assertEquals(emptyList(), first.parentsOf("Acme_B"))
        assertEquals(listOf("Acme_B"), first.roots())
    }
}
