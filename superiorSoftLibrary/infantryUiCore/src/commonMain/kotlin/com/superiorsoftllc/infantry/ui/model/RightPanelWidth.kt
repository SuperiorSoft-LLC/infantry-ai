package com.superiorsoftllc.infantry.ui.model

/** Width rules for the drag-resizable right panel, in dp. Pure so the bounds are testable without Compose. */
object RightPanelWidth {
    const val DEFAULT = 320f
    const val MIN = 280f
    const val MAX = 640f

    fun clamp(width: Float): Float = width.coerceIn(MIN, MAX)

    /** The panel is on the right edge, so a drag LEFT (negative [deltaDp]) widens it. */
    fun dragged(current: Float, deltaDp: Float): Float = clamp(current - deltaDp)
}
