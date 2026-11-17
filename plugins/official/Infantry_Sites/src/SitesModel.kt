// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.sites

import com.superiorsoftllc.infantry.plugin.api.DaemonAccess
import com.superiorsoftllc.infantry.plugin.api.DaemonReply
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** One hosting server as the daemon shows it. The id is a UUID string. */
data class SiteServer(val id: String, val name: String, val status: String, val ip: String, val region: String, val size: String, val createdAt: String, val destroyAt: String, val sshReady: Boolean = true)

/** What the Servers section draws: [state] is `ready`, `signed_out`, `not_entitled`, `limit`, `not_configured`, `unavailable` or `not_found`. */
data class SitesState(val state: String, val servers: List<SiteServer>, val limit: Int?, val message: String?, val signUpUrl: String?, val reclaimableUntil: Long? = null, val planStatus: String? = null, val siteUrl: String? = null)

object SitesParser {
    fun parse(text: String?): SitesState? {
        if (text.isNullOrBlank()) return null
        val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val state = o.str("state") ?: return null
        val servers = (o["servers"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::server) }
        return SitesState(state, servers, (o["limit"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(), o.str("message"), o.str("signUpUrl"), (o["reclaimableUntil"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull(), o.str("planStatus"), o.str("siteUrl"))
    }

    private fun server(o: JsonObject): SiteServer? {
        val id = o.str("id") ?: return null
        return SiteServer(id, o.str("name") ?: id, o.str("status").orEmpty(), o.str("ip").orEmpty(), o.str("region").orEmpty(), o.str("size").orEmpty(), o.str("createdAt").orEmpty(), o.str("destroyAt").orEmpty(), (o["sshReady"] as? JsonPrimitive)?.booleanOrNull ?: true)
    }

    /** The answer of an action route: null when it worked, else (state, error) of the refusal. */
    fun failure(text: String): Pair<String, String>? {
        val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return "unavailable" to text
        return if ((o["ok"] as? JsonPrimitive)?.booleanOrNull == false) (o.str("state") ?: "unavailable") to (o.str("error") ?: "refused") else null
    }

    /** Why hosting is unavailable for this plan state. "Does not include hosting" only when the plan is active (or unknown) and still lacks it. */
    fun entitlementText(planStatus: String?): String = when (planStatus) {
        "past_due" -> "Payment failed. Update your payment method to keep hosting."
        "canceled" -> "Your subscription ended."
        else -> "Your plan does not include hosting yet."
    }

    /** What the section says for a refused action; the limit and entitlement texts are fixed. */
    fun message(state: String, error: String): String = when (state) {
        "limit" -> "You're at your plan's server limit."
        "not_entitled" -> "Your plan does not include hosting."
        "email_unverified" -> "Verify your email address to create a server."
        "signed_out" -> "Sign in to Infantry AI Hosting first."
        else -> error
    }

    /** A server time as a readable local date ("Nov 21, 2026"). Epoch milliseconds, or an ISO string; blank or unparsable gives null (never the raw value). */
    fun date(value: String?, zone: TimeZone = TimeZone.currentSystemDefault()): String? {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return null
        val instant = v.toLongOrNull()?.let { runCatching { Instant.fromEpochMilliseconds(it) }.getOrNull() }
            ?: runCatching { Instant.parse(v) }.getOrNull() ?: return null
        val d = instant.toLocalDateTime(zone).date
        return "${MONTHS[d.month.ordinal]} ${d.day}, ${d.year}"
    }

    fun date(epochMs: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): String? = date(epochMs?.toString(), zone)

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}

/** The UI half's only way to the daemon: its declared routes. Nothing here can ask for a token. */
class SitesClient(private val daemon: DaemonAccess?) {
    var lastProblem: String? = null
        private set

    suspend fun load(): SitesState? {
        val d = daemon ?: return null.also { lastProblem = "The daemon is not reachable from here." }
        return when (val r = d.call("GET /plugins/Infantry_Sites/state", null)) {
            is DaemonReply.Ok -> SitesParser.parse(r.body).also { lastProblem = if (it == null) "The daemon answered something else than the server state." else null }
            is DaemonReply.Refused -> null.also { lastProblem = r.reason }
            is DaemonReply.Failed -> null.also { lastProblem = r.reason }
        }
    }

    /** Hands off to Infantry_Account: starts its device-code sign-in; the code shows in the Services tab. Null when it started. */
    suspend fun startSignIn(): String? {
        val d = daemon ?: return "The daemon is not reachable from here."
        val body = JsonObject(mapOf("provider" to JsonPrimitive("infantry"))).toString()
        return when (val r = d.call("POST /plugins/Infantry_Account/signin", body)) {
            is DaemonReply.Ok -> if (r.status in 200..299) null else r.body.ifBlank { "HTTP ${r.status}" }
            is DaemonReply.Refused -> r.reason
            is DaemonReply.Failed -> r.reason
        }
    }

    private suspend fun post(route: String, body: JsonObject): String? {
        val d = daemon ?: return "The daemon is not reachable from here."
        return when (val r = d.call("POST /plugins/Infantry_Sites/$route", body.toString())) {
            is DaemonReply.Ok -> if (r.status !in 200..299) r.body.ifBlank { "HTTP ${r.status}" } else SitesParser.failure(r.body)?.let { (s, e) -> SitesParser.message(s, e) }
            is DaemonReply.Refused -> r.reason
            is DaemonReply.Failed -> r.reason
        }
    }
}
