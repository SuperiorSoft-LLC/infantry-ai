package com.superiorsoftllc.agenthq.keystore

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

@Serializable
class SecretRow(val name: String, val label: String, val set: Boolean, val tail: String, val importable: Boolean, val overriddenByEnv: Boolean)

@Serializable
class SecretState(val protection: String, val secrets: List<SecretRow>)

/**
 * SECRETS-1 (contract 1.102.0): `secret_set` / `secret_clear` / `secret_import` / `secret_status`, each answered by one
 * `secret_state` and pushed to every client after a change. No frame carries a value back. A refusal never echoes the frame's data.
 */
class KeystoreHandler(
    private val keystore: Keystore,
    private val known: List<KnownSecret> = DEFAULT,
    private val env: (String) -> String? = System::getenv,
    private val push: (Envelope) -> Unit = {},
    private val json: Json = Json { ignoreUnknownKeys = false; encodeDefaults = true },
) {
    fun answer(envelope: Envelope): Envelope? {
        val reply = when (envelope.type) {
            Envelope.SECRET_STATUS -> request(envelope.type) { }
            Envelope.SECRET_SET -> request(envelope.type) {
                val req = json.decodeFromString(SecretSet.serializer(), envelope.data)
                keystore.set(known(req.name).name, req.value)
            }
            Envelope.SECRET_CLEAR -> request(envelope.type) { keystore.clear(known(name(envelope)).name) }
            Envelope.SECRET_IMPORT -> request(envelope.type) {
                val secret = known(name(envelope))
                val value = secret.envAlias?.let(env)?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("nothing to import for '${secret.name}'")
                keystore.set(secret.name, value)
            }
            else -> return null
        }
        if (reply.type == Envelope.SECRET_STATE && envelope.type != Envelope.SECRET_STATUS) push(reply)
        return reply
    }

    fun state(): SecretState {
        val infos = keystore.status(known.map { it.name }).associateBy { it.name }
        return SecretState(
            keystore.protection().wire,
            known.map { k ->
                val info = infos.getValue(k.name)
                val aliasSet = k.envAlias?.let(env)?.isNotBlank() == true
                SecretRow(k.name, k.label, info.set, info.tail, importable = aliasSet && !info.set, overriddenByEnv = aliasSet && info.set)
            },
        )
    }

    private fun name(envelope: Envelope) = json.decodeFromString(SecretName.serializer(), envelope.data).name

    private fun known(name: String): KnownSecret = known.firstOrNull { it.name == name } ?: throw IllegalArgumentException("unknown secret '${name.take(64)}'")

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
    }
}
