// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.model

/** ASSISTANT-1: width rules for the drag-resizable Assistant popup, in dp. Pure so the bounds are testable without Compose. */
object AssistantPanelWidth {
    const val DEFAULT = 420f
    const val MIN = 320f
    const val MAX = 900f

    fun clamp(width: Float): Float = width.coerceIn(MIN, MAX)

    /** The popup is docked right, so a drag LEFT (negative [deltaDp]) widens it. */
    fun dragged(current: Float, deltaDp: Float): Float = clamp(current - deltaDp)
}
