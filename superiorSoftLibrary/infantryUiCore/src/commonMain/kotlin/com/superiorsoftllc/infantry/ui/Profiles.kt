package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.protocol.RoleBinding

/** One line saying what a binding pins, for the role detail and the chat's New agent row (S4c: ChatSurface.kt is core). */
fun bindingSummary(b: RoleBinding?): String =
    if (b == null || (b.provider == null && b.model == null && b.effort == null)) "daemon default"
    else listOfNotNull(b.provider, b.model, b.effort?.let { "effort $it" }).joinToString(" / ")
