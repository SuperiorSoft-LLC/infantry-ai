// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.account.daemon

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** One service a provider offers (a section of the Account tab may belong to it): its [entitlement] id is what the hub checks. */
/** [section] is the id of the UI tab that is this service's dashboard (opened from the Services list); null = no dashboard. */
data class ProviderService(val id: String, val name: String, val entitlement: String, val section: String? = null)

/**
 * ACCOUNT-1: a provider as it describes itself (`describe`, the account design doc (docs/design) 1.3). The hub knows nothing else
 * about a provider. [consumers] are the module ids that may be given this provider's access token.
 */
data class ProviderDescriptor(
    val id: String,
    val name: String,
    val icon: String,
    val description: String? = null,
    val clientId: String,
    val scopes: List<String>,
    val deviceAuthorizationUrl: String,
    val tokenUrl: String,
    val revokeUrl: String?,
    val accountUrl: String?,
    val billingUrl: String?,
    val signUpUrl: String?,
    val consumers: List<String>,
    val services: List<ProviderService>,
) {
    /** The keystore field of this provider's refresh token (letters, digits, `_`; the id is validated to that alphabet). The fixed `acct_` prefix keeps any provider id clear of the host's reserved `INFANTRY_` prefix. */
    val refreshField: String get() = "acct_${id}_refresh"

    companion object {
        private val ID = Regex("[a-z][a-z0-9_]{0,39}")

        /** https, or http to this machine only (a local test provider). Anything else is refused: a token never travels in clear text. */
        fun urlOk(url: String): Boolean {
            val u = runCatching { java.net.URI(url) }.getOrNull() ?: return false
            return u.scheme == "https" || (u.scheme == "http" && u.host in setOf("127.0.0.1", "localhost", "[::1]"))
        }

        /** Parses a `describe` answer; throws [IllegalArgumentException] with the reason (shown in the log, never a secret). */
        fun parse(json: JsonObject): ProviderDescriptor {
            fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            fun list(o: JsonObject, k: String) = (o[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            val id = str(json, "id") ?: throw IllegalArgumentException("no id")
            require(ID.matches(id)) { "id '$id' must be lowercase letters, digits and _ (starting with a letter)" }
            val sign = json["signIn"] as? JsonObject ?: throw IllegalArgumentException("no signIn")
            require((str(sign, "method") ?: "device_code") == "device_code") { "signIn.method '${str(sign, "method")}' is not supported yet (device_code)" }
            fun url(o: JsonObject, k: String, required: Boolean): String? {
                val v = str(o, k)
                if (v == null) { require(!required) { "no $k" }; return null }
                require(urlOk(v)) { "$k must be https" }
                return v
            }
            return ProviderDescriptor(
                id = id,
                name = str(json, "name") ?: id,
                icon = str(json, "icon") ?: "cloud",
                description = str(json, "description"),
                clientId = str(sign, "clientId") ?: throw IllegalArgumentException("no signIn.clientId"),
                scopes = list(sign, "scopes"),
                deviceAuthorizationUrl = url(sign, "deviceAuthorizationUrl", true)!!,
                tokenUrl = url(sign, "tokenUrl", true)!!,
                revokeUrl = url(sign, "revokeUrl", false),
                accountUrl = url(json, "accountUrl", false),
                billingUrl = url(json, "billingUrl", false),
                signUpUrl = url(json, "signUpUrl", false),
                consumers = list(json, "consumers"),
                services = (json["services"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject) }.mapNotNull { s ->
                    val sid = str(s, "id") ?: return@mapNotNull null
                    ProviderService(sid, str(s, "name") ?: sid, str(s, "entitlement") ?: sid, str(s, "section"))
                },
            )
        }

        fun parseText(text: String): ProviderDescriptor =
            parse(kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject)
    }
}

/** "alice@gmail.com" -> "a***@gmail.com"; a value that is already masked (holds `*`) is kept. */
fun maskEmail(value: String): String {
    if ('*' in value) return value
    val at = value.indexOf('@')
    return if (at <= 0) value.take(1) + "***" else value.take(1) + "***" + value.substring(at)
}
