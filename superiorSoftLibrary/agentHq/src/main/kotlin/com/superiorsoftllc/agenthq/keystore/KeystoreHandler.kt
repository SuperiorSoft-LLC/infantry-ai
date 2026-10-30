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

/** PLUGIN-ENDPOINTS-2 (1.138.0) `plugin_secret_get`: one field of the secret `plugin.<module>`. */
@Serializable
class PluginSecretGet(val module: String, val field: String)

/** The direct answer to [PluginSecretGet]; [value] null = not set. Never pushed, never logged. */
@Serializable
class PluginSecret(val module: String, val field: String, val value: String?) {
    override fun toString(): String = "PluginSecret(module=$module, field=$field)"
}

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
 * (a restricted policy is active: nothing can be changed and no run gets a secret).
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
 * valid name but a built-in's). Under a restricted policy every change is refused.
 */
class KeystoreHandler(
    private val keystore: Keystore,
    private val known: List<KnownSecret> = DEFAULT,
    private val env: (String) -> String? = InfantryEnv::get,
    private val push: (Envelope) -> Unit = {},
    private val json: Json = Json { ignoreUnknownKeys = false; encodeDefaults = true },
    private val restricted: () -> Boolean = { false },
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
            Envelope.PLUGIN_SECRET_GET -> return pluginSecret(envelope)
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
            locked = restricted(),
        )
    }

    /**
     * PLUGIN-ENDPOINTS-2 (D3): the value of one field of `plugin.<module>`, for the desktop host's EndpointAccess of that module.
     * FrameGate already holds it to the owner connection and refuses it under a restricted policy; refused here too under the lock.
     */
    private fun pluginSecret(envelope: Envelope): Envelope {
        val frame = envelope.type
        if (restricted()) return Envelope(Envelope.ERROR, "$frame refused: secrets are locked under a restricted policy")
        return try {
            val req = json.decodeFromString(PluginSecretGet.serializer(), envelope.data)
            Keystore.checkField(req.field)
            require(!req.field.uppercase().startsWith("AGENTHQ_")) { "AGENTHQ_* fields are never handed to a plugin" }
            val name = pluginSecretName(req.module)
            Keystore.checkName(name)
            val value = keystore.field(name, req.field)
            Envelope(Envelope.PLUGIN_SECRET, json.encodeToString(PluginSecret.serializer(), PluginSecret(req.module, req.field, value)))
        } catch (e: SerializationException) {
            Envelope(Envelope.ERROR, "$frame refused: malformed request")
        } catch (e: IllegalArgumentException) {
            Envelope(Envelope.ERROR, "$frame refused: ${e.message ?: "invalid request"}")
        } catch (e: Exception) {
            Envelope(Envelope.ERROR, "$frame failed: ${e::class.simpleName}")
        }
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
        if (restricted()) Envelope(Envelope.ERROR, "$frame refused: secrets are locked under a restricted policy") else request(frame, block)

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
        /** PLUGIN-ENDPOINTS-2: the keystore name holding a module's endpoint credentials (names are lowercase). */
        fun pluginSecretName(module: String): String = "plugin." + module.lowercase()

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
