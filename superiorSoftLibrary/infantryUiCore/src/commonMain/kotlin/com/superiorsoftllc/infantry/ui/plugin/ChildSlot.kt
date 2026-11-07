// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.LocalTabFactory
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.net.Controller

/**
 * SLOTS-1: the arguments the host of a [ChildSlot] hands to the sections drawn in it (a key -> value map the host and its
 * children agree on; empty outside a slot).
 */
val LocalSlotArgs = staticCompositionLocalOf<Map<String, Any?>> { emptyMap() }

/** Test tag on the generic empty state of a [ChildSlot] that no section fills. */
const val SLOT_EMPTY_TAG = "slot-empty"

/**
 * SLOTS-1: a named, overridable child slot. The host declares [name] in its module.json (`slots`) and draws this where the
 * child goes; every module adds sections to it through the existing `panels` declaration (`"panel": name`, `order` before or
 * after the default child; a `preferences` entry replaces one). Sections draw top to bottom, a `fill` one taking the free
 * height. With none loaded, [empty] draws (a generic notice by default). A host wraps an existing section by moving its
 * drawing into a module that contributes it as the slot's default section.
 *
 * [state] and [controller] are the window's; [args] reach the sections through [LocalSlotArgs]; [context] is the host's own
 * [TabContext] when it has one.
 */
@Composable
fun ChildSlot(
    name: String,
    state: AppState,
    controller: Controller,
    modifier: Modifier = Modifier,
    args: Map<String, Any?> = emptyMap(),
    context: TabContext? = null,
    empty: @Composable () -> Unit = { SlotEmpty(name) },
) {
    val factory = LocalTabFactory.current
    val sections = factory.panels(name)
    val ctx = context ?: TabContext(
        state, controller, open = {}, showChat = {}, insertIntoChat = {}, diffTarget = null, onOpenDiff = {}, transcript = {},
        config = { factory.resolvedConfig.values(it) },
    )
    Column(modifier) {
        if (sections.isEmpty()) empty()
        else CompositionLocalProvider(LocalSlotArgs provides args) { sections.forEach { PanelSection(factory, it, ctx) } }
    }
}

/**
 * CHAT-VOICE-SEAMS-1: [ChildSlot] laid out as a ROW (a slot of small inline blocks: buttons beside a composer, actions on a
 * message). Each section takes its own width, left to right in `order`; `fill` is ignored. With no section loaded it
 * draws NOTHING (no empty state, no box), so a host that offers the slot looks exactly as it did without it.
 */
@Composable
fun ChildSlotRow(
    name: String,
    state: AppState,
    controller: Controller,
    modifier: Modifier = Modifier,
    args: Map<String, Any?> = emptyMap(),
    context: TabContext? = null,
) {
    val factory = LocalTabFactory.current
    val sections = factory.panels(name)
    if (sections.isEmpty()) return
    val ctx = context ?: TabContext(
        state, controller, open = {}, showChat = {}, insertIntoChat = {}, diffTarget = null, onOpenDiff = {}, transcript = {},
        config = { factory.resolvedConfig.values(it) },
    )
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        CompositionLocalProvider(LocalSlotArgs provides args) { sections.forEach { PanelSectionBody(factory, it, ctx, Modifier.testTag(it.testTag), badge = false) } }
    }
}

/** Whether any loaded module adds a section to slot [name] (a host skips its slot's wrapper when none does). */
@Composable
fun slotFilled(name: String): Boolean = LocalTabFactory.current.panels(name).isNotEmpty()

/** The generic empty state of a slot nothing fills. */
@Composable
fun SlotEmpty(name: String) {
    Box(Modifier.fillMaxSize().testTag(SLOT_EMPTY_TAG), Alignment.Center) {
        Text("Nothing is installed for $name.", color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
    }
}
