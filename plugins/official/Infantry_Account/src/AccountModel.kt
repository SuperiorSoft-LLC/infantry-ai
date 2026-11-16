// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.account

import com.superiorsoftllc.infantry.plugin.api.DaemonAccess
import com.superiorsoftllc.infantry.plugin.api.DaemonReply
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** The device-code sign-in the daemon is polling for; [state] is `pending`, `denied`, `expired` or `failed`. */
data class AccountFlow(val state: String, val userCode: String, val verificationUri: String, val expiresAt: Long, val error: String?)

data class AccountServiceView(val id: String, val name: String, val entitled: Boolean, val section: String? = null)

/** One provider as the daemon shows it: display data only, never a token. */
data class AccountProvider(
    val id: String,
    val name: String,
    val signedIn: Boolean,
    val userName: String?,
    val userEmail: String?,
    val planName: String?,
    val planStatus: String?,
    val billingUrl: String?,
    val signUpUrl: String?,
    val services: List<AccountServiceView>,
    val error: String?,
    val flow: AccountFlow?,
    val permissions: List<String> = emptyList(),
    val limits: Map<String, String> = emptyMap(),
    val reclaimableUntil: Long? = null,
    val description: String? = null,
) {
    /** The tab id of this service's dashboard (a service's `section`), or null when the provider has none. */
    val dashboardTabId: String? get() = services.firstNotNullOfOrNull { it.section }

    /** True once a plan exists on the account (any status). */
    val hasPlan: Boolean get() = planName != null || planStatus != null
}

/** Plain words for the permission ids the hosting server sends; an unknown id is shown as it is. */
object AccountWords {
    private val permissions = mapOf(
        "hosting.servers.read" to "Hosting: see your servers",
        "hosting.servers.create" to "Hosting: create servers",
        "hosting.servers.power" to "Hosting: start and stop servers",
        "hosting.servers.destroy" to "Hosting: remove servers",
    )

    /** What the plan includes, one readable line each: limits first ("Hosting: up to 1 server"), then permissions. */
    fun included(p: AccountProvider): List<String> {
        val out = mutableListOf<String>()
        p.limits["servers.max"]?.toLongOrNull()?.let { out += "Hosting: up to $it server${if (it == 1L) "" else "s"}" }
        for (id in p.permissions) out += permissions[id] ?: id
        return out.distinct()
    }

    fun planLabel(p: AccountProvider): String? = p.planName?.replaceFirstChar { it.uppercase() }
}

/** Parses the daemon's `GET /plugins/Infantry_Account/state` answer; null when it is not that. */
object AccountParser {
    fun parse(text: String?): List<AccountProvider>? {
        if (text.isNullOrBlank()) return null
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val providers = root["providers"] as? JsonArray ?: return null
        return providers.mapNotNull { (it as? JsonObject)?.let(::provider) }
    }

    private fun provider(o: JsonObject): AccountProvider? {
        val id = o.str("id") ?: return null
        val user = o["user"] as? JsonObject
        val plan = o["plan"] as? JsonObject
        val flow = (o["flow"] as? JsonObject)?.let { f ->
            AccountFlow(f.str("state") ?: "pending", f.str("userCode").orEmpty(), f.str("verificationUri").orEmpty(), (f["expiresAt"] as? JsonPrimitive)?.longOrNull ?: 0L, f.str("error"))
        }
        return AccountProvider(
            id = id, name = o.str("name") ?: id, signedIn = (o["signedIn"] as? JsonPrimitive)?.booleanOrNull ?: false,
            userName = user?.str("name"), userEmail = user?.str("email"),
            planName = plan?.str("name") ?: plan?.str("id"), planStatus = plan?.str("status"),
            billingUrl = o.str("billingUrl"), signUpUrl = o.str("signUpUrl"),
            services = (o["services"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { s ->
                val sid = s.str("id") ?: return@mapNotNull null
                AccountServiceView(sid, s.str("name") ?: sid, (s["entitled"] as? JsonPrimitive)?.booleanOrNull ?: false, s.str("section"))
            },
            error = o.str("error"), flow = flow,
            permissions = (o["permissions"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            limits = (o["limits"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.orEmpty().toMap(),
            reclaimableUntil = (o["reclaimableUntil"] as? JsonPrimitive)?.longOrNull,
            description = o.str("description"),
        )
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}

/** The UI half's only way to the daemon half: the three declared routes. Nothing here can ask for a token. */
class AccountClient(private val daemon: DaemonAccess?) {
    /** The providers now, or null with [lastProblem] set. */
    var lastProblem: String? = null
        private set

    suspend fun load(refresh: Boolean = false): List<AccountProvider>? {
        val d = daemon ?: return null.also { lastProblem = "The daemon is not reachable from here." }
        return when (val r = d.call("GET /plugins/Infantry_Account/state" + if (refresh) "?refresh=1" else "", null)) {
            is DaemonReply.Ok -> AccountParser.parse(r.body).also { lastProblem = if (it == null) "The daemon answered something else than the account state." else null }
            is DaemonReply.Refused -> null.also { lastProblem = r.reason }
            is DaemonReply.Failed -> null.also { lastProblem = r.reason }
        }
    }

    suspend fun signIn(providerId: String): String? = post("signin", providerId)
    suspend fun signOut(providerId: String): String? = post("signout", providerId)

    /** Null when it worked, else the reason. */
    private suspend fun post(route: String, providerId: String): String? {
        val d = daemon ?: return "The daemon is not reachable from here."
        val body = JsonObject(mapOf("provider" to JsonPrimitive(providerId))).toString()
        return when (val r = d.call("POST /plugins/Infantry_Account/$route", body)) {
            is DaemonReply.Ok -> if (r.status in 200..299) null else r.body.ifBlank { "HTTP ${r.status}" }
            is DaemonReply.Refused -> r.reason
            is DaemonReply.Failed -> r.reason
        }
    }
}
