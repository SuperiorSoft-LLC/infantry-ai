package com.superiorsoftllc.infantry.ui.model

/** M2-9: width rules for the drag-resizable LEFT sidebar, in dp. Not persisted (owner's call). */
object SidebarWidth {
    const val DEFAULT = 260f
    const val MIN = 200f
    const val MAX = 480f

    fun clamp(width: Float): Float = width.coerceIn(MIN, MAX)

    /** The sidebar is on the left edge, so a drag RIGHT (positive [deltaDp]) widens it. */
    fun dragged(current: Float, deltaDp: Float): Float = clamp(current + deltaDp)
}
