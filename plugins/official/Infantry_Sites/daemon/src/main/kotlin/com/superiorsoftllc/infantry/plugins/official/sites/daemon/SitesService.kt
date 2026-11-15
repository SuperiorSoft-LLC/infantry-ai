// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.sites.daemon

import com.superiorsoftllc.infantry.daemon.api.http.Http
import com.superiorsoftllc.infantry.daemon.api.http.HttpResult
import com.superiorsoftllc.infantry.daemon.api.http.HttpSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.UUID

/** Why a hosting call did not happen; [kind] is what the UI keys its state on. */
class SitesException(val kind: String, message: String) : RuntimeException(message) {
    companion object {
        const val SIGNED_OUT = "signed_out"
        const val NOT_ENTITLED = "not_entitled"
        const val LIMIT = "limit"
        const val NOT_CONFIGURED = "not_configured"
        const val BAD_REQUEST = "bad_request"
        const val NOT_FOUND = "not_found"
        const val UNAVAILABLE = "unavailable"
        const val EMAIL_UNVERIFIED = "email_unverified"
    }
}

/**
 * The ONE place for the hosting API's paths (FROZEN route table from the server PM, mounted under the /api prefix of the config). A path change
 * is made here and nowhere else; the base URL is the module's `api` endpoint (module.json `daemon.endpoints`, which includes the prefix).
 */
object HostingRoutes {
    const val DEVICE_CODE = "/auth/oauth/device/code"
    const val TOKEN = "/auth/oauth/token"
    const val REVOKE = "/auth/oauth/revoke"
    const val ACCOUNT = "/core/v1/account"
    const val SERVERS = "/hosting/v1/servers"
    fun server(id: String) = "$SERVERS/$id"
    fun action(id: String, name: String) = "$SERVERS/$id/actions/$name"
}

/** The hosting API's token, asked from the Account hub as this plugin. It is used for one request and never stored or returned. */
fun interface TokenSource { fun token(): String }

/**
 * SITES-1: the hosting API client (the account design doc (docs/design) 3.2). It talks to the API only and holds no cloud logic;
 * every server id is a UUID checked here before any call, and the bearer token goes only into a secret header.
 */
class SitesService(
    private val http: Http,
    private val tokens: TokenSource,
    private val baseUrl: () -> String,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) {
    fun list(): JsonArray {
        val body = call("GET", HostingRoutes.SERVERS)
        val arr = (body as? JsonArray) ?: ((body as? JsonObject)?.let { it["items"] ?: it["servers"] } as? JsonArray)
            ?: throw SitesException(SitesException.UNAVAILABLE, "the hosting API answered something else than a server list")
        return JsonArray(arr.mapNotNull { (it as? JsonObject)?.let(::server) })
    }

    fun create(name: String, sshPublicKey: String?, idempotencyKey: String? = null): JsonObject {
        require(NAME.matches(name)) { "name must be 1 to 40 characters: lowercase letters, digits and '-', starting with a letter or digit" }
        val key = sshPublicKey?.trim()?.takeIf { it.isNotEmpty() }
        require(key == null || SSH_KEY.matches(key)) { "sshPublicKey must be one line of a public key (ssh-ed25519 AAAA...)" }
        val idem = idempotencyKey ?: newKey()
        requireUuid(idem, "idempotencyKey")
        val payload = buildJsonObject { put("name", name); if (key != null) put("sshPublicKey", key) }
        val answer = call("POST", HostingRoutes.SERVERS, payload.toString(), mapOf("Idempotency-Key" to idem))
        return server((answer as? JsonObject)?.let { (it["server"] as? JsonObject) ?: it } ?: JsonObject(emptyMap()))
    }

    /** [action] is `start`, `stop` or `refresh` (refresh re-reads the real status and ip; poll it after create and start). */
    fun power(id: String, action: String) {
        requireUuid(id, "id")
        require(action == "start" || action == "stop" || action == "refresh") { "action must be start, stop or refresh" }
        call("POST", HostingRoutes.action(id, action))
    }

    fun destroy(id: String) {
        requireUuid(id, "id")
        call("DELETE", HostingRoutes.server(id))
    }

    /** The account's `reclaimableUntil` (epoch ms): set when the paid period ended and the servers are still in the reclaim window; null otherwise. */
    fun reclaimableUntil(): Long? {
        val o = call("GET", HostingRoutes.ACCOUNT) as? JsonObject ?: return null
        return (o["reclaimableUntil"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
    }

    private fun call(method: String, path: String, body: String? = null, extra: Map<String, String> = emptyMap()): JsonElement? {
        val base = baseUrl().trim().trimEnd('/')
        if (base.isEmpty()) throw SitesException(SitesException.NOT_CONFIGURED, "no hosting service is configured")
        if (!urlOk(base)) throw SitesException(SitesException.NOT_CONFIGURED, "the hosting service address must be https")
        val first = send(base, method, path, body, extra)
        return answer(if (first.status == 401) send(base, method, path, body, extra) else first)
    }

    private fun send(base: String, method: String, path: String, body: String?, extra: Map<String, String>): HttpResult {
        val token = try { tokens.token() } catch (e: SitesException) { throw e } catch (e: Exception) { throw SitesException(SitesException.UNAVAILABLE, "could not reach the account service") }
        val headers = mutableMapOf("Accept" to "application/json")
        if (body != null) headers["Content-Type"] = "application/json"
        headers += extra
        return try {
            http.request(HttpSpec("$base$path", method = method, headers = headers, secretHeaders = mapOf("Authorization" to "Bearer $token"), body = body))
        } catch (e: Exception) {
            throw SitesException(SitesException.UNAVAILABLE, "the hosting service did not answer")
        }
    }

    private fun answer(r: HttpResult): JsonElement? {
        val json = r.body.takeIf { it.isNotBlank() }?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
        val code = ((json as? JsonObject)?.get("error") as? JsonPrimitive)?.contentOrNull
        val reason = ((json as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
        return when (r.status) {
            in 200..299 -> json
            401 -> throw SitesException(SitesException.SIGNED_OUT, "sign in again")
            403 -> if (code == "email_unverified") throw SitesException(SitesException.EMAIL_UNVERIFIED, "Verify your email address to create a server.")
                   else throw SitesException(SitesException.NOT_ENTITLED, "your plan does not include hosting")
            404 -> throw SitesException(SitesException.NOT_FOUND, "no such server")
            409 -> if (code == "scheduled_for_destroy") throw SitesException(SitesException.BAD_REQUEST, reason ?: "this server is scheduled to be destroyed")
                   else throw SitesException(SitesException.LIMIT, "you're at your plan's server limit")
            429 -> {
                val secs = r.headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }?.value?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
                throw SitesException(SitesException.UNAVAILABLE, if (secs != null) "slow down: too many requests, try again in $secs s" else "slow down: too many requests, try again later")
            }
            400, 422 -> throw SitesException(SitesException.BAD_REQUEST, reason ?: "the request was refused")
            502 -> throw SitesException(SitesException.UNAVAILABLE, "The hosting provider had a problem. Try again in a minute.")
            // The server's own words win over a bare status: {"error":"not_configured","message":"hosting is not configured on this server"}.
            else -> {
                val kind = if (code == "not_configured") SitesException.NOT_CONFIGURED else SitesException.UNAVAILABLE
                val text = reason ?: code?.replace('_', ' ')
                throw SitesException(kind, if (text != null) "$text (HTTP ${r.status})" else "the hosting service answered HTTP ${r.status}")
            }
        }
    }

    private fun server(o: JsonObject): JsonObject {
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return buildJsonObject {
            put("id", s("id")); put("name", s("name")); put("status", s("status")); put("ip", s("ip"))
            put("region", s("region")); put("size", s("size")); put("createdAt", s("createdAt")); put("destroyAt", s("destroyAt"))
        }
    }

    companion object {
        private val UUID_RE = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val NAME = Regex("[a-z0-9][a-z0-9-]{0,39}")
        private val SSH_KEY = Regex("(ssh-[a-z0-9]+|ecdsa-sha2-[a-z0-9]+|sk-[a-z0-9@.-]+) [A-Za-z0-9+/=]+( [^\\r\\n]*)?")

        fun isUuid(v: String) = UUID_RE.matches(v)
        fun requireUuid(v: String, what: String) = require(isUuid(v)) { "$what must be a UUID" }

        /** https, or http to this machine only (a local test service): a token never travels in clear text. */
        fun urlOk(url: String): Boolean {
            val u = runCatching { java.net.URI(url) }.getOrNull() ?: return false
            return u.scheme == "https" || (u.scheme == "http" && u.host in setOf("127.0.0.1", "localhost", "[::1]"))
        }
    }
}
