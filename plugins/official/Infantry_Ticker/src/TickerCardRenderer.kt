// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.ticker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.ChatCard
import com.superiorsoftllc.infantry.ui.ChatCardRenderer
import com.superiorsoftllc.infantry.ui.Palette
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.math.roundToLong

const val TICKER_CARD_TAG = "ticker-card"
const val TICKER_VALUE_TAG = "ticker-card-value"
const val TICKER_POINT_TAG = "ticker-card-point"

/**
 * CARD-UPDATE-1: what a `card:ticker` body says: `{"title": "...", "unit": "...", "items": [{"label"?, "value": number}]}`.
 * The body is the FOLDED state when the card was appended to (the chat folds `op=append` fences into the first one). An
 * item without a numeric value is dropped; a malformed body reads as no points.
 */
data class TickerCardBody(val title: String, val unit: String, val points: List<Point>) {
    data class Point(val label: String, val value: Double)

    /** The last value minus the first (0 with fewer than two points). */
    val change: Double get() = if (points.size < 2) 0.0 else points.last().value - points.first().value

    companion object {
        fun parse(body: String): TickerCardBody {
            val o = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
            val points = (o?.get("items") as? JsonArray).orEmpty().mapNotNull { e ->
                val i = e as? JsonObject ?: return@mapNotNull (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.let { Point("", it) }
                val v = (i["value"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return@mapNotNull null
                Point(i.s("label"), v)
            }
            return TickerCardBody(o?.s("title").orEmpty(), o?.s("unit").orEmpty(), points)
        }
    }
}

/** CARD-UPDATE-1: [v] with at most two decimals, no trailing zeros ("101.2", "-3", "0.05"). Common code: no String.format. */
fun tickerNumber(v: Double): String {
    val cents = (abs(v) * 100).roundToLong()
    val whole = cents / 100
    val frac = cents % 100
    val sign = if (v < 0 && cents != 0L) "-" else ""
    return when {
        frac == 0L -> "$sign$whole"
        frac % 10 == 0L -> "$sign$whole.${frac / 10}"
        else -> "$sign$whole.${frac.toString().padStart(2, '0')}"
    }
}

/**
 * CARD-UPDATE-1: draws a `card:ticker` block as a bordered card: the title, the latest value with its unit, the change since
 * the first value (green up, red down), a sparkline of every value, and every value as a small labelled row so nothing is
 * hidden behind the line. Appends fold into this card (the chat does that), so it always shows the whole series.
 */
object TickerCardRenderer : ChatCardRenderer {
    @Composable
    override fun Render(card: ChatCard, modifier: Modifier) {
        val body = remember(card.body) { TickerCardBody.parse(card.body) }
        Column(
            modifier.fillMaxWidth().testTag(TICKER_CARD_TAG)
                .border(1.dp, Palette.Border, RoundedCornerShape(10.dp))
                .background(Palette.Panel, RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(body.title.ifEmpty { "Ticker" }, color = Palette.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                if (body.points.isNotEmpty()) Text("${body.points.size} values", color = Palette.Muted, fontSize = 11.sp)
            }
            val last = body.points.lastOrNull()
            if (last == null) {
                Text("No values in this card.", color = Palette.Muted, fontSize = 12.sp)
                return@Column
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    tickerNumber(last.value) + if (body.unit.isNotEmpty()) " ${body.unit}" else "",
                    color = Palette.Text, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.testTag(TICKER_VALUE_TAG),
                )
                if (body.points.size > 1) {
                    Spacer(Modifier.width(8.dp))
                    val ch = body.change
                    val up = ch >= 0
                    Text(
                        (if (up) "▲ +" else "▼ ") + tickerNumber(ch),
                        color = if (up) Palette.Ok else Palette.Danger, fontSize = 12.sp,
                    )
                }
            }
            if (body.points.size > 1) Sparkline(body.points.map { it.value }, if (body.change >= 0) Palette.Ok else Palette.Danger)
            body.points.forEach { p ->
                Row(Modifier.fillMaxWidth().testTag(TICKER_POINT_TAG)) {
                    Text(p.label, color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text(tickerNumber(p.value), color = Palette.Text, fontSize = 11.sp)
                }
            }
        }
    }

    @Composable
    private fun Sparkline(values: List<Double>, color: androidx.compose.ui.graphics.Color) {
        val min = values.min()
        val span = (values.max() - min).takeIf { it > 0 } ?: 1.0
        Canvas(Modifier.fillMaxWidth().height(36.dp).semantics { contentDescription = "Trend of ${values.size} values" }) {
            val step = size.width / (values.size - 1)
            val path = Path()
            values.forEachIndexed { i, v ->
                val pt = Offset(i * step, size.height - ((v - min) / span).toFloat() * size.height)
                if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
            }
            drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
        }
    }
}
