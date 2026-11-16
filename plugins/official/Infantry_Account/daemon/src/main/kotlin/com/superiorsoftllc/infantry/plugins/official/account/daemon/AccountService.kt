// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.account.daemon

import com.superiorsoftllc.infantry.daemon.api.ServiceEndpoint
import com.superiorsoftllc.infantry.daemon.api.http.Http
import com.superiorsoftllc.infantry.daemon.api.http.HttpSpec
import com.superiorsoftllc.infantry.daemon.api.pluginsecrets.PluginSecrets
import com.superiorsoftllc.infantry.daemon.api.pluginsecrets.SecretValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.util.UUID

/** A device-code sign-in in progress (or its end state). The device code never leaves this module. */
class SignInFlow(
    val id: String,
    val providerId: String,
    internal val deviceCode: SecretValue,
    val userCode: String,
    val verificationUri: String,
    val expiresAt: Long,
    internal var intervalMs: Long,
    internal var nextPollAt: Long,
    var state: String = PENDING,
    var error: String? = null,
) {
    companion object {
        const val PENDING = "pending"
        const val DENIED = "denied"
        const val EXPIRED = "expired"
        const val FAILED = "failed"
    }
}

/**
 * ACCOUNT-1: the hub. It knows providers only through their `describe` answer (the account design doc (docs/design) 1.3) and names no
 * company. Refresh tokens live in the plugin's own keystore entry, the access token only in memory, and nothing here puts a
 * token into a log line, a notice, an exception message or an answer except [accessToken]'s own return value.
 * Every entry point is synchronized: a poll tick and a UI request never interleave.
 */
class AccountService(
    private val http: Http,
    private val secrets: PluginSecrets,
    private val endpoints: () -> Map<String, ServiceEndpoint>,
    private val notify: (title: String, body: String, dedupeKey: String) -> Unit,
    private val log: (String) -> Unit,
    private val cacheSeconds: () -> Long,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val deviceName: () -> String = { "Infantry on " + hostName() },
) {
    private class Access(val token: SecretValue, val expiresAt: Long)
    private class Doc(val fetchedAt: Long, val json: JsonObject?, val error: String?)

    private val access = HashMap<String, Access>()
    private val docs = HashMap<String, Doc>()
    private val flows = HashMap<String, SignInFlow>()

    // ---- providers ------------------------------------------------------------------------------------------------

    /** Every provider that describes itself validly; a broken one is logged and skipped. */
    @Synchronized
    fun providers(): List<ProviderDescriptor> = endpoints().entries.sortedBy { it.key }.mapNotNull { (key, ep) ->
        try {
            ProviderDescriptor.parseText(ep.call("describe", "{}")).also { require(it.id == key) { "its id '${it.id}' differs from the endpoint '$key'" } }
        } catch (e: Exception) {
            log("provider '$key' skipped: ${e.message ?: e::class.simpleName}")
            null
        }
    }

    @Synchronized
    fun provider(id: String): ProviderDescriptor = providers().firstOrNull { it.id == id } ?: throw IllegalArgumentException("unknown provider '$id'")

    fun signedIn(p: ProviderDescriptor): Boolean = secrets.get(p.refreshField) != null

    // ---- device-code sign-in --------------------------------------------------------------------------------------

    /** Starts a device-code sign-in with [providerId]: asks for a code; the caller shows [SignInFlow.userCode] and the URL. */
    @Synchronized
    fun signIn(providerId: String): SignInFlow {
        val p = provider(providerId)
        val (status, body) = postForm(p.deviceAuthorizationUrl, listOf("client_id" to p.clientId, "scope" to p.scopes.joinToString(" "), "device_name" to deviceName()))
        check(status in 200..299 && body != null) { "${p.name} refused the sign-in request (${body?.str("error") ?: "HTTP $status"})" }
        val code = body.str("device_code")
        val user = body.str("user_code")
        val uri = body.str("verification_uri_complete") ?: body.str("verification_uri")
        check(code != null && user != null && uri != null) { "${p.name} answered without a device code" }
        check(ProviderDescriptor.urlOk(uri)) { "${p.name} gave a sign-in page that is not https" }
        val t = now()
        val interval = (body.long("interval") ?: DEFAULT_INTERVAL_S).coerceAtLeast(1) * 1000
        val flow = SignInFlow(newId(), p.id, SecretValue(code), user, uri, t + (body.long("expires_in") ?: DEFAULT_EXPIRES_S) * 1000, interval, t + interval)
        flows[p.id] = flow
        return flow
    }

    @Synchronized
    fun flow(providerId: String): SignInFlow? = flows[providerId]

    /** One polling pass over the pending flows (the plugin calls it each second; tests call it with a moved clock). */
    @Synchronized
    fun tick() {
        val t = now()
        for (flow in flows.values.filter { it.state == SignInFlow.PENDING }) {
            if (t >= flow.expiresAt) {
                flow.state = SignInFlow.EXPIRED
                continue
            }
            if (t < flow.nextPollAt) continue
            val p = providers().firstOrNull { it.id == flow.providerId }
            if (p == null) {
                flow.state = SignInFlow.FAILED
                flow.error = "the provider is gone"
                continue
            }
            poll(p, flow, t)
        }
    }

    private fun poll(p: ProviderDescriptor, flow: SignInFlow, t: Long) {
        val (status, body) = try {
            postForm(p.tokenUrl, listOf("grant_type" to DEVICE_GRANT, "device_code" to flow.deviceCode.reveal(), "client_id" to p.clientId))
        } catch (e: Exception) {
            flow.nextPollAt = t + flow.intervalMs   // offline: try again at the next interval
            return
        }
        if (status in 200..299 && body?.str("access_token") != null) {
            val refresh = body.str("refresh_token")
            if (refresh == null) {
                flow.state = SignInFlow.FAILED
                flow.error = "${p.name} sent no refresh token"
                return
            }
            secrets.set(p.refreshField, SecretValue(refresh))
            remember(p, body)
            docs.remove(p.id)
            flows.remove(p.id)
            return
        }
        when (val error = body?.str("error")) {
            "authorization_pending", null -> flow.nextPollAt = t + flow.intervalMs
            "slow_down" -> {
                flow.intervalMs += SLOW_DOWN_MS
                flow.nextPollAt = t + flow.intervalMs
            }
            "access_denied" -> {
                flow.state = SignInFlow.DENIED
                flow.error = "sign-in was denied"
            }
            "expired_token" -> flow.state = SignInFlow.EXPIRED
            else -> {
                flow.state = SignInFlow.FAILED
                flow.error = body.str("error_description") ?: error
            }
        }
    }

    /** Signs out of [providerId]: asks the provider to revoke (best effort), forgets the tokens. True when it was signed in. */
    @Synchronized
    fun signOut(providerId: String): Boolean {
        val p = provider(providerId)
        val refresh = secrets.get(p.refreshField)
        if (refresh != null && p.revokeUrl != null) {
            runCatching { postForm(p.revokeUrl, listOf("token" to refresh.reveal(), "client_id" to p.clientId)) }
        }
        forget(p)
        flows.remove(p.id)
        return refresh != null
    }

    private fun forget(p: ProviderDescriptor) {
        secrets.clear(p.refreshField)
        access.remove(p.id)
        docs.remove(p.id)
    }

    // ---- tokens ---------------------------------------------------------------------------------------------------

    private fun remember(p: ProviderDescriptor, body: JsonObject) {
        val ttl = body.long("expires_in") ?: DEFAULT_TOKEN_TTL_S
        access[p.id] = Access(SecretValue(body.str("access_token")!!), now() + ttl * 1000)
    }

    /** A valid access token for [providerId] and when it expires; refreshes first when needed. The ONLY place a token leaves the hub. */
    @Synchronized
    fun accessToken(providerId: String): Pair<SecretValue, Long> {
        val p = provider(providerId)
        access[p.id]?.takeIf { it.expiresAt - EXPIRY_SKEW_MS > now() }?.let { return it.token to it.expiresAt }
        val refresh = secrets.get(p.refreshField) ?: throw IllegalStateException("not signed in to ${p.name}")
        val (status, body) = try {
            postForm(p.tokenUrl, listOf("grant_type" to "refresh_token", "refresh_token" to refresh.reveal(), "client_id" to p.clientId))
        } catch (e: Exception) {
            throw IllegalStateException("could not reach ${p.name} to refresh the sign-in")
        }
        if (status in 200..299 && body?.str("access_token") != null) {
            body.str("refresh_token")?.let { secrets.set(p.refreshField, SecretValue(it)) }   // rotating refresh token
            remember(p, body)
            return access.getValue(p.id).let { it.token to it.expiresAt }
        }
        if (body?.str("error") == "invalid_grant") {
            forget(p)
            notify("Signed out of ${p.name}", "Your sign-in expired or was revoked. Open the Account tab to sign in again.", "account.signedout.${p.id}")
            throw IllegalStateException("signed out of ${p.name}")
        }
        throw IllegalStateException("${p.name} would not refresh the sign-in (HTTP $status)")
    }

    // ---- the account document -------------------------------------------------------------------------------------

    private fun document(p: ProviderDescriptor, force: Boolean): Doc? {
        if (!signedIn(p) || p.accountUrl == null) return null
        val cached = docs[p.id]
        if (cached != null && !force && cached.fetchedAt + cacheSeconds() * 1000 > now()) return cached
        val fresh = try {
            val token = accessToken(p.id).first
            val r = http.request(HttpSpec(p.accountUrl, headers = mapOf("Accept" to "application/json"), secretHeaders = mapOf("Authorization" to "Bearer ${token.reveal()}")))
            if (r.ok) Doc(now(), Json.parseToJsonElement(r.body).jsonObject, null) else Doc(now(), cached?.json, "account lookup failed (HTTP ${r.status})")
        } catch (e: Exception) {
            Doc(now(), cached?.json, "account lookup failed: ${e.message ?: e::class.simpleName}")
        }
        docs[p.id] = fresh
        return fresh
    }

    // ---- answers (no secret in any of them) -----------------------------------------------------------------------

    private fun planActive(doc: JsonObject?): Boolean {
        val status = ((doc?.get("plan") as? JsonObject)?.str("status"))
        return status == null || status == "active"
    }

    /** Whether the account document's `permissions` list holds [id]. Generic: this hub names no product or permission. */
    private fun holds(doc: JsonObject?, id: String): Boolean =
        (doc?.get("permissions") as? JsonArray).orEmpty().any { (it as? JsonPrimitive)?.contentOrNull == id }

    private fun providerView(p: ProviderDescriptor, force: Boolean): JsonObject {
        val signedIn = signedIn(p)
        val doc = if (signedIn) document(p, force) else null
        val json = doc?.json
        return buildJsonObject {
            put("id", p.id)
            put("name", p.name)
            put("icon", p.icon)
            put("signedIn", signedIn)
            p.billingUrl?.let { put("billingUrl", it) }
            p.signUpUrl?.let { put("signUpUrl", it) }
            json?.str("email")?.let { e -> put("user", buildJsonObject { put("email", maskEmail(e)) }) }
            // The generic account state, as the server sends it (null / empty before an account exists).
            json?.let { j ->
                j.str("accountId")?.let { put("accountId", it) }
                j.str("memberRole")?.let { put("memberRole", it) }
                (j["plan"] as? JsonObject)?.let { put("plan", it) }
                put("permissions", j["permissions"] as? JsonArray ?: JsonArray(emptyList()))
                (j["limits"] as? JsonObject)?.let { put("limits", it) }
                (j["usage"] as? JsonObject)?.let { put("usage", it) }
                j.long("reclaimableUntil")?.let { put("reclaimableUntil", it) }
            }
            put("services", buildJsonArray {
                for (s in p.services) add(buildJsonObject {
                    put("id", s.id)
                    put("name", s.name)
                    put("entitled", signedIn && planActive(json) && holds(json, s.entitlement))
                })
            })
            doc?.error?.let { put("error", it) }
            flows[p.id]?.let { f ->
                put("flow", buildJsonObject {
                    put("id", f.id)
                    put("state", f.state)
                    put("userCode", f.userCode)
                    put("verificationUri", f.verificationUri)
                    put("expiresAt", f.expiresAt)
                    f.error?.let { put("error", it) }
                })
            }
        }
    }

    /** `account.status` and the UI's state: one entry per provider (or only [providerId]). [refresh] re-reads the account document now. */
    @Synchronized
    fun status(providerId: String? = null, refresh: Boolean = false): JsonObject = buildJsonObject {
        put("providers", buildJsonArray {
            for (p in providers().filter { providerId == null || it.id == providerId }) add(providerView(p, refresh))
        })
    }

    /** `account.entitlement`: whether the account holds permission [entitlement] (a generic "has permission <id>"). */
    @Synchronized
    fun entitlement(providerId: String, entitlement: String): JsonObject {
        val p = provider(providerId)
        val json = document(p, false)?.json
        val held = signedIn(p) && planActive(json) && holds(json, entitlement)
        return buildJsonObject {
            put("entitled", held)
            put("limit", JsonNull)
            put("used", JsonNull)
            put("attrs", JsonObject(emptyMap()))
        }
    }

    // ---- http helpers ---------------------------------------------------------------------------------------------

    private fun postForm(url: String, fields: List<Pair<String, String>>): Pair<Int, JsonObject?> {
        val body = fields.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }
        // Any transport failure (ConnectException, timeout, TLS...) is one clear answer, never an exception that reaches core's guard.
        val r = try {
            http.request(HttpSpec(url, method = "POST", headers = mapOf("Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json"), body = body))
        } catch (e: Exception) {
            throw IllegalStateException("could not reach the account server")
        }
        return r.status to runCatching { Json.parseToJsonElement(r.body).jsonObject }.getOrNull()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { it.isNotEmpty() }
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    companion object {
        const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        const val DEFAULT_INTERVAL_S = 5L
        const val DEFAULT_EXPIRES_S = 900L
        const val DEFAULT_TOKEN_TTL_S = 3600L
        const val SLOW_DOWN_MS = 5000L
        const val EXPIRY_SKEW_MS = 30_000L
    }
}

/** The machine name for the sign-in page's "Infantry on <host>" label; never throws. */
internal fun hostName(): String =
    (System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME") ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull())
        ?.takeIf { it.isNotBlank() } ?: "this computer"
