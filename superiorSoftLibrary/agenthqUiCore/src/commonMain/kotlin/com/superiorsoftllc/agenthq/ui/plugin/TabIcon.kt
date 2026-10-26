package com.superiorsoftllc.agenthq.ui.plugin

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * PLUGINS-1: a tab icon drawn as a 2px outline on the 24dp grid (round caps and joins), tinted by whoever draws it.
 * Each [paths] entry is one stroked path, so a module draws its icon from lines, curves and arcs, never a text glyph.
 */
fun tabIcon(name: String, vararg paths: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name = "tab.$name", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .apply {
            for (p in paths) {
                path(
                    fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = p,
                )
            }
        }.build()

/** A full circle, as two arcs, for [tabIcon] paths. */
fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx + r, cy)
    arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = cx - r, y1 = cy)
    arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = cx + r, y1 = cy)
    close()
}
