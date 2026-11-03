/*
 * The path data below is copied from the Material icons of the Android Open Source Project
 * (androidx.compose.material:material-icons-core / material-icons-extended 1.7.3).
 *
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * UX-ICON-1: the Material icons the window uses, copied in rather than depending on material-icons-extended
 * (a ~36 MB jar for eight icons; the installer ships without ProGuard). Same 24dp grid, viewport and fill as
 * Material's own `materialIcon { materialPath { } }`, so they render identically. Add an icon by copying its path
 * from the Material sources the same way.
 */
object AhqIcons {
    /** Material "AutoMirrored.Filled.Send". */
    val Send: ImageVector by lazy {
        icon("Send", autoMirror = true) {
            moveTo(2.01f, 21.0f)
            lineTo(23.0f, 12.0f)
            lineTo(2.01f, 3.0f)
            lineTo(2.0f, 10.0f)
            lineToRelative(15.0f, 2.0f)
            lineToRelative(-15.0f, 2.0f)
            close()
        }
    }

    /** Material "Filled.PersonAdd". */
    val PersonAdd: ImageVector by lazy {
        icon("PersonAdd", autoMirror = false) {
            moveTo(15.0f, 12.0f)
            curveToRelative(2.21f, 0.0f, 4.0f, -1.79f, 4.0f, -4.0f)
            reflectiveCurveToRelative(-1.79f, -4.0f, -4.0f, -4.0f)
            reflectiveCurveToRelative(-4.0f, 1.79f, -4.0f, 4.0f)
            reflectiveCurveToRelative(1.79f, 4.0f, 4.0f, 4.0f)
            close()
            moveTo(6.0f, 10.0f)
            lineTo(6.0f, 7.0f)
            lineTo(4.0f, 7.0f)
            verticalLineToRelative(3.0f)
            lineTo(1.0f, 10.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(3.0f)
            verticalLineToRelative(3.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(-3.0f)
            horizontalLineToRelative(3.0f)
            verticalLineToRelative(-2.0f)
            lineTo(6.0f, 10.0f)
            close()
            moveTo(15.0f, 14.0f)
            curveToRelative(-2.67f, 0.0f, -8.0f, 1.34f, -8.0f, 4.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(16.0f)
            verticalLineToRelative(-2.0f)
            curveToRelative(0.0f, -2.66f, -5.33f, -4.0f, -8.0f, -4.0f)
            close()
        }
    }

    /** Material "Filled.Add". */
    val Add: ImageVector by lazy {
        icon("Add", autoMirror = false) {
            moveTo(19.0f, 13.0f)
            horizontalLineToRelative(-6.0f)
            verticalLineToRelative(6.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineToRelative(-6.0f)
            lineTo(5.0f, 13.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(6.0f)
            lineTo(11.0f, 5.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(6.0f)
            horizontalLineToRelative(6.0f)
            verticalLineToRelative(2.0f)
            close()
        }
    }

    /** Material "Filled.SwapHoriz" (SWAP-AGENT-1: the composer's Switch agent). */
    val SwapHoriz: ImageVector by lazy {
        icon("SwapHoriz", autoMirror = false) {
            moveTo(6.99f, 11.0f)
            lineTo(3.0f, 15.0f)
            lineToRelative(3.99f, 4.0f)
            verticalLineToRelative(-3.0f)
            horizontalLineTo(14.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineTo(6.99f)
            verticalLineToRelative(-3.0f)
            close()
            moveTo(21.0f, 9.0f)
            lineToRelative(-3.99f, -4.0f)
            verticalLineToRelative(3.0f)
            horizontalLineTo(10.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(7.01f)
            verticalLineToRelative(3.0f)
            lineTo(21.0f, 9.0f)
            close()
        }
    }

    /** PLUGINS-3: Material "AutoMirrored.Filled.KeyboardArrowLeft", a tab strip's scroll-left arrow. */
    val ChevronLeft: ImageVector by lazy {
        icon("KeyboardArrowLeft", autoMirror = true) {
            moveTo(15.41f, 16.59f)
            lineTo(10.83f, 12.0f)
            lineToRelative(4.58f, -4.59f)
            lineTo(14.0f, 6.0f)
            lineToRelative(-6.0f, 6.0f)
            lineToRelative(6.0f, 6.0f)
            lineToRelative(1.41f, -1.41f)
            close()
        }
    }

    /** PLUGINS-3: Material "AutoMirrored.Filled.KeyboardArrowRight", a tab strip's scroll-right arrow. */
    val ChevronRight: ImageVector by lazy {
        icon("KeyboardArrowRight", autoMirror = true) {
            moveTo(8.59f, 16.59f)
            lineTo(13.17f, 12.0f)
            lineTo(8.59f, 7.41f)
            lineTo(10.0f, 6.0f)
            lineToRelative(6.0f, 6.0f)
            lineToRelative(-6.0f, 6.0f)
            lineToRelative(-1.41f, -1.41f)
            close()
        }
    }

    /** Material "Filled.KeyboardArrowDown", a drop-down's caret (drawn, so the web font cannot turn a text glyph into tofu). */
    val ChevronDown: ImageVector by lazy {
        icon("KeyboardArrowDown", autoMirror = false) {
            moveTo(7.41f, 8.59f)
            lineTo(12.0f, 13.17f)
            lineToRelative(4.59f, -4.58f)
            lineTo(18.0f, 10.0f)
            lineToRelative(-6.0f, 6.0f)
            lineToRelative(-6.0f, -6.0f)
            close()
        }
    }

    /** MOBILE-LAYOUT-1: Material "AutoMirrored.Filled.ArrowBack", the compact window's back affordance. */
    val ArrowBack: ImageVector by lazy {
        icon("ArrowBack", autoMirror = true) {
            moveTo(20.0f, 11.0f)
            horizontalLineTo(7.83f)
            lineToRelative(5.59f, -5.59f)
            lineTo(12.0f, 4.0f)
            lineToRelative(-8.0f, 8.0f)
            lineToRelative(8.0f, 8.0f)
            lineToRelative(1.41f, -1.41f)
            lineTo(7.83f, 13.0f)
            horizontalLineTo(20.0f)
            verticalLineToRelative(-2.0f)
            close()
        }
    }

    /** Material "Filled.Close". */
    val Close: ImageVector by lazy {
        icon("Close", autoMirror = false) {
            moveTo(19.0f, 6.41f)
            lineTo(17.59f, 5.0f)
            lineTo(12.0f, 10.59f)
            lineTo(6.41f, 5.0f)
            lineTo(5.0f, 6.41f)
            lineTo(10.59f, 12.0f)
            lineTo(5.0f, 17.59f)
            lineTo(6.41f, 19.0f)
            lineTo(12.0f, 13.41f)
            lineTo(17.59f, 19.0f)
            lineTo(19.0f, 17.59f)
            lineTo(13.41f, 12.0f)
            close()
        }
    }

    /** Material "Filled.PlayArrow". */
    val PlayArrow: ImageVector by lazy {
        icon("PlayArrow", autoMirror = false) {
            moveTo(8.0f, 5.0f)
            verticalLineToRelative(14.0f)
            lineToRelative(11.0f, -7.0f)
            close()
        }
    }

    /** Material "Filled.Cancel". */
    val Cancel: ImageVector by lazy {
        icon("Cancel", autoMirror = false) {
            moveTo(12.0f, 2.0f)
            curveTo(6.47f, 2.0f, 2.0f, 6.47f, 2.0f, 12.0f)
            reflectiveCurveToRelative(4.47f, 10.0f, 10.0f, 10.0f)
            reflectiveCurveToRelative(10.0f, -4.47f, 10.0f, -10.0f)
            reflectiveCurveTo(17.53f, 2.0f, 12.0f, 2.0f)
            close()
            moveTo(17.0f, 15.59f)
            lineTo(15.59f, 17.0f)
            lineTo(12.0f, 13.41f)
            lineTo(8.41f, 17.0f)
            lineTo(7.0f, 15.59f)
            lineTo(10.59f, 12.0f)
            lineTo(7.0f, 8.41f)
            lineTo(8.41f, 7.0f)
            lineTo(12.0f, 10.59f)
            lineTo(15.59f, 7.0f)
            lineTo(17.0f, 8.41f)
            lineTo(13.41f, 12.0f)
            lineTo(17.0f, 15.59f)
            close()
        }
    }

    /** Material "Filled.DeleteForever". */
    val DeleteForever: ImageVector by lazy {
        icon("DeleteForever", autoMirror = false) {
            moveTo(6.0f, 19.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(8.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            lineTo(18.0f, 7.0f)
            lineTo(6.0f, 7.0f)
            verticalLineToRelative(12.0f)
            close()
            moveTo(8.46f, 11.88f)
            lineToRelative(1.41f, -1.41f)
            lineTo(12.0f, 12.59f)
            lineToRelative(2.12f, -2.12f)
            lineToRelative(1.41f, 1.41f)
            lineTo(13.41f, 14.0f)
            lineToRelative(2.12f, 2.12f)
            lineToRelative(-1.41f, 1.41f)
            lineTo(12.0f, 15.41f)
            lineToRelative(-2.12f, 2.12f)
            lineToRelative(-1.41f, -1.41f)
            lineTo(10.59f, 14.0f)
            lineToRelative(-2.13f, -2.12f)
            close()
            moveTo(15.5f, 4.0f)
            lineToRelative(-1.0f, -1.0f)
            horizontalLineToRelative(-5.0f)
            lineToRelative(-1.0f, 1.0f)
            lineTo(5.0f, 4.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(14.0f)
            lineTo(19.0f, 4.0f)
            close()
        }
    }

    /** Material "Filled.Check" (RUN-CONSOLE: a finished run's pill). */
    val Check: ImageVector by lazy {
        icon("Check", autoMirror = false) {
            moveTo(9.0f, 16.17f)
            lineTo(4.83f, 12.0f)
            lineToRelative(-1.42f, 1.41f)
            lineTo(9.0f, 19.0f)
            lineTo(21.0f, 7.0f)
            lineToRelative(-1.41f, -1.41f)
            close()
        }
    }

    /** Material "Filled.Stop". */
    val Stop: ImageVector by lazy {
        icon("Stop", autoMirror = false) {
            moveTo(6.0f, 6.0f)
            horizontalLineToRelative(12.0f)
            verticalLineToRelative(12.0f)
            horizontalLineTo(6.0f)
            close()
        }
    }

    /** Material "Filled.Refresh". */
    val Refresh: ImageVector by lazy {
        icon("Refresh", autoMirror = false) {
            moveTo(17.65f, 6.35f)
            curveTo(16.2f, 4.9f, 14.21f, 4.0f, 12.0f, 4.0f)
            curveToRelative(-4.42f, 0.0f, -7.99f, 3.58f, -7.99f, 8.0f)
            reflectiveCurveToRelative(3.57f, 8.0f, 7.99f, 8.0f)
            curveToRelative(3.73f, 0.0f, 6.84f, -2.55f, 7.73f, -6.0f)
            horizontalLineToRelative(-2.08f)
            curveToRelative(-0.82f, 2.33f, -3.04f, 4.0f, -5.65f, 4.0f)
            curveToRelative(-3.31f, 0.0f, -6.0f, -2.69f, -6.0f, -6.0f)
            reflectiveCurveToRelative(2.69f, -6.0f, 6.0f, -6.0f)
            curveToRelative(1.66f, 0.0f, 3.14f, 0.69f, 4.22f, 1.78f)
            lineTo(13.0f, 11.0f)
            horizontalLineToRelative(7.0f)
            verticalLineTo(4.0f)
            lineToRelative(-2.35f, 2.35f)
            close()
        }
    }

    /** Material "Filled.AttachFile". */
    val AttachFile: ImageVector by lazy {
        icon("AttachFile", autoMirror = false) {
            moveTo(16.5f, 6.0f)
            verticalLineToRelative(11.5f)
            curveToRelative(0.0f, 2.21f, -1.79f, 4.0f, -4.0f, 4.0f)
            reflectiveCurveToRelative(-4.0f, -1.79f, -4.0f, -4.0f)
            verticalLineTo(5.0f)
            curveToRelative(0.0f, -1.38f, 1.12f, -2.5f, 2.5f, -2.5f)
            reflectiveCurveToRelative(2.5f, 1.12f, 2.5f, 2.5f)
            verticalLineToRelative(10.5f)
            curveToRelative(0.0f, 0.55f, -0.45f, 1.0f, -1.0f, 1.0f)
            reflectiveCurveToRelative(-1.0f, -0.45f, -1.0f, -1.0f)
            verticalLineTo(6.0f)
            horizontalLineTo(10.0f)
            verticalLineToRelative(9.5f)
            curveToRelative(0.0f, 1.38f, 1.12f, 2.5f, 2.5f, 2.5f)
            reflectiveCurveToRelative(2.5f, -1.12f, 2.5f, -2.5f)
            verticalLineTo(5.0f)
            curveToRelative(0.0f, -2.21f, -1.79f, -4.0f, -4.0f, -4.0f)
            reflectiveCurveTo(7.0f, 2.79f, 7.0f, 5.0f)
            verticalLineToRelative(12.5f)
            curveToRelative(0.0f, 3.04f, 2.46f, 5.5f, 5.5f, 5.5f)
            reflectiveCurveToRelative(5.5f, -2.46f, 5.5f, -5.5f)
            verticalLineTo(6.0f)
            horizontalLineToRelative(-1.5f)
            close()
        }
    }

    /** Material "Filled.ContentPaste". */
    val ContentPaste: ImageVector by lazy {
        icon("ContentPaste", autoMirror = false) {
            moveTo(19.0f, 2.0f)
            horizontalLineToRelative(-4.18f)
            curveTo(14.4f, 0.84f, 13.3f, 0.0f, 12.0f, 0.0f)
            curveToRelative(-1.3f, 0.0f, -2.4f, 0.84f, -2.82f, 2.0f)
            horizontalLineTo(5.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            verticalLineToRelative(16.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(14.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            verticalLineTo(4.0f)
            curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
            close()
            moveTo(12.0f, 2.0f)
            curveToRelative(0.55f, 0.0f, 1.0f, 0.45f, 1.0f, 1.0f)
            reflectiveCurveToRelative(-0.45f, 1.0f, -1.0f, 1.0f)
            reflectiveCurveToRelative(-1.0f, -0.45f, -1.0f, -1.0f)
            reflectiveCurveToRelative(0.45f, -1.0f, 1.0f, -1.0f)
            close()
            moveTo(19.0f, 20.0f)
            horizontalLineTo(5.0f)
            verticalLineTo(4.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(3.0f)
            horizontalLineToRelative(10.0f)
            verticalLineTo(4.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(16.0f)
            close()
        }
    }

    /** PANEL-TOGGLE-1: a window frame with its LEFT column filled (the agent list toggle). Drawn here, not Material. */
    val PanelLeft: ImageVector by lazy { icon("PanelLeft", autoMirror = false) { panelFrame(); rect(5f, 6f, 9f, 18f) } }

    /** PANEL-TOGGLE-1: a window frame with its RIGHT column filled (the side panel toggle). Drawn here, not Material. */
    val PanelRight: ImageVector by lazy { icon("PanelRight", autoMirror = false) { panelFrame(); rect(15f, 6f, 19f, 18f) } }

    /** Three horizontal dots (the overflow "More" button). Drawn here: the U+22EF glyph is tofu in the web canvas font. */
    val MoreHoriz: ImageVector by lazy { icon("MoreHoriz", autoMirror = false) { rect(4f, 10.5f, 7f, 13.5f); rect(10.5f, 10.5f, 13.5f, 13.5f); rect(17f, 10.5f, 20f, 13.5f) } }

    /** COMMS: a walkie-talkie (antenna, outlined body, speaker slots). Drawn here, not Material. */
    val Radio: ImageVector by lazy {
        icon("Radio", autoMirror = false) {
            rect(8f, 1f, 10f, 6f)
            rect(6f, 6f, 18f, 8f); rect(6f, 20f, 18f, 22f)
            rect(6f, 8f, 8f, 20f); rect(16f, 8f, 18f, 20f)
            rect(10f, 10f, 14f, 11.5f); rect(10f, 13f, 14f, 14.5f); rect(10f, 16f, 14f, 17.5f)
        }
    }

    /** REASONING: a simple brain (two lobes split by a centre groove, plus a stem). Drawn here, not Material. */
    val Brain: ImageVector by lazy {
        icon("Brain", autoMirror = false) {
            disc(8.5f, 10.5f, 5.5f); disc(15.5f, 10.5f, 5.5f)
            rect(10f, 14f, 14f, 19f)
            // Counter-clockwise bars cut the centre groove and two folds out of the non-zero fill.
            hole(11.4f, 5f, 12.6f, 15f); hole(5.5f, 9.4f, 10f, 10.6f); hole(14f, 9.4f, 18.5f, 10.6f)
        }
    }

    /** Source-control branch (two nodes, a trunk and a fork). Drawn here: the glyph U+2387 is tofu in the web build. */
    val Branch: ImageVector by lazy {
        icon("Branch", autoMirror = false) {
            disc(6f, 5f, 2.5f); disc(6f, 19f, 2.5f); disc(18f, 7f, 2.5f)
            rect(5f, 6f, 7f, 18f); rect(17f, 9f, 19f, 13f); rect(7f, 11f, 19f, 13f)
        }
    }

    /** Configuration cog (ring, eight teeth, round hole). Drawn here: the glyph U+2699 is tofu in the web build. */
    val Cog: ImageVector by lazy {
        icon("Cog", autoMirror = false) {
            disc(12f, 12f, 7f)
            rect(10.5f, 2f, 13.5f, 22f); rect(2f, 10.5f, 22f, 13.5f)
            moveTo(5.2f, 3.1f); lineTo(20.9f, 18.8f); lineTo(18.8f, 20.9f); lineTo(3.1f, 5.2f); close()
            moveTo(18.8f, 3.1f); lineTo(20.9f, 5.2f); lineTo(5.2f, 20.9f); lineTo(3.1f, 18.8f); close()
            moveTo(9f, 12f); arcTo(3f, 3f, 0f, true, false, 15f, 12f); arcTo(3f, 3f, 0f, true, false, 9f, 12f); close()
        }
    }

    private fun PathBuilder.disc(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, true, true, cx + r, cy)
        arcTo(r, r, 0f, true, true, cx - r, cy)
        close()
    }

    /** A rectangle wound the other way round, so it cuts a hole in the fill beneath it. */
    private fun PathBuilder.hole(left: Float, top: Float, right: Float, bottom: Float) {
        moveTo(left, top)
        verticalLineTo(bottom)
        horizontalLineTo(right)
        verticalLineTo(top)
        close()
    }

    /** A 2dp frame from (3,4) to (21,20), as four bars (non-zero fill: each bar is its own clockwise rectangle). */
    private fun PathBuilder.panelFrame() {
        rect(3f, 4f, 21f, 6f)
        rect(3f, 18f, 21f, 20f)
        rect(3f, 6f, 5f, 18f)
        rect(19f, 6f, 21f, 18f)
    }

    private fun PathBuilder.rect(left: Float, top: Float, right: Float, bottom: Float) {
        moveTo(left, top)
        horizontalLineTo(right)
        verticalLineTo(bottom)
        horizontalLineTo(left)
        close()
    }

    private fun icon(name: String, autoMirror: Boolean, pathData: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = "AhqIcons.$name", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = autoMirror,
        ).path(
            fill = SolidColor(Color.Black), fillAlpha = 1f, stroke = null, strokeAlpha = 1f, strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt, strokeLineJoin = StrokeJoin.Bevel, strokeLineMiter = 1f,
            pathFillType = PathFillType.NonZero, pathBuilder = pathData,
        ).build()
}
