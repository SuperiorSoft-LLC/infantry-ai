package com.superiorsoftllc.infantry.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Contract 1.3.0: how the sessions a connection creates are spawned, carried as JSON in the data of a
 * `new` frame: `{"provider":"claude","model":"sonnet"}`.
 *
 * Every field is optional. [provider] is an id exactly as the `providers` frame lists it; null =
 * the daemon's default provider. [model] is passed to that provider's CLI as its model flag; null =
 * the provider's configured default. The options hold for every session the connection creates until
 * the next `new` frame replaces them, and a `new` with empty data clears them (the pre-1.3.0 meaning).
 *
 * Contract 1.4.0 adds [agentId]: spawn from that agent profile (`agent_list`). The session takes the
 * profile's provider, model and role, and the profile's instructions go ahead of its first prompt.
 * [provider] and [model], when also given, override the profile's for this spawn; a profile's model
 * is dropped when the spawn moves it to another provider, because a model belongs to one provider.
 *
 * Plain Kotlin and kotlinx.serialization only, so a non-JVM client can share the type.
 */
@Serializable
data class SpawnOptions(
    val provider: String? = null,
    val model: String? = null,
    val agentId: String? = null,
    /** The project this console belongs to; recorded on the session so the roster and role mail see it. */
    val projectId: String? = null,
    /** CP-4(e): the reasoning effort the session's first run starts with (a `/effort` typed in the spawn box); null = the harness default. Additive. */
    val effort: String? = null,
    /**
     * Contract 1.43.0 (ADDITIVE, role-first): attach the session to this role. A profile-less session with a role
     * runs on the role's binding; with [agentId] it must name the profile's own role, else the spawn is refused.
     */
    val role: String? = null,
    /**
     * Contract 1.76.0 (BUG-PERM-1, ADDITIVE): this session's own Tool approval (`deny` | `allowEdits` | `allowAll`),
     * over the global Settings value; null = the global value. An unknown value refuses the spawn.
     */
    val toolApproval: String? = null,
    /**
     * ASK-TIMER (1.139.0, ADDITIVE): this session's own prompt timer in seconds (0..86400, 0 = no timer) over the
     * global `prompts.timeoutSec`; null = the global value. Out of range refuses the spawn.
     */
    val promptTimeoutSec: Int? = null,
    /** ASK-TIMER (1.139.0, ADDITIVE): this session's `prompts.onTimeout` (`deny` | `approve`); null = global. Unknown refuses the spawn. */
    val promptOnTimeout: String? = null,
    /**
     * CONTEXT-LIMIT-1 (1.146.0, ADDITIVE): the session's context limit in tokens (10000..10000000); null = the harness's own
     * window. How it is honoured is the harness's (`providers` lists `contextLimit` per harness); out of range refuses the spawn.
     */
    val contextLimit: Int? = null,
) {
    companion object {
        /** Decode a `new` frame's JSON data, or throw [IllegalArgumentException] saying what is wrong. */
        fun decode(text: String): SpawnOptions = StrictJson.decode(serializer(), text, "new payload")
    }
}

/**
 * Contract 1.3.0: one provider a daemon can spawn, as the `providers` answer lists it.
 *
 * [default] marks the provider a spawn gets when it names none (the one `ready` and `/health` call
 * `provider`). [defaultModel] is the model that provider's runs use when a spawn names none, as the
 * daemon resolves it right now; null = no model flag, so the CLI's own configured model applies.
 */
@Serializable
data class ProviderInfo(
    val id: String,
    val default: Boolean = false,
    val defaultModel: String? = null,
    /**
     * AGENT-UX-10 (ADDITIVE): the model ids this provider can run, as the daemon last read them from
     * the provider CLI itself (`opencode models` for the `opencode` provider). `null` = the daemon has
     * no catalogue for this provider, so a picker offers only [defaultModel]. Never hand-maintained.
     */
    val models: List<String>? = null,
    /** AGENT-UX-10 (ADDITIVE): [models] is last-known data served after a failed refresh. */
    val modelsStale: Boolean = false,
    /**
     * CLIPATH-1 (1.73.0, ADDITIVE): false when this provider's CLI cannot be found (Settings override, known install
     * locations, PATH); [unavailableReason] then names it and lists every path tried. A spawn on it would fail.
     */
    val available: Boolean = true,
    val unavailableReason: String? = null,
    /**
     * CONTEXT-LIMIT-1 (1.146.0, ADDITIVE): how this provider honours a session's `contextLimit`: `supported` (the CLI keeps
     * the conversation under it), `best_effort` (approximated, e.g. compacted between turns) or `unsupported` (recorded, not
     * applied); [contextLimitHow] says how in words. Null from an older daemon.
     */
    val contextLimit: String? = null,
    val contextLimitHow: String? = null,
    /** EFFORT-1 (1.148.0, ADDITIVE): how this provider honours a session's `effort` (`supported` / `best_effort` / `unsupported`) and how, in words. Null from an older daemon. */
    val effort: String? = null,
    val effortHow: String? = null,
)

/**
 * The decoder for a client COMMAND's JSON payload (`new`, `agent_save`). STRICT on purpose, unlike the
 * lenient decoding everywhere else: a command the daemon does not fully understand is refused, never
 * half-applied. A misspelt key (`"provder"`) or a field from a newer contract must not quietly fall back
 * to a default the client did not ask for.
 */
object StrictJson {
    private val strict = Json { ignoreUnknownKeys = false }

    fun <T> decode(serializer: KSerializer<T>, text: String, what: String): T = try {
        strict.decodeFromString(serializer, text)
    } catch (e: SerializationException) {
        throw IllegalArgumentException("malformed $what: ${e.message?.lineSequence()?.firstOrNull() ?: "not a JSON object"}")
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("malformed $what: ${e.message?.lineSequence()?.firstOrNull() ?: "not a JSON object"}")
    }
}
