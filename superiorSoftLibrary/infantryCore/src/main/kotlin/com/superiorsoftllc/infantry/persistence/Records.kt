package com.superiorsoftllc.infantry.persistence

import com.superiorsoftllc.infantry.protocol.EventVocabulary
import com.superiorsoftllc.infantry.protocol.FinishedPayload
import com.superiorsoftllc.infantry.protocol.StatusPayload
import com.superiorsoftllc.infantry.session.SessionState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Phase 4.5 -- the domain types the repository seam speaks. Callers (Phase 5 pods/runs/events code)
 * see these, never the SQLDelight-generated `Runs` / `Pods` / `Events` row classes and never SQL:
 * snake_case columns, Long-for-every-INTEGER and TEXT-encoded enums stop at [SqlInfantryStore].
 *
 * Phase 5b makes [RunRecord], [PodRecord] and [NodeRecord] `@Serializable`, which is what lets the
 * `pod` answer frame carry them verbatim. There is deliberately no second wire DTO: one record, as
 * [com.superiorsoftllc.infantry.session.SessionRecord] already is, so a client and the database can
 * never disagree about what a pod looks like.
 */

/** One process execution (see runs.sq for what a run is). [state] is stored by name. */
@Serializable
data class RunRecord(
    val id: String,
    val sessionId: String,
    val provider: String,
    val workingDir: String,
    val prompt: String,
    val state: SessionState,
    val startedAt: Long,
    val endedAt: Long? = null,
    val exitCode: Int? = null,
    val stdoutLines: Long = 0,
    val stderrLines: Long = 0,
    val providerSessionId: String? = null,
    val error: String? = null,
    /** The pod this run belongs to; null for a plain session run. The pod must already exist. */
    val podId: String? = null,
) {
    /** A run is in flight until it has an end time -- the crash-recovery predicate. */
    val isFinished: Boolean get() = endedAt != null
}

/**
 * One orchestration unit (see pods.sq). [state] is a free string on purpose: the pod lifecycle
 * vocabulary is Phase 5's decision, not this ticket's.
 */
@Serializable
data class PodRecord(
    val id: String,
    val goal: String,
    val state: String,
    val maxConcurrency: Int = 1,
    val createdAt: Long,
    val endedAt: Long? = null,
) {
    init {
        require(maxConcurrency >= 1) { "maxConcurrency must be >= 1, was $maxConcurrency" }
    }
}

/**
 * One event of the frozen public vocabulary (EventVocabulary / CONTRACT.md v1.0.0) -- exactly the
 * four frames a client renders, so a stored log replays as the live stream would have.
 */
sealed interface RunEvent {
    /** The wire frame type ([EventVocabulary] constant). */
    val type: String

    /** Incremental assistant text. */
    data class TextDelta(val text: String) : RunEvent {
        override val type: String get() = EventVocabulary.TEXT_DELTA
    }

    /** The model's own reasoning, kept apart from [TextDelta] so replay can tell the two apart. */
    data class Reasoning(val text: String) : RunEvent {
        override val type: String get() = EventVocabulary.REASONING
    }

    /** A lifecycle transition. */
    data class Status(val payload: StatusPayload) : RunEvent {
        override val type: String get() = EventVocabulary.STATUS
    }

    /** A daemon-side failure. */
    data class Error(val message: String) : RunEvent {
        override val type: String get() = EventVocabulary.ERROR
    }

    /** How the run ended. */
    data class Finished(val payload: FinishedPayload) : RunEvent {
        override val type: String get() = EventVocabulary.FINISHED
    }

    companion object {
        /** Same settings as the daemon's wire Json, so a stored payload IS the wire payload. */
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** The frame's `data` string as it goes on the wire. */
        fun encodePayload(event: RunEvent): String = when (event) {
            is TextDelta -> event.text
            is Reasoning -> event.text
            is Error -> event.message
            is Status -> json.encodeToString(StatusPayload.serializer(), event.payload)
            is Finished -> json.encodeToString(FinishedPayload.serializer(), event.payload)
        }

        /** Inverse of [encodePayload]; an unknown [type] is a corrupt row, so it throws. */
        fun decode(type: String, payload: String): RunEvent = when (type) {
            EventVocabulary.TEXT_DELTA -> TextDelta(payload)
            EventVocabulary.REASONING -> Reasoning(payload)
            EventVocabulary.ERROR -> Error(payload)
            EventVocabulary.STATUS -> Status(json.decodeFromString(StatusPayload.serializer(), payload))
            EventVocabulary.FINISHED -> Finished(json.decodeFromString(FinishedPayload.serializer(), payload))
            else -> throw IllegalStateException("Unknown stored event type '$type'")
        }
    }
}

/** A [RunEvent] as stored: [seq] is the global replay order and a client's resume cursor. */
data class StoredEvent(
    val seq: Long,
    val runId: String,
    val event: RunEvent,
    val vocabularyVersion: String,
    val createdAt: Long,
)

/**
 * Phase 5 -- one node of a pod's DAG (see nodes.sq). [state] / [reviewState] are the names of
 * orchestration's NodeState / ReviewState, stored as strings like PodRecord.state (the persistence
 * layer does not import orchestration types).
 */
@Serializable
data class NodeRecord(
    val id: String,
    val podId: String,
    val name: String,
    /** Index in PodSpec.nodes: the deterministic scheduling tie-breaker. */
    val specIndex: Int,
    val prompt: String,
    val state: String,
    val maxAttempts: Int = 1,
    val attempts: Int = 0,
    val reviewState: String? = null,
    val detail: String? = null,
    val createdAt: Long,
    val endedAt: Long? = null,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1, was $maxAttempts" }
        require(attempts >= 0) { "attempts must be >= 0, was $attempts" }
    }
}

/** "[nodeId] may not start until [dependsOn] has succeeded." Both nodes are in the same pod. */
@Serializable
data class NodeDependency(val nodeId: String, val dependsOn: String)

/** Run [runId] was attempt number [attempt] (1-based) of node [nodeId]. */
@Serializable
data class NodeAttempt(val runId: String, val nodeId: String, val attempt: Int)

/** One project setting, stored as JSON text so its JSON type is retained across restarts. */
data class SettingRow(
    val key: String,
    val value: String,
    val updatedAt: Long,
)

/** One durable start and its terminal outcome, when the session has ended. */
data class TicketRunRow(
    val ticketId: String,
    val sessionId: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val outcome: String? = null,
    val detail: String? = null,
)
