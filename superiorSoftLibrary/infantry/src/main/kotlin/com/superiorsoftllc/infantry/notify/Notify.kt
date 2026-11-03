package com.superiorsoftllc.infantry.notify

import com.superiorsoftllc.infantry.protocol.Envelope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/** VOICE-1 levels, lowest first. Wire names are stable. */
object NotifyLevel {
    const val INFO = "info"
    const val NEEDS_OWNER = "needs-owner"
    const val URGENT = "urgent"
    val ALL = listOf(INFO, NEEDS_OWNER, URGENT)
}

/** `notify` request (contract 1.104.0): only [say], the short text an agent wrote, is ever spoken. */
@Serializable
data class NotifyRequest(
    val say: String,
    val level: String = NotifyLevel.INFO,
    val projectId: String? = null,
    val ticketId: String? = null,
)

/** The `spoken` push (contract 1.104.0). Ephemeral: not stored, not replayed. */
@Serializable
data class SpokenNotice(
    val id: String,
    /** Unix epoch milliseconds. */
    val at: Long,
    val say: String,
    val level: String,
    val projectId: String? = null,
    val projectName: String? = null,
    val ticketId: String? = null,
)

/** VOICE-1: answers `notify` with a `spoken` push to every client. A refusal answers `error`. */
class NotifyHandler(
    private val projectName: (String) -> String?,
    private val push: (Envelope) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    fun answer(envelope: Envelope): Envelope? {
        if (envelope.type != Envelope.NOTIFY) return null
        val req = try {
            json.decodeFromString(NotifyRequest.serializer(), envelope.data)
        } catch (e: Exception) {
            return Envelope(Envelope.ERROR, "notify refused: malformed request")
        }
        val say = clean(req.say)
        if (say.isEmpty()) return Envelope(Envelope.ERROR, "notify refused: say must not be blank")
        if (req.level !in NotifyLevel.ALL) {
            return Envelope(Envelope.ERROR, "notify refused: level must be one of ${NotifyLevel.ALL.joinToString(", ")}")
        }
        val projectId = req.projectId?.trim()?.takeIf { it.isNotEmpty() }
        val notice = SpokenNotice(
            id = idFactory(), at = clock(), say = say, level = req.level,
            projectId = projectId, projectName = projectId?.let { runCatching { projectName(it) }.getOrNull() },
            ticketId = req.ticketId?.trim()?.takeIf { it.isNotEmpty() },
        )
        push(Envelope(Envelope.SPOKEN, json.encodeToString(SpokenNotice.serializer(), notice)))
        return null
    }

    companion object {
        const val MAX_SAY = 200

        /** Control characters and runs of whitespace collapse to one space; capped at [MAX_SAY]. */
        fun clean(raw: String): String =
            raw.map { if (it.isISOControl() || it.isWhitespace()) ' ' else it }.joinToString("")
                .split(' ').filter { it.isNotEmpty() }.joinToString(" ").take(MAX_SAY).trim()
    }
}
