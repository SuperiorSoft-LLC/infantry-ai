// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.policy

import com.superiorsoftllc.infantry.daemon.DemoPresetFixture
import com.superiorsoftllc.infantry.plugin.api.ActionPolicyMatcher
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * POLICY-1: the demo preset (plugin data) lets through EXACTLY the frames the pre-POLICY-1 DemoLock's hand lists did, so
 * moving the values out of core changed no demo behaviour.
 */
class DemoPresetParityTest {
    private val preset = DemoPresetFixture.preset

    @Test fun `the demo preset allows exactly the old allow-list`() {
        val allowed = ActionCatalog.frames.filter { f ->
            val a = ActionCatalog.forFrame(f)!!
            ActionPolicyMatcher.allows(preset.disable, preset.allow, a.id, a.kind)
        }.toSet()
        // Deliberate narrowing: usage_get (DEMO-USAGE-HIDE-1 17e14e91) carried the owner's plan meters and accounts.
        assertEquals(setOf("usage_get"), DemoPresetFixture.ALLOWED_TYPES - allowed, "the old lock allowed these, the preset refuses them")
        // Deliberate widenings since the pre-POLICY-1 lock, each a read or the visitor's own chat:
        //  plugin_tools     DEMO-FIX-1 e11ec0d3: lists the plugins' tools (a call is still gated by the tool's own action).
        //  timeline_list    DEMO-CHECK-1 91495a9f: demo.json allows timeline.read (read only) so the landing page stops showing a red error.
        //  layer_skill_list CATALOG: skills.read lists layer skills, a read like skill_list (already allowed).
        //  queue_add/remove/force  CHAT-QUEUE-1 3e83a4bd: catalogued under chat.prompt (scope SESSION); they queue, drop or
        //                   send the visitor's own pending chat messages, the same power as the already-allowed prompt frame.
        // No write tool (ticket/settings/file/git writes, skill_save, ...) is in this set; adding one must fail here.
        val widened = setOf("plugin_tools", "timeline_list", "layer_skill_list", "queue_add", "queue_remove", "queue_force")
        assertEquals(widened, allowed - DemoPresetFixture.ALLOWED_TYPES, "the preset allows these, the old lock refused them")
    }

    @Test fun `the old per-project reads are the catalog's PROJECT scope`() {
        val project = ActionCatalog.frames.filter { ActionCatalog.forFrame(it)!!.scope == ActionCatalog.Scope.PROJECT }.toSet()
        assertEquals(DemoPresetFixture.PROJECT_READ_FRAMES, project intersect DemoPresetFixture.ALLOWED_TYPES)
    }

    @Test fun `the demo texts are the plugin's`() {
        assertEquals("demo: 'ticket_save' is disabled in this demo", PolicyRuntime.refusalText(preset, "ticket_save", "board.ticket.save"))
        assertEquals("demo_limit", preset.seats?.turnLimitCode)
    }
}
