// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.mascot

import com.superiorsoftllc.infantry.plugin.api.ConfigDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldTypes
import com.superiorsoftllc.infantry.plugin.api.ConfigScopes
import kotlinx.serialization.json.JsonPrimitive

/**
 * CI-TESTS-2: Mascot's config, the same as its module.json `config` (ModuleConfigDriftTest pins them equal, FIX-CONFIG-1).
 * Desktop reads module.json; web and Android compile this in, so they get the Mascot avatars card too.
 */
val MASCOT_CONFIG: List<ConfigDeclaration> = listOf(
    ConfigDeclaration(
        node = MASCOT_NODE, group = "Mascot avatars", title = "Mascot avatars", tab = "Plugins", order = 920,
        applies = "Applies to: agent avatars, once the Mascot style is chosen as the avatar style.",
        keywords = listOf("mascot", "avatar", "agent", "animation", "motion", "character"),
        fields = listOf(
            ConfigFieldDeclaration(
                "mascot.motion", ConfigFieldTypes.SELECT, "Motion", JsonPrimitive("static"), listOf("static", "focus", "all"),
                optionLabels = mapOf("static" to "Static", "focus" to "Focus only", "all" to "Everywhere"),
                scope = ConfigScopes.DEVICE, order = 1,
                description = "Static (cheapest, default): one frame per state, no idle loop; the pose changes when the state does. " +
                    "Focus only: the idle loop runs only where the app allows live motion (sidebar, chat header, title bar). " +
                    "Everywhere: every avatar loops, which costs a frame ticker per visible avatar: costly in long chats and big lists. " +
                    "Measured (MASCOT-3, 200 chat rows + 20 sidebar agents, desktop JVM test harness): static about 12 ms per animation " +
                    "frame, focus about 24 ms, everywhere about 24 ms; first composition 120-220 ms for all three.",
            ),
            ConfigFieldDeclaration(
                "mascot.settleMs", ConfigFieldTypes.NUMBER, "Pose change (ms)", JsonPrimitive(900), min = 0.0, max = 5000.0, step = 100.0,
                scope = ConfigScopes.DEVICE, order = 2,
                description = "Static mode only: how long the mascot morphs into its new pose when the session state changes, then " +
                    "freezes again (one short animation per change, never a loop). 0 = the pose swaps at once, like a snapshot. " +
                    "Focus and Everywhere always animate.",
            ),
            ConfigFieldDeclaration(
                "mascot.fidgetSec", ConfigFieldTypes.NUMBER, "Idle fidget (seconds, 0 = off)", JsonPrimitive(12), min = 0.0, max = 600.0, step = 1.0,
                scope = ConfigScopes.DEVICE, order = 3,
                description = "Focus and Everywhere only: while an agent is idle, its mascot does a short random pose (happy, greeting, " +
                    "reading, sleepy, confused) about this often, then settles back. 0 = a still idle mascot between state changes.",
            ),
            ConfigFieldDeclaration(
                "mascot.stateMap", ConfigFieldTypes.TEXT, "State map",
                JsonPrimitive("STARTING=Greet;BUSY=Working;WAITING_INPUT=Notify;IDLE=Idle;EXITED=Sleepy;ORPHANED=Confused;FAILED=Alert"),
                scope = ConfigScopes.DEVICE, order = 2,
                description = "SESSION_STATE=Mood pairs separated by semicolons. A state not listed, or a mood the mascot does not know, " +
                    "draws Idle. While compacting a busy session shows Reading; a failed turn shows Alert.",
            ),
            ConfigFieldDeclaration(
                "mascot.roleLooks", ConfigFieldTypes.TEXT, "Role looks", JsonPrimitive(""), scope = ConfigScopes.DEVICE, order = 3,
                description = "Optional overrides, role=eyes/finish/cat separated by semicolons, e.g. architect=round/chrome/cat. " +
                    "Eyes: classic, big, round, dot, vizor. Finish: flat, chrome, outline. Cat: cat or nocat. Roles not listed get a " +
                    "look from a hash of the role id.",
            ),
        ),
    ),
)
