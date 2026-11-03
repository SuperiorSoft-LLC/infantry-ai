package com.superiorsoftllc.infantry.ui.voice

import kotlinx.serialization.Serializable

/** VOICE-1 (contract 1.104.0): the daemon's `spoken` push. Only [say], the short text an agent wrote, is ever read out. */
@Serializable
data class SpokenNotice(
    val id: String,
    val at: Long = 0,
    val say: String,
    val level: String = "info",
    val projectId: String? = null,
    val projectName: String? = null,
    val ticketId: String? = null,
)

/** Alert levels, lowest first; [wire] is the name on the wire. An unknown name reads as [INFO]. */
enum class VoiceLevel(val wire: String, val label: String) {
    INFO("info", "Info"), NEEDS_OWNER("needs-owner", "Needs owner"), URGENT("urgent", "Urgent");

    companion object {
        fun fromWire(value: String?): VoiceLevel = entries.firstOrNull { it.wire == value } ?: INFO
    }
}

/** The voice settings the config modal edits. Window-lifetime, like every other flag there. */
data class VoiceSettings(
    val enabled: Boolean = true,
    val minLevel: VoiceLevel = VoiceLevel.INFO,
    val quietHoursEnabled: Boolean = false,
    /** Local hour 0..23 quiet hours begin / end; the range may wrap midnight (22 -> 8). */
    val quietStartHour: Int = 22,
    val quietEndHour: Int = 8,
    val onlyWhenUnfocused: Boolean = false,
    val volumePercent: Int = 100,
    /** The platform voice to use; null = the platform default. */
    val voiceName: String? = null,
)

/** What the platform can do. A host without speech returns false from [speak] and the caller falls back to [beep]. */
interface Speaker {
    /** Read [text] aloud; [interrupt] cuts off anything being said. False = no voice available (nothing was spoken). */
    fun speak(text: String, volumePercent: Int, voiceName: String?, interrupt: Boolean): Boolean

    /** A plain system beep, the fallback when there is no voice. */
    fun beep()

    /** Voices to offer in the settings; empty = only the default. */
    fun voices(): List<String> = emptyList()
}

enum class SpeechDecision(val speaks: Boolean) {
    SPEAK(true), SPEAK_INTERRUPT(true),
    SKIP_DUPLICATE(false), SKIP_DISABLED(false), SKIP_LEVEL(false), SKIP_QUIET(false), SKIP_FOCUSED(false), SKIP_RATE(false),
}

/** What is read out: the project name first (an alert may come from any project), then the agent's text. */
fun spokenText(n: SpokenNotice): String = n.projectName?.takeIf { it.isNotBlank() }?.let { "$it. ${n.say}" } ?: n.say

/**
 * Decides whether a notice is spoken: never twice (by id, and by project + text inside [dedupeWindowMs]), at most
 * [maxPerMinute] a minute (urgent is exempt and interrupts what is being said), and only when the settings allow it.
 * A notice that is not spoken is still shown as a toast by the caller, except a duplicate.
 */
class SpeechGate(private val maxPerMinute: Int = 6, private val dedupeWindowMs: Long = 10 * 60_000L) {
    private val seenIds = LinkedHashSet<String>()
    private val seenText = HashMap<String, Long>()
    private val spokenAt = ArrayDeque<Long>()

    fun decide(n: SpokenNotice, s: VoiceSettings, nowMs: Long, hourOfDay: Int, windowFocused: Boolean): SpeechDecision {
        val key = "${n.projectId.orEmpty()}|${n.say.trim().lowercase()}"
        val repeat = n.id in seenIds || (seenText[key]?.let { nowMs - it < dedupeWindowMs } == true)
        seenIds += n.id
        if (seenIds.size > MAX_REMEMBERED) seenIds.remove(seenIds.first())
        seenText[key] = nowMs
        if (seenText.size > MAX_REMEMBERED) seenText.entries.removeAll { nowMs - it.value >= dedupeWindowMs }
        if (repeat) return SpeechDecision.SKIP_DUPLICATE
        val level = VoiceLevel.fromWire(n.level)
        if (!s.enabled) return SpeechDecision.SKIP_DISABLED
        if (level.ordinal < s.minLevel.ordinal) return SpeechDecision.SKIP_LEVEL
        if (s.quietHoursEnabled && inQuietHours(hourOfDay, s.quietStartHour, s.quietEndHour)) return SpeechDecision.SKIP_QUIET
        if (s.onlyWhenUnfocused && windowFocused) return SpeechDecision.SKIP_FOCUSED
        if (level == VoiceLevel.URGENT) return SpeechDecision.SPEAK_INTERRUPT
        while (spokenAt.isNotEmpty() && nowMs - spokenAt.first() >= 60_000L) spokenAt.removeFirst()
        if (spokenAt.size >= maxPerMinute) return SpeechDecision.SKIP_RATE
        spokenAt.addLast(nowMs)
        return SpeechDecision.SPEAK
    }

    private companion object { const val MAX_REMEMBERED = 200 }
}

/** True when [hour] falls in [start, end); start == end is an empty range, and start > end wraps midnight. */
fun inQuietHours(hour: Int, start: Int, end: Int): Boolean = when {
    start == end -> false
    start < end -> hour in start until end
    else -> hour >= start || hour < end
}
