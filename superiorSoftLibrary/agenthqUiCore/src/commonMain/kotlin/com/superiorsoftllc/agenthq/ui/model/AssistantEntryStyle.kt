package com.superiorsoftllc.agenthq.ui.model

/** The title bar's Assistant pill: the "Assistant" label shows beside the portrait unless the bar is compact (portrait + border only). */
object AssistantEntryStyle {
    fun showLabel(compactBar: Boolean): Boolean = !compactBar
}
