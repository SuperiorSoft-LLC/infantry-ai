// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.sites.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.HttpCall
import com.superiorsoftllc.infantry.daemon.api.HttpReply
import com.superiorsoftllc.infantry.daemon.api.ServiceEndpoint
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import com.superiorsoftllc.infantry.daemon.api.capabilities.CapabilityResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** The provider descriptor the Account hub reads (`describe`, design 1.3). Empty [apiUrl] = no provider (nothing calls a real server by accident). */
object InfantryProvider {
    const val ID = "infantry"
    const val SERVICE = "hosting"
    const val ENTITLEMENT = "hosting.servers"

    fun describe(apiUrl: String, siteUrl: String): String = buildJsonObject {
        val api = apiUrl.trim().trimEnd('/')
        val site = siteUrl.trim().trimEnd('/')
        put("id", ID)
        put("name", "Infantry AI Hosting")
        put("icon", "cloud")
        put("signIn", buildJsonObject {
            put("method", "device_code")
            put("deviceAuthorizationUrl", "$api${HostingRoutes.DEVICE_CODE}")
            put("tokenUrl", "$api${HostingRoutes.TOKEN}")
            put("revokeUrl", "$api${HostingRoutes.REVOKE}")
            put("clientId", "infantry-app")
            put("scopes", buildJsonArray { add(JsonPrimitive("account")); add(JsonPrimitive("hosting")) })
        })
        put("accountUrl", "$api${HostingRoutes.ACCOUNT}")
        if (site.isNotEmpty()) { put("billingUrl", "$site/billing"); put("signUpUrl", "$site/hosting") }
        put("consumers", buildJsonArray { add(JsonPrimitive("Infantry_Sites")) })
        put("services", buildJsonArray {
            add(buildJsonObject { put("id", SERVICE); put("name", "Hosting"); put("entitlement", ENTITLEMENT); put("section", "infantry.sites") })
        })
    }.toString()
}

/**
 * SITES-1: Infantry_Sites' daemon half. It registers the provider `infantry` with the Account hub, asks the hub for a token as
 * this module (the hub gives it only to a listed consumer), and calls the hosting API itself: UI routes and agent tools both
 * go through [SitesService], so no token ever reaches the UI, an agent, a log line or an answer.
 */
class SitesDaemonPlugin : DaemonPlugin {
    private val closers = mutableListOf<AutoCloseable>()
    private var published: AutoCloseable? = null
    private var publishedFor: String? = null

    override fun start(api: DaemonApi) {
        val service = SitesService(api.http, TokenSource { tokenOf(api) }, { api.config("sites.apiUrl").orEmpty() })

        fun syncProvider() {
            val key = api.config("sites.apiUrl").orEmpty().trim() + "|" + api.config("sites.siteUrl").orEmpty().trim()
            if (key == publishedFor) return
            published?.let { runCatching { it.close() } }
            published = null
            publishedFor = key
            val apiUrl = api.config("sites.apiUrl").orEmpty().trim()
            if (apiUrl.isEmpty() || !SitesService.urlOk(apiUrl)) return
            val text = InfantryProvider.describe(apiUrl, api.config("sites.siteUrl").orEmpty())
            published = api.services.publishEndpoint("account.provider/${InfantryProvider.ID}", ServiceEndpoint { op, _ -> if (op == "describe") text else error("unsupported operation '$op'") })
        }
        syncProvider()
        closers += api.schedule("sites-provider", SYNC_PERIOD_MS) { syncProvider() }

        api.routes.handle("GET", "state") { reply { stateOf(api, service) } }
        api.routes.handle("POST", "create") { call -> reply { val b = body(call); service.create(b.need("name"), b.str("sshPublicKey"), b.str("idempotencyKey")) } }
        api.routes.handle("POST", "power") { call -> reply { val b = body(call); service.power(b.need("id"), b.need("action")); ok() } }
        api.routes.handle("POST", "destroy") { call -> reply { service.destroy(body(call).need("id")); ok() } }

        for (t in tools(service)) api.tools.register(t)
    }

    override fun stop() {
        published?.let { runCatching { it.close() } }
        published = null
        publishedFor = null
        closers.forEach { runCatching { it.close() } }
        closers.clear()
    }

    // ---- the token: asked from the Account hub as this module; used by SitesService and never returned ---------------

    private fun tokenOf(api: DaemonApi): String {
        val r = api.capabilities.call("account.token", buildJsonObject { put("provider", InfantryProvider.ID) })
        return when (r) {
            is CapabilityResult.Ok -> (r.value as? JsonObject)?.str("accessToken") ?: throw SitesException(SitesException.UNAVAILABLE, "the account hub gave no token")
            is CapabilityResult.Failed ->
                if ("signed" in r.error) throw SitesException(SitesException.SIGNED_OUT, "sign in to Infantry AI Hosting from the Account tab")
                else throw SitesException(SitesException.UNAVAILABLE, "the account hub refused: ${r.error.take(120)}")
        }
    }

    /** What the Servers section draws: the state first (signed out, not entitled...), the servers when ready. No token in it. */
    private fun stateOf(api: DaemonApi, service: SitesService): JsonObject {
        val apiUrl = api.config("sites.apiUrl").orEmpty().trim()
        fun state(s: String, extra: JsonObjectBuilderBlock = {}) = buildJsonObject { put("state", s); extra() }
        if (apiUrl.isEmpty()) return state(SitesException.NOT_CONFIGURED) { put("message", "No hosting service is configured.") }
        val status = (api.capabilities.call("account.status", buildJsonObject { put("provider", InfantryProvider.ID) }) as? CapabilityResult.Ok)?.value as? JsonObject
        val provider = (status?.get("providers") as? JsonArray)?.firstOrNull() as? JsonObject
        val signUp = provider?.str("signUpUrl")
        if (provider == null || provider["signedIn"]?.let { (it as? JsonPrimitive)?.booleanOrNull } != true) return state(SitesException.SIGNED_OUT)
        val entitled = (provider["services"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.firstOrNull { it.str("id") == InfantryProvider.SERVICE }?.get("entitled")?.let { (it as? JsonPrimitive)?.booleanOrNull } == true
        fun reclaim(): Long? = try { service.reclaimableUntil() } catch (e: SitesException) { null }
        fun JsonObjectBuilder.extras(until: Long?) { signUp?.let { put("signUpUrl", it) }; until?.let { put("reclaimableUntil", it) } }
        if (!entitled) return state(SitesException.NOT_ENTITLED) { extras(reclaim()) }
        return try {
            val ent = (api.capabilities.call("account.entitlement", buildJsonObject { put("provider", InfantryProvider.ID); put("entitlement", InfantryProvider.ENTITLEMENT) }) as? CapabilityResult.Ok)?.value as? JsonObject
            val servers = service.list()
            state("ready") { put("servers", servers); ent?.get("limit")?.let { put("limit", it) } }
        } catch (e: SitesException) {
            val until = if (e.kind == SitesException.NOT_ENTITLED) reclaim() else null
            state(e.kind) { put("message", e.message ?: ""); extras(until) }
        }
    }

    // ---- agent tools: write / destructive, declared in module.json `daemon.tools` ------------------------------------

    private fun tools(service: SitesService): List<DaemonTool> = listOf(
        tool("infantry_sites_list", "List your hosting servers (id, name, status, ip, region, size, createdAt, destroyAt).", obj(), read = true) { service.list() },
        tool("infantry_sites_create", "Create a hosting server. Counts against your plan's server limit.",
            obj("name" to "string", "sshPublicKey" to "string", "idempotencyKey" to "string", required = listOf("name"))) { service.create(it.need("name"), it.str("sshPublicKey"), it.str("idempotencyKey")) },
        tool("infantry_sites_start", "Power on one of your hosting servers.", obj("id" to "string", required = listOf("id"))) { service.power(it.need("id"), "start"); ok() },
        tool("infantry_sites_stop", "Shut down one of your hosting servers.", obj("id" to "string", required = listOf("id"))) { service.power(it.need("id"), "stop"); ok() },
        tool("infantry_sites_destroy", "Permanently destroy one of your hosting servers and its data.", obj("id" to "string", required = listOf("id")), destructive = true) { service.destroy(it.need("id")); ok() },
    )

    private fun tool(toolName: String, desc: String, schema: JsonObject, read: Boolean = false, destructive: Boolean = false, run: (JsonObject) -> JsonElement): DaemonTool = object : DaemonTool {
        override val name = toolName
        override val description = desc
        override val inputSchema = schema
        override val annotations = buildJsonObject { if (read) put("readOnlyHint", true); if (destructive) put("destructiveHint", true) }
        override fun call(call: ToolCall): ToolResult = try {
            ToolResult(run(call.arguments).toString())
        } catch (e: SitesException) {
            ToolResult("${e.kind}: ${e.message}", isError = true)
        } catch (e: IllegalArgumentException) {
            ToolResult(e.message ?: "bad arguments", isError = true)
        }
    }

    private fun obj(vararg props: Pair<String, String>, required: List<String> = emptyList()): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { for ((k, t) in props) put(k, buildJsonObject { put("type", t) }) })
        if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
    }

    // ---- routes ------------------------------------------------------------------------------------------------------

    private fun ok() = buildJsonObject { put("ok", true) }

    private fun body(call: HttpCall): JsonObject = Json.parseToJsonElement(call.body.ifBlank { "{}" }).jsonObject

    /** 200 with the answer; a refusal of the hosting API is 200 `{ok:false, state, error}` so the UI shows its state, a bad argument is 400. */
    private fun reply(run: () -> JsonElement): HttpReply = try {
        HttpReply(200, run().toString(), "application/json")
    } catch (e: SitesException) {
        HttpReply(200, buildJsonObject { put("ok", false); put("state", e.kind); put("error", e.message ?: "") }.toString(), "application/json")
    } catch (e: IllegalArgumentException) {
        HttpReply(400, e.message ?: "bad request")
    } catch (e: Exception) {
        HttpReply(502, e.message ?: e::class.simpleName.orEmpty())
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.need(key: String): String = str(key) ?: throw IllegalArgumentException("'$key' is required")

    companion object {
        const val SYNC_PERIOD_MS = 5000L
    }
}

private typealias JsonObjectBuilderBlock = JsonObjectBuilder.() -> Unit
