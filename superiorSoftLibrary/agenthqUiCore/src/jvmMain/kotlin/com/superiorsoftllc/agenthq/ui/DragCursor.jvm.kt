package com.superiorsoftllc.agenthq.ui

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor

// AWT has no grab / grabbing cursor: the hand reads as "can be picked up", the move cross as "being moved".
actual val GrabIcon: PointerIcon = PointerIcon.Hand
actual val GrabbingIcon: PointerIcon = PointerIcon(Cursor(Cursor.MOVE_CURSOR))
