package com.superiorsoftllc.agenthq.ui.sound

import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.LineKind
import com.superiorsoftllc.agenthq.ui.model.MessageOrigin
import com.superiorsoftllc.agenthq.ui.shell.PlatformHost

/** Plays an mp3 on the platform. [play] returns false (never throws) when the bytes cannot be decoded or played. */
interface SoundPlayer {
    fun play(mp3: ByteArray, volumePercent: Int): Boolean
}

/** The owner's own sound, kept as a COPY in the app's settings area (never the picked file's original path). */
interface CustomSoundStore {
    fun save(bytes: ByteArray): Boolean
    fun load(): ByteArray?
    fun clear()
}

/** Which sound a play used; [SILENT] = nothing could be played (no player, or even the default failed). */
enum class SoundSource { CUSTOM, DEFAULT, SILENT }

/** Incoming-comms sound settings. Persisted by the host under [KEY_ENABLED] / [KEY_VOLUME] / [KEY_NAME]. */
data class IncomingCommsSoundSettings(
    val enabled: Boolean = true,
    val volumePercent: Int = 100,
    /** Display name of the custom file; null = the bundled default. */
    val customName: String? = null,
) {
    fun save(host: PlatformHost, previous: IncomingCommsSoundSettings) {
        if (enabled != previous.enabled) host.saveUiPref(KEY_ENABLED, if (enabled) "on" else "off")
        if (volumePercent != previous.volumePercent) host.saveUiPref(KEY_VOLUME, volumePercent.toString())
        if (customName != previous.customName) host.saveUiPref(KEY_NAME, customName.orEmpty())
    }

    companion object {
        const val KEY_ENABLED = "incomingCommsSound.enabled"
        const val KEY_VOLUME = "incomingCommsSound.volume"
        const val KEY_NAME = "incomingCommsSound.name"

        fun load(host: PlatformHost): IncomingCommsSoundSettings = IncomingCommsSoundSettings(
            enabled = host.uiPref(KEY_ENABLED) != "off",
            volumePercent = host.uiPref(KEY_VOLUME)?.toIntOrNull()?.coerceIn(0, 100) ?: 100,
            customName = host.uiPref(KEY_NAME)?.takeIf { it.isNotBlank() },
        )
    }
}

/**
 * Plays [custom] if there is one and it plays, else the bundled [default], else nothing. Never throws: a missing, corrupt
 * or undecodable file falls back to the default, and no audio at all is silence.
 */
suspend fun playWithFallback(player: SoundPlayer?, custom: ByteArray?, volumePercent: Int, default: suspend () -> ByteArray?): SoundSource {
    if (player == null) return SoundSource.SILENT
    val v = volumePercent.coerceIn(0, 100)
    if (custom != null && custom.isNotEmpty() && runCatching { player.play(custom, v) }.getOrDefault(false)) return SoundSource.CUSTOM
    val bundled = runCatching { default() }.getOrNull() ?: return SoundSource.SILENT
    return if (runCatching { player.play(bundled, v) }.getOrDefault(false)) SoundSource.DEFAULT else SoundSource.SILENT
}

/** The generic "a message reached the owner" event; the sound listens to it, and so may a later visual effect. */
data class IncomingComms(val sessionId: String, val origin: MessageOrigin)

/** What the gate reads from one agent's transcript: how many incoming (non-owner) turns it holds, and the newest one's origin. */
data class IncomingSnapshot(val count: Int, val last: MessageOrigin?)

fun incomingSnapshots(state: AppState): Map<String, IncomingSnapshot> = state.agents.mapValues { (_, a) ->
    val incoming = a.entries.filter { it.kind == LineKind.USER && it.origin !is MessageOrigin.Owner }
    IncomingSnapshot(incoming.size, incoming.lastOrNull()?.origin)
}

/**
 * Turns transcript growth into [IncomingComms] events. Decided on the typed [MessageOrigin] only, never on text:
 * an owner turn never counts. A transcript's first observation, and the [settleMs] after a session's history is
 * requested, only set the baseline, so a connect, reconnect or history replay is never announced.
 */
class IncomingCommsGate(private val settleMs: Long = 2_000) {
    private class Track(var baseline: Int, var settleUntil: Long, var requested: Boolean)

    private val tracks = HashMap<String, Track>()
    private var generation: Any? = null

    /** [generation] changes on a (re)connect or a new daemon instance: everything is baseline again. */
    fun observe(nowMs: Long, generation: Any?, snapshots: Map<String, IncomingSnapshot>, historyRequested: Set<String>): List<IncomingComms> {
        if (generation != this.generation) { tracks.clear(); this.generation = generation }
        tracks.keys.retainAll(snapshots.keys)
        val out = ArrayList<IncomingComms>()
        for ((id, snap) in snapshots) {
            val t = tracks[id]
            if (t == null) { tracks[id] = Track(snap.count, nowMs + settleMs, id in historyRequested); continue }
            if (id in historyRequested && !t.requested) { t.requested = true; t.settleUntil = nowMs + settleMs }
            if (nowMs < t.settleUntil || snap.count < t.baseline) { t.baseline = snap.count; continue }
            if (snap.count == t.baseline) continue
            t.baseline = snap.count
            snap.last?.let { out += IncomingComms(id, it) }
        }
        return out
    }
}

/** Lets one play through per [cooldownMs], so a burst of messages is ONE sound. */
class BurstCooldown(private val cooldownMs: Long = 1_500) {
    private var last = Long.MIN_VALUE

    fun tryAcquire(nowMs: Long): Boolean {
        if (last != Long.MIN_VALUE && nowMs - last < cooldownMs) return false
        last = nowMs
        return true
    }
}
