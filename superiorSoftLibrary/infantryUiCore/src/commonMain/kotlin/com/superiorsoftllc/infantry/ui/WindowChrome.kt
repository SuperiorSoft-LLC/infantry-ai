// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * M2-6: what the title bar needs from the window it lives in. A desktop shell with an undecorated window
 * provides one (minimize / maximize-restore / close, and the drag behaviour of the empty bar); a platform
 * without a window (Android) provides none, and the bar then shows no window controls.
 */
interface WindowChrome {
    /** Read in composition, so an implementation backed by snapshot state swaps the maximize/restore glyph. */
    val isMaximized: Boolean
    fun minimize()
    fun toggleMaximize()
    fun close()

    /** Applied to the bar's empty areas: on desktop, pressing and dragging there moves the window. */
    val dragArea: Modifier get() = Modifier
}

/** The window chrome of the current platform window; null where there is no window to control. */
val LocalWindowChrome = staticCompositionLocalOf<WindowChrome?> { null }

val TITLE_BAR_HEIGHT = 36.dp

/** True while the title bar is in its compact (narrow) layout; read by the Assistant pill to drop its label. */
val LocalTitleBarCompact = androidx.compose.runtime.compositionLocalOf { false }
