package com.superiorsoftllc.agenthq.ui

import androidx.compose.ui.input.pointer.PointerIcon

// The browser target has no grab cursors in Compose's PointerIcon set: the hand reads as "can be picked up".
actual val GrabIcon: PointerIcon = PointerIcon.Hand
actual val GrabbingIcon: PointerIcon = PointerIcon.Hand
