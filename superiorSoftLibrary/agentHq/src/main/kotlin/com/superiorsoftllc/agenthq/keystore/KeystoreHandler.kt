package com.superiorsoftllc.agenthq.keystore

import com.superiorsoftllc.agenthq.session.InfantryEnv

import com.superiorsoftllc.agenthq.protocol.Envelope
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A secret the app knows how to use. [envAlias] is the older environment variable that still overrides it. */
class KnownSecret(val name: String, val label: String, val envAlias: String? = null)

@Serializable
class SecretSet(val name: String, val value: String) {
    // A secret must never reach a log line through a data-class dump.
    override fun toString(): String = "SecretSet(name=$name)"
}

@Serializable
class SecretName(val name: String)

/** SECRETS-2 (1.135.0) `secret_field_set`: one field of a user secret. The value goes in once and never comes back. */
@Serializable
class SecretFieldSet(val name: String, val field: String, val value: String) {
    override fun toString(): String = "SecretFieldSet(name=$name, field=$field)"
}

@Serializable
class SecretFieldName(val name: String, val field: String)

@Serializable
class SecretRow(val name: String, val label: String, val set: Boolean, val tail: String, val importable: Boolean, val overriddenByEnv: Boolean)

/** SECRETS-2: one field of a user secret: its name and a masked tail. */
@Serializable
class SecretFieldRow(val name: String, val tail: String)

/** SECRETS-2: one user secret (a name that is not a [KnownSecret]) and its fields. */
@Serializable
class SecretEntryRow(val name: String, val fields: List<SecretFieldRow>)

/**
 * [secrets]: the built-in single-value secrets. 1.135.0 (SECRETS-2): [entries] the user's key/value secrets, and [locked]
 * (the demo lock is on: nothing can be changed and no run gets a secret).
 */
@Serializable
class SecretState(
    val protection: String,
    val secrets: List<SecretRow>,
    val entries: List<SecretEntryRow> = emptyList(),
    val locked: Boolean = false,
)

/**
 * SECRETS-1 (contract 1.102.0): `secret_set` / `secret_clear` / `secret_import` / `secret_status`, each answered by one
 * `secret_state` and pushed to every client after a change. No frame carries a value back. A refusal never echoes the frame's data.
 * SECRETS-2 (1.135.0): `secret_field_set` / `secret_field_delete` / `secret_delete` edit the user's key/value secrets (any
 * valid name but a built-in's). Under the demo lock every change is refused.
 */
class KeystoreHandler(
    private val keystore: Keystore,
    private val known: List<KnownSecret> = DEFAULT,
    private val env: (String) -> String? = InfantryEnv::get,
    private val push: (Envelope) -> Unit = {},
    private val json: Json = Json { ignoreUnknownKeys = false; encodeDefaults = true },
    private val demoLock: () -> Boolean = { false },
) {
    fun answer(envelope: Envelope): Envelope? {
        val reply = when (envelope.type) {
            Envelope.SECRET_STATUS -> request(envelope.type) { }
            Envelope.SECRET_SET -> change(envelope.type) {
                val req = json.decodeFromString(SecretSet.serializer(), envelope.data)
                keystore.set(known(req.name).name, req.value)
            }
            Envelope.SECRET_CLEAR -> change(envelope.type) { keystore.clear(known(name(envelope)).name) }
            Envelope.SECRET_IMPORT -> change(envelope.type) {
                val secret = known(name(envelope))
                val value = secret.envAlias?.let(env)?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("nothing to import for '${secret.name}'")
                keystore.set(secret.name, value)
            }
            Envelope.SECRET_FIELD_SET -> change(envelope.type) {
                val req = json.decodeFromString(SecretFieldSet.serializer(), envelope.data)
                keystore.setField(user(req.name), req.field, req.value)
            }
            Envelope.SECRET_FIELD_DELETE -> change(envelope.type) {
                val req = json.decodeFromString(SecretFieldName.serializer(), envelope.data)
                if (!keystore.deleteField(user(req.name), req.field)) throw IllegalArgumentException("no such field")
            }
            Envelope.SECRET_DELETE -> change(envelope.type) {
                if (!keystore.clear(user(name(envelope)))) throw IllegalArgumentException("no such secret")
            }
            else -> return null
        }
        if (reply.type == Envelope.SECRET_STATE && envelope.type != Envelope.SECRET_STATUS) push(reply)
        return reply
    }

    fun state(): SecretState {
        val infos = keystore.status(known.map { it.name }).associateBy { it.name }
        val builtIn = known.map { it.name }.toSet()
        return SecretState(
            keystore.protection().wire,
            known.map { k ->
                val info = infos.getValue(k.name)
                val aliasSet = k.envAlias?.let(env)?.isNotBlank() == true
                SecretRow(k.name, k.label, info.set, info.tail, importable = aliasSet && !info.set, overriddenByEnv = aliasSet && info.set)
            },
            keystore.names().filter { it !in builtIn }.map { name ->
                SecretEntryRow(name, keystore.describe(name).map { SecretFieldRow(it.name, it.tail) })
            },
            locked = demoLock(),
        )
    }

    private fun name(envelope: Envelope) = json.decodeFromString(SecretName.serializer(), envelope.data).name

    private fun known(name: String): KnownSecret = known.firstOrNull { it.name == name } ?: throw IllegalArgumentException("unknown secret '${name.take(64)}'")

    /** A user secret's name: valid, and not a built-in's (those keep their single value and their own frames). */
    private fun user(name: String): String {
        Keystore.checkName(name)
        if (known.any { it.name == name }) throw IllegalArgumentException("'$name' is a built-in secret")
        return name
    }

    private inline fun change(frame: String, block: () -> Unit): Envelope =
        if (demoLock()) Envelope(Envelope.ERROR, "$frame refused: secrets are locked under the demo lock") else request(frame, block)

    private inline fun request(frame: String, block: () -> Unit): Envelope = try {
        block()
        Envelope(Envelope.SECRET_STATE, json.encodeToString(SecretState.serializer(), state()))
    } catch (e: SerializationException) {
        // A decode message quotes the input (and so the value): say only that the frame is malformed.
        Envelope(Envelope.ERROR, "$frame refused: malformed request")
    } catch (e: IllegalArgumentException) {
        Envelope(Envelope.ERROR, "$frame refused: ${e.message ?: "invalid request"}")
    } catch (e: Exception) {
        Envelope(Envelope.ERROR, "$frame failed: ${e::class.simpleName}")
    }

    companion object {
        val DEFAULT = listOf(KnownSecret(Keystore.UPDATE_TOKEN, "Update token", "AGENTHQ_UPDATE_TOKEN"))

        /**
         * SEC-FIX-1 H1(b): the secrets a saved command may hand its run: the user's key/value secrets through [fields], never a
         * built-in ([known]: the update token, the daemon's own credentials). A built-in name is refused with a reason.
         */
        fun runSecrets(fields: (String) -> Map<String, String>?, known: List<KnownSecret> = DEFAULT): (String) -> Map<String, String>? = { name ->
            require(known.none { it.name == name }) { "secret '$name' is built in and never goes to a run" }
            fields(name)
        }
    }
}
