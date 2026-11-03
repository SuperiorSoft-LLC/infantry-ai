// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
/*
 * The "opencode" brackets and the "terminal" fallback are adapted from the Material icons "Filled.Code" and
 * "Filled.Terminal" of the Android Open Source Project (androidx.compose.material:material-icons-extended 1.7.3).
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

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import org.jetbrains.compose.resources.decodeToImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** ROLE-PILL-ICONS-1: a harness icon's tag is `harness-icon:<harnessId>`. */
const val HARNESS_ICON_TAG_PREFIX = "harness-icon:"

fun harnessIconTag(harness: String): String = "$HARNESS_ICON_TAG_PREFIX$harness"

/** Semantics: the glyph key a [HarnessIcon] resolved to ([AhqHarnessIcons.resolve]), so a test asserts the glyph. */
val HarnessIconKey = SemanticsPropertyKey<String>("HarnessIconKey")
var SemanticsPropertyReceiver.harnessIconKey by HarnessIconKey

/**
 * ROLE-PILL-ICONS-1: the small glyph on a harness pill. Deliberately NEUTRAL glyphs, not vendor logos: none of the
 * Anthropic/Claude, opencode or pi marks is cleared for in-app use here. Bundled vectors on the 24dp Material grid
 * (same as [AhqIcons]); nothing is fetched at runtime. A harness this table does not know paints [FALLBACK].
 */
object AhqHarnessIcons {
    const val FALLBACK: String = "terminal"

    /** The glyph key for [harnessId]: its own when this build ships one, else [FALLBACK]. */
    fun resolve(harnessId: String?): String = when (harnessId?.trim()?.lowercase()) {
        "opencode" -> "opencode"
        "claude", "claude-code" -> "claude"
        "pi" -> "pi"
        else -> FALLBACK
    }

    fun forHarness(harnessId: String?): ImageVector = when (resolve(harnessId)) {
        "opencode" -> OpenCode
        "claude" -> Claude
        "pi" -> Pi
        else -> Terminal
    }

    /** opencode: code brackets with a slash, `</>`. */
    val OpenCode: ImageVector by lazy {
        icon("OpenCode") {
            moveTo(9.4f, 16.6f); lineTo(4.8f, 12.0f); lineToRelative(4.6f, -4.6f); lineTo(8.0f, 6.0f)
            lineToRelative(-6.0f, 6.0f); lineToRelative(6.0f, 6.0f); lineToRelative(1.4f, -1.4f); close()
            moveTo(14.6f, 16.6f); lineToRelative(4.6f, -4.6f); lineToRelative(-4.6f, -4.6f); lineTo(16.0f, 6.0f)
            lineToRelative(6.0f, 6.0f); lineToRelative(-6.0f, 6.0f); lineToRelative(-1.4f, -1.4f); close()
            moveTo(12.9f, 5.0f); horizontalLineToRelative(1.6f); lineTo(11.1f, 19.0f); horizontalLineToRelative(-1.6f); close()
        }
    }

    /** claude: a generic four-point spark. */
    val Claude: ImageVector by lazy {
        icon("Claude") {
            moveTo(12.0f, 2.0f)
            curveTo(12.6f, 7.5f, 16.5f, 11.4f, 22.0f, 12.0f)
            curveTo(16.5f, 12.6f, 12.6f, 16.5f, 12.0f, 22.0f)
            curveTo(11.4f, 16.5f, 7.5f, 12.6f, 2.0f, 12.0f)
            curveTo(7.5f, 11.4f, 11.4f, 7.5f, 12.0f, 2.0f)
            close()
        }
    }

    /** pi: the Greek letter, drawn as a path (a letter, not a logo). */
    val Pi: ImageVector by lazy {
        icon("Pi") {
            moveTo(4.0f, 5.0f); lineTo(20.0f, 5.0f); lineTo(20.0f, 7.4f); lineTo(17.0f, 7.4f); lineTo(17.0f, 16.0f)
            curveTo(17.0f, 17.1f, 17.4f, 17.6f, 18.3f, 17.6f)
            curveTo(18.7f, 17.6f, 19.1f, 17.5f, 19.5f, 17.3f)
            lineTo(19.9f, 19.4f)
            curveTo(19.3f, 19.7f, 18.6f, 19.9f, 17.8f, 19.9f)
            curveTo(15.6f, 19.9f, 14.5f, 18.6f, 14.5f, 16.1f)
            lineTo(14.5f, 7.4f); lineTo(10.4f, 7.4f)
            curveTo(10.2f, 12.2f, 9.5f, 16.6f, 8.2f, 19.8f)
            lineTo(5.6f, 19.8f)
            curveTo(6.9f, 16.5f, 7.6f, 12.2f, 7.8f, 7.4f)
            lineTo(4.0f, 7.4f)
            close()
        }
    }

    /** Any other harness: Material "Filled.Terminal". */
    val Terminal: ImageVector by lazy {
        icon("Terminal") {
            moveTo(20.0f, 4.0f); horizontalLineTo(4.0f)
            curveTo(2.89f, 4.0f, 2.0f, 4.9f, 2.0f, 6.0f); verticalLineToRelative(12.0f)
            curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 2.0f, 2.0f); horizontalLineToRelative(16.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f); verticalLineTo(6.0f)
            curveTo(22.0f, 4.9f, 21.11f, 4.0f, 20.0f, 4.0f); close()
            moveTo(20.0f, 18.0f); horizontalLineTo(4.0f); verticalLineTo(8.0f); horizontalLineToRelative(16.0f); verticalLineTo(18.0f); close()
            moveTo(18.0f, 17.0f); horizontalLineToRelative(-6.0f); verticalLineToRelative(-2.0f); horizontalLineToRelative(6.0f); verticalLineTo(17.0f); close()
            moveTo(7.5f, 17.0f); lineToRelative(-1.41f, -1.41f); lineTo(8.67f, 13.0f); lineToRelative(-2.59f, -2.59f)
            lineTo(7.5f, 9.0f); lineToRelative(4.0f, 4.0f); lineTo(7.5f, 17.0f); close()
        }
    }

    private fun icon(name: String, pathData: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = "AhqHarnessIcons.$name", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false,
        ).path(
            fill = SolidColor(Color.Black), fillAlpha = 1f, stroke = null, strokeAlpha = 1f, strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt, strokeLineJoin = StrokeJoin.Bevel, strokeLineMiter = 1f,
            pathFillType = PathFillType.NonZero, pathBuilder = pathData,
        ).build()
}

/** HARNESS-PLUGIN-4 H5a: the [HarnessIconKey] of an icon drawn from a module's `harness.icon`: `manifest:<path>`. */
const val MANIFEST_ICON_KEY_PREFIX = "manifest:"

/** Decoded manifest icons by path, so a pill does not read and decode its file on every composition. Null = undecodable. */
private val manifestIconCache = HashMap<String, Any?>()

/**
 * ROLE-PILL-ICONS-1: the glyph of a harness, ahead of its id on a harness pill. Decorative: the id sits beside it.
 * HARNESS-PLUGIN-4 H5a: a loaded module's `harness.icon` ([com.superiorsoftllc.infantry.ui.model.HarnessManifestIcons]) wins;
 * otherwise (or when its file does not decode) the built-in table, then [AhqHarnessIcons.FALLBACK].
 */
@Composable
fun HarnessIcon(harnessId: String, size: Int = 11, tint: Color = Palette.Muted, modifier: Modifier = Modifier) {
    val factory = LocalTabFactory.current
    val icons = remember(factory) {
        com.superiorsoftllc.infantry.ui.model.HarnessManifestIcons.of(factory.statuses) { AhqHarnessIcons.resolve(it) != AhqHarnessIcons.FALLBACK }
    }
    val path = icons[harnessId.trim().lowercase()]
    val host = com.superiorsoftllc.infantry.ui.shell.LocalPlatformHost.current
    val density = LocalDensity.current
    val painter: Painter? = path?.let { p ->
        remember(p, host) {
            val cached = manifestIconCache.getOrPut(p) {
                runCatching {
                    val bytes = host.readBytes(p) ?: return@runCatching null
                    if (com.superiorsoftllc.infantry.ui.model.HarnessManifestIcons.isSvg(p)) decodeSvgPainter(bytes, density) else BitmapPainter(bytes.decodeToImageBitmap())
                }.getOrNull()
            }
            cached as? Painter
        }
    }
    if (painter != null) {
        Icon(
            painter, contentDescription = null, tint = tint,
            modifier = modifier.size(size.dp).testTag(harnessIconTag(harnessId)).semantics { harnessIconKey = MANIFEST_ICON_KEY_PREFIX + path },
        )
        return
    }
    val key = AhqHarnessIcons.resolve(harnessId)
    Icon(
        AhqHarnessIcons.forHarness(harnessId), contentDescription = null, tint = tint,
        modifier = modifier.size(size.dp).testTag(harnessIconTag(harnessId)).semantics { harnessIconKey = key },
    )
}
