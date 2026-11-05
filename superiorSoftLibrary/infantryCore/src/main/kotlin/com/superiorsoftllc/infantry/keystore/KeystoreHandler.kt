// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.keystore

import com.superiorsoftllc.infantry.session.InfantryEnv

import com.superiorsoftllc.infantry.protocol.Envelope
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

/** [project] (SECRETS-2b, 1.152.0): the scope of a user-secret frame; null = the app scope. Ignored by the built-in frames. */
@Serializable
class SecretName(val name: String, val project: String? = null)

/** SECRETS-2 (1.135.0) `secret_field_set`: one field of a user secret. The value goes in once and never comes back. */
@Serializable
class SecretFieldSet(val name: String, val field: String, val value: String, val project: String? = null) {
    override fun toString(): String = "SecretFieldSet(name=$name, field=$field, project=$project)"
}

@Serializable
class SecretFieldName(val name: String, val field: String, val project: String? = null)

/** SECRETS-2b (1.152.0) `secret_move`: secret [name] from scope [from] to scope [to] (null = the app scope). No value travels. */
@Serializable
class SecretMove(val name: String, val from: String? = null, val to: String? = null)

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

/** SECRETS-2: one user secret (a name that is not a [KnownSecret]) and its fields. SECRETS-2b: [project] its scope (null = the app's). */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
class SecretEntryRow(
    val name: String,
    val fields: List<SecretFieldRow>,
    /** SECRETS-2b: the owning project; null (the app scope) is left off the wire, so an app entry reads as before. */
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val project: String? = null,
)

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
                keystore.setField(user(req.name), req.field, req.value, scope(req.project))
            }
            Envelope.SECRET_FIELD_DELETE -> change(envelope.type) {
                val req = json.decodeFromString(SecretFieldName.serializer(), envelope.data)
                if (!keystore.deleteField(user(req.name), req.field, scope(req.project))) throw IllegalArgumentException("no such field")
            }
            Envelope.SECRET_DELETE -> change(envelope.type) {
                val req = json.decodeFromString(SecretName.serializer(), envelope.data)
                if (!keystore.clear(user(req.name), scope(req.project))) throw IllegalArgumentException("no such secret")
            }
            Envelope.SECRET_MOVE -> change(envelope.type) {
                val req = json.decodeFromString(SecretMove.serializer(), envelope.data)
                keystore.move(user(req.name), scope(req.from), scope(req.to))
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
            } + keystore.projects().flatMap { project ->
                // SECRETS-2b: each project's own secrets, after the app's; a client shows them in that project's configuration.
                keystore.names(project).map { name ->
                    SecretEntryRow(name, keystore.describe(name, project).map { SecretFieldRow(it.name, it.tail) }, project)
                }
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
            require(!req.field.uppercase().startsWith("INFANTRY_")) { "INFANTRY_* fields are never handed to a plugin" }
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

    /** A frame's scope: null / blank = the app scope, else a valid project id. */
    private fun scope(project: String?): String? = project?.trim()?.takeIf { it.isNotEmpty() }?.also(Keystore::checkProject)

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

        val DEFAULT = listOf(KnownSecret(Keystore.UPDATE_TOKEN, "Update token", "INFANTRY_UPDATE_TOKEN"))

        /**
         * SEC-FIX-1 H1(b): the secrets a saved command may hand its run: the user's key/value secrets through [fields], never a
         * built-in ([known]: the update token, the daemon's own credentials). A built-in name is refused with a reason.
         */
        fun runSecrets(fields: (String) -> Map<String, String>?, known: List<KnownSecret> = DEFAULT): (String) -> Map<String, String>? = { name ->
            require(known.none { it.name == name }) { "secret '$name' is built in and never goes to a run" }
            fields(name)
        }

        /**
         * SECRETS-2b: [runSecrets] for a run of a project: the project's own secret first, then the app's ([Keystore.resolve]).
         * A built-in is refused whatever the scope.
         */
        fun projectRunSecrets(keystore: Keystore, known: List<KnownSecret> = DEFAULT): (String, String?) -> Map<String, String>? = { name, project ->
            require(known.none { it.name == name }) { "secret '$name' is built in and never goes to a run" }
            keystore.resolve(name, project)
        }
    }
}
