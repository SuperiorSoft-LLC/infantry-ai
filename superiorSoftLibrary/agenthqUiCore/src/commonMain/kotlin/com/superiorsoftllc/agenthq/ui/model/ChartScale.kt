package com.superiorsoftllc.agenthq.ui.model

import androidx.compose.runtime.mutableStateMapOf

/** Which Stats chart a scale belongs to. */
enum class ChartKind(val key: String, val defaultMax: Long, val presets: List<Long>) {
    /** Context tokens per request. */
    CONTEXT("context", 200_000L, listOf(128_000L, 200_000L, 500_000L, 1_000_000L)),

    /** Billed tokens, running total. */
    BILLED("billed", 2_000_000L, listOf(500_000L, 1_000_000L, 2_000_000L)),
}

/** What one session chose for one chart. [Default] is "never changed": the shared default of the [ChartKind]. */
sealed interface ChartScaleSetting {
    data object Default : ChartScaleSetting
    /** A preset or a custom value, in tokens. */
    data class Fixed(val tokens: Long) : ChartScaleSetting
    data object ModelLimit : ChartScaleSetting
    data object Fit : ChartScaleSetting

    fun encode(): String = when (this) {
        Default -> "default"
        is Fixed -> "fixed:$tokens"
        ModelLimit -> "limit"
        Fit -> "fit"
    }

    companion object {
        fun decode(raw: String?): ChartScaleSetting = when {
            raw == null -> Default
            raw == "limit" -> ModelLimit
            raw == "fit" -> Fit
            raw.startsWith("fixed:") -> raw.removePrefix("fixed:").toLongOrNull()?.takeIf { it > 0 }?.let { Fixed(it) } ?: Default
            else -> Default
        }
    }
}

/** The axis a chart draws: 0 to [max] tokens; [overScale] when the series goes past it (the line is clipped, a marker shows). */
data class ChartAxis(val max: Long, val overScale: Boolean)

/**
 * One scale rule for every session and harness: no harness name reaches here. [seriesMax] is the largest plotted value,
 * [modelLimit] the model's window when known. Model limit with no known limit falls back to the shared default.
 */
object ChartScale {
    fun resolve(kind: ChartKind, setting: ChartScaleSetting, seriesMax: Long, modelLimit: Long?): ChartAxis {
        val peak = seriesMax.coerceAtLeast(0L)
        val max = when (setting) {
            ChartScaleSetting.Default -> kind.defaultMax
            is ChartScaleSetting.Fixed -> setting.tokens
            ChartScaleSetting.ModelLimit -> modelLimit?.takeIf { it > 0 } ?: kind.defaultMax
            ChartScaleSetting.Fit -> peak.coerceAtLeast(1L)
        }
        return ChartAxis(max, peak > max)
    }

    /** The dashed model-limit line is drawn only when it falls inside the axis. */
    fun limitInside(axis: ChartAxis, limit: Long?): Boolean = limit != null && limit > 0 && limit <= axis.max

    /** "150k", "1.5m", "200000" -> tokens; null when it is not a positive number. */
    fun parseTokens(text: String): Long? {
        val t = text.trim().lowercase().replace(",", "").replace("_", "")
        if (t.isEmpty()) return null
        val mult = when (t.last()) { 'k' -> 1_000.0; 'm' -> 1_000_000.0; else -> 1.0 }
        val num = (if (mult == 1.0) t else t.dropLast(1)).toDoubleOrNull() ?: return null
        val v = Math.round(num * mult)
        return v.takeIf { it > 0 }
    }
}

/**
 * Per-session chart scale choices. Backed by the host's UI-preference store (desktop Preferences, Android
 * SharedPreferences) keyed by session id and chart; the in-memory map keeps the view live and is the only store
 * when a host persists nothing. A session never changed reads [ChartScaleSetting.Default], so one session's choice
 * never reaches another.
 */
class ChartScales(
    private val load: (String) -> String? = { null },
    private val save: (String, String) -> Unit = { _, _ -> },
) {
    private val memory = mutableStateMapOf<String, ChartScaleSetting>()

    private fun key(sessionId: String, kind: ChartKind) = "chartScale.${kind.key}.$sessionId"

    fun get(sessionId: String, kind: ChartKind): ChartScaleSetting {
        val k = key(sessionId, kind)
        return memory[k] ?: ChartScaleSetting.decode(load(k)).also { memory[k] = it }
    }

    fun set(sessionId: String, kind: ChartKind, setting: ChartScaleSetting) {
        val k = key(sessionId, kind)
        memory[k] = setting
        save(k, setting.encode())
    }

    /** "Reset to default": back to the shared default (stored as "default"; a pref store cannot delete). */
    fun reset(sessionId: String, kind: ChartKind) = set(sessionId, kind, ChartScaleSetting.Default)
}
