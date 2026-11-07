// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import com.superiorsoftllc.infantry.ui.plugin.PluginFaults
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Frame
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.Toolkit

/** Smallest size the edge handles shrink the window to (also the frame's own minimum). DESKTOP-NARROW-CHROME-1: the title bar
 * with its 138dp of caption buttons needs this floor; [DesktopWindowChromeTest] pins it. */
internal val MIN_SIZE = Dimension(900, 560)

/**
 * M2-6: the Infantry AI desktop window, undecorated: no white OS title bar, the app's dark [TitleBar] is the
 * title bar. It gets the window actions through [LocalWindowChrome], moves the window when its empty area is
 * dragged, and the window is resized from its edges by [ResizeHandles] (an undecorated AWT frame has no native
 * resize border on Windows, so `resizable = true` alone would leave it fixed-size).
 * [onFocusGained] runs on the UI thread each time the window becomes the focused window (UPDATER-DEFAULT-1: the due update check).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ApplicationScope.InfantryWindow(onCloseRequest: () -> Unit, onFocusGained: () -> Unit = {}, content: @Composable () -> Unit) {
    val state = rememberWindowState(size = DpSize(1400.dp, 860.dp))
    // APP-ICON-1: the Infantry AI "A" mark on the title bar's taskbar entry and Alt-Tab, not the runtime default.
    val icon = remember { AppIcon.painter() }
    // PLUGIN-CRASH-CONTAIN-1: Compose's default handler shows a native "Error" dialog; ours logs and contains it.
    CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides NoDialogExceptionHandler) {
    Window(onCloseRequest = onCloseRequest, icon = icon, state = state, title = ShellApp.current?.manifest?.windowTitle ?: "Infantry AI", undecorated = true, resizable = true) {
        val frame = window
        LaunchedEffect(frame) { frame.minimumSize = MIN_SIZE }
        LaunchedEffect(frame, state.placement) { WindowCorners.apply(frame, round = state.placement == WindowPlacement.Floating) }
        // INSTALL-REFOCUS-1: what a refused second launch's activation brings forward (only the installed app routes it).
        DisposableEffect(frame) {
            WindowActivation.register { WindowActivation.bringToFront(frame) { state.isMinimized = false } }
            onDispose { WindowActivation.register(null) }
        }
        val focusGained = androidx.compose.runtime.rememberUpdatedState(onFocusGained)
        DisposableEffect(frame) {
            val listener = object : java.awt.event.WindowAdapter() {
                override fun windowGainedFocus(e: java.awt.event.WindowEvent?) { runCatching { focusGained.value() } }
            }
            frame.addWindowFocusListener(listener)
            onDispose { frame.removeWindowFocusListener(listener) }
        }
        val chrome = remember(frame, state) {
            DesktopWindowChrome(
                state, onCloseRequest,
                dragArea = Modifier.windowDrag(frame, state),
                // Without this an undecorated frame maximizes over the taskbar.
                beforeMaximize = { frame.maximizedBounds = workArea(frame) },
            )
        }
        val floating = state.placement == WindowPlacement.Floating
        CompositionLocalProvider(LocalWindowChrome provides chrome) {
            Box(Modifier.fillMaxSize().then(if (floating) Modifier.border(1.dp, Palette.Border) else Modifier)) {
                content()
                HostErrorBanner()
                if (floating) ResizeHandles(frame)
            }
        }
    }
    }
}

/** PLUGIN-CRASH-CONTAIN-1: logs the exception and routes it to [PluginFaults]; never a modal, never a rethrow. */
@OptIn(ExperimentalComposeUiApi::class)
internal object NoDialogExceptionHandler : WindowExceptionHandlerFactory {
    override fun exceptionHandler(window: java.awt.Window) = WindowExceptionHandler { PluginFaults.handle(it) }
}

/** PLUGIN-CRASH-CONTAIN-1: the in-app strip for an exception no plugin owned. */
@Composable
private fun BoxScope.HostErrorBanner() {
    val message = PluginFaults.hostError.value ?: return
    Row(Modifier.align(Alignment.TopCenter).background(Palette.Border).padding(horizontal = 12.dp, vertical = 4.dp).testTag("host-error-banner"), verticalAlignment = Alignment.CenterVertically) {
        Text("Something went wrong: $message", color = Palette.Text, fontSize = 12.sp)
        TextButton({ PluginFaults.hostError.value = null }) { Text("Dismiss") }
    }
}

/** The title bar's window actions, mapped onto Compose's [WindowState]. */
class DesktopWindowChrome(
    private val state: WindowState,
    private val onClose: () -> Unit,
    override val dragArea: Modifier = Modifier,
    private val beforeMaximize: () -> Unit = {},
) : WindowChrome {
    override val isMaximized: Boolean get() = state.placement == WindowPlacement.Maximized
    override fun minimize() { state.isMinimized = true }
    override fun toggleMaximize() {
        if (isMaximized) {
            state.placement = WindowPlacement.Floating
        } else {
            beforeMaximize()
            state.placement = WindowPlacement.Maximized
        }
    }
    override fun close() = onClose()
}

/** The screen the frame is on, minus the taskbar, in the frame's (user-space) coordinates. */
private fun workArea(frame: Frame): Rectangle {
    val gc = frame.graphicsConfiguration
    val b = gc.bounds
    val i = Toolkit.getDefaultToolkit().getScreenInsets(gc)
    return Rectangle(b.x + i.left, b.y + i.top, b.width - i.left - i.right, b.height - i.top - i.bottom)
}

/**
 * Press-and-drag moves the frame. Screen coordinates (not the pointer's local ones) so the motion does not
 * feed back as the window moves under the pointer. A maximized window does not move. The down is read even
 * when the bar's double-click detector has consumed it.
 */
private fun Modifier.windowDrag(frame: Frame, state: WindowState): Modifier = pointerInput(frame) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        if (state.placement != WindowPlacement.Floating) return@awaitEachGesture
        val startMouse = MouseInfo.getPointerInfo()?.location ?: return@awaitEachGesture
        val startWin = frame.location
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.none { it.pressed }) break
            val now = MouseInfo.getPointerInfo()?.location ?: break
            frame.setLocation(startWin.x + now.x - startMouse.x, startWin.y + now.y - startMouse.y)
        }
    }
}

enum class ResizeEdge(val left: Boolean, val top: Boolean, val right: Boolean, val bottom: Boolean, val cursor: Int) {
    LEFT(true, false, false, false, Cursor.W_RESIZE_CURSOR),
    TOP(false, true, false, false, Cursor.N_RESIZE_CURSOR),
    RIGHT(false, false, true, false, Cursor.E_RESIZE_CURSOR),
    BOTTOM(false, false, false, true, Cursor.S_RESIZE_CURSOR),
    TOP_LEFT(true, true, false, false, Cursor.NW_RESIZE_CURSOR),
    TOP_RIGHT(false, true, true, false, Cursor.NE_RESIZE_CURSOR),
    BOTTOM_LEFT(true, false, false, true, Cursor.SW_RESIZE_CURSOR),
    BOTTOM_RIGHT(false, false, true, true, Cursor.SE_RESIZE_CURSOR),
}

/**
 * The bounds after dragging [edge] by ([dx], [dy]) from [start]. A left/top drag moves the origin and keeps the
 * opposite edge fixed; no side shrinks below [minWidth] x [minHeight].
 */
fun resizedBounds(start: Rectangle, edge: ResizeEdge, dx: Int, dy: Int, minWidth: Int, minHeight: Int): Rectangle {
    var x = start.x
    var y = start.y
    var w = start.width
    var h = start.height
    if (edge.right) w = maxOf(minWidth, start.width + dx)
    if (edge.bottom) h = maxOf(minHeight, start.height + dy)
    if (edge.left) {
        w = maxOf(minWidth, start.width - dx)
        x = start.x + start.width - w
    }
    if (edge.top) {
        h = maxOf(minHeight, start.height - dy)
        y = start.y + start.height - h
    }
    return Rectangle(x, y, w, h)
}

/** Thin strips along the edges and corners that resize the frame; everything inside them passes through. */
@Composable
private fun BoxScope.ResizeHandles(frame: Frame) {
    val t = 5.dp
    val c = 10.dp
    Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(t).resizeHandle(frame, ResizeEdge.LEFT))
    Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(t).resizeHandle(frame, ResizeEdge.RIGHT))
    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(t).resizeHandle(frame, ResizeEdge.TOP))
    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(t).resizeHandle(frame, ResizeEdge.BOTTOM))
    Box(Modifier.align(Alignment.TopStart).size(c).resizeHandle(frame, ResizeEdge.TOP_LEFT))
    Box(Modifier.align(Alignment.TopEnd).size(c).resizeHandle(frame, ResizeEdge.TOP_RIGHT))
    Box(Modifier.align(Alignment.BottomStart).size(c).resizeHandle(frame, ResizeEdge.BOTTOM_LEFT))
    Box(Modifier.align(Alignment.BottomEnd).size(c).resizeHandle(frame, ResizeEdge.BOTTOM_RIGHT))
}

private fun Modifier.resizeHandle(frame: Frame, edge: ResizeEdge): Modifier =
    pointerHoverIcon(PointerIcon(Cursor(edge.cursor))).pointerInput(frame, edge) {
        awaitEachGesture {
            awaitFirstDown().consume()
            val startMouse = MouseInfo.getPointerInfo()?.location ?: return@awaitEachGesture
            val startBounds = frame.bounds
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.none { it.pressed }) break
                event.changes.forEach { it.consume() }
                val now = MouseInfo.getPointerInfo()?.location ?: break
                frame.bounds = resizedBounds(startBounds, edge, now.x - startMouse.x, now.y - startMouse.y, MIN_SIZE.width, MIN_SIZE.height)
                frame.validate()
            }
        }
    }
