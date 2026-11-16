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

/** The provider descriptor the Account hub reads (`describe`, design 1.3). No `api` endpoint = no provider (nothing calls a real server by accident). */
object InfantryProvider {
    const val ID = "infantry"
    const val SERVICE = "hosting"
    /** The account permission this plugin requires to show the page (the account hub checks "permissions include X"). */
    const val ENTITLEMENT = "hosting.servers.read"

    fun describe(apiUrl: String, siteUrl: String): String = buildJsonObject {
        val api = apiUrl.trim().trimEnd('/')
        val site = siteUrl.trim().trimEnd('/')
        put("id", ID)
        put("name", "Infantry AI Hosting")
        put("icon", "cloud")
        put("description", "Servers for your sites, managed on the Infantry AI Hosting website.")
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
    private var published: AutoCloseable? = null

    override fun start(api: DaemonApi) {
        // ENDPOINT-ENV-1: where the hosting service lives is developer config (module.json `daemon.endpoints`, picked by the build
        // environment), never a user setting: a customer signs in and Sites works with zero setup.
        val service = SitesService(api.http, TokenSource { tokenOf(api) }, { apiUrlOf(api) })
        val apiUrl = apiUrlOf(api)
        if (apiUrl.isNotEmpty() && SitesService.urlOk(apiUrl)) {
            val text = InfantryProvider.describe(apiUrl, api.endpoint(SITE_ENDPOINT).orEmpty())
            published = api.services.publishEndpoint("account.provider/${InfantryProvider.ID}", ServiceEndpoint { op, _ -> if (op == "describe") text else error("unsupported operation '$op'") })
        } else {
            api.log("no usable '$API_ENDPOINT' endpoint for environment '${api.environment}' (module.json daemon.endpoints): hosting is not offered")
        }

        api.routes.handle("GET", "state") { reply { stateOf(api, service) } }

        // SITES-SSH-1: zero-config SSH. The device key lives in this plugin's keystore entry; pins in plugin storage (public data).
        val pins: HostKeyPins = try { StorageHostKeyPins(api.storage) } catch (e: Exception) { api.log("plugin storage unavailable: SSH host keys are pinned in memory only"); MemoryHostKeyPins() }
        val ssh = SitesSsh(
            servers = { service.list().mapNotNull { it as? JsonObject }.map { (it.str("id") ?: "") to (it.str("name") ?: "") }.filter { it.first.isNotEmpty() } },
            key = DeviceKey({ api.pluginSecrets }),
            certs = CertCache(CertIssuer { id, pub -> service.sshCertificate(id, pub) }),
            exec = SshExec(pins) { api.log(it) },
            limits = { limitsOf(api) },
        )

        for (t in tools(service, ssh)) api.tools.register(t)
    }

    override fun stop() {
        published?.let { runCatching { it.close() } }
        published = null
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
        val apiUrl = apiUrlOf(api)
        fun state(s: String, extra: JsonObjectBuilderBlock = {}) = buildJsonObject { put("state", s); extra() }
        // A 401/403 from the hosting API means the cached account document is stale: re-read it so the state does not stay "active".
        fun refreshAccount() { api.capabilities.call("account.status", buildJsonObject { put("provider", InfantryProvider.ID); put("refresh", "true") }) }
        if (apiUrl.isEmpty()) return state(SitesException.NOT_CONFIGURED) { put("message", "No hosting service is configured.") }
        val status = (api.capabilities.call("account.status", buildJsonObject { put("provider", InfantryProvider.ID) }) as? CapabilityResult.Ok)?.value as? JsonObject
        val provider = (status?.get("providers") as? JsonArray)?.firstOrNull() as? JsonObject
        val signUp = provider?.str("signUpUrl")
        if (provider == null || provider["signedIn"]?.let { (it as? JsonPrimitive)?.booleanOrNull } != true) return state(SitesException.SIGNED_OUT)
        val entitled = (provider["services"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.firstOrNull { it.str("id") == InfantryProvider.SERVICE }?.get("entitled")?.let { (it as? JsonPrimitive)?.booleanOrNull } == true
        fun reclaim(): Long? = try { service.reclaimableUntil() } catch (e: SitesException) { null }
        fun JsonObjectBuilder.extras(until: Long?) { signUp?.let { put("signUpUrl", it) }; until?.let { put("reclaimableUntil", it) }; (provider["plan"] as? JsonObject)?.str("status")?.let { put("planStatus", it) } }
        if (!entitled) return state(SitesException.NOT_ENTITLED) { extras(reclaim()) }
        return try {
            val servers = service.list()
            val limit = (provider["limits"] as? JsonObject)?.get("servers.max")
            state("ready") { put("servers", servers); limit?.let { put("limit", it) }; api.endpoint(SITE_ENDPOINT)?.takeIf { it.isNotBlank() }?.let { put("siteUrl", it.trimEnd('/') + "/hosting") } }
        } catch (e: SitesException) {
            if (e.kind == SitesException.NOT_ENTITLED || e.kind == SitesException.SIGNED_OUT) refreshAccount()
            val until = if (e.kind == SitesException.NOT_ENTITLED) reclaim() else null
            state(e.kind) { put("message", e.message ?: ""); extras(until) }
        }
    }

    // ---- agent tools: read only, declared in module.json `daemon.tools` ------------------------------------

    internal fun tools(service: SitesService, ssh: SitesSsh): List<DaemonTool> = listOf(
        tool("infantry_sites_list", "List your hosting servers (id, name, status, ip, region, size, createdAt, destroyAt).", obj(), read = true) { service.list() },
        tool(SSH_EXEC, "Run a shell command on one of your hosting servers over SSH (no setup: this PC's key and a short-lived certificate). Returns exitCode, stdout, stderr (capped). 'server' is a name or id from infantry_sites_list, optional when you have one server.",
            obj("server" to "string", "command" to "string", "timeoutSec" to "integer", required = listOf("command"))) { a ->
            val timeout = a.str("timeoutSec")?.toLongOrNull()?.coerceAtMost(maxExecSec)
            val (name, r) = ssh.run(a.str("server"), a.need("command"), timeout)
            execAnswer(name, r)
        },
        tool(SSH_UPLOAD, "Write a small text file on one of your hosting servers over SSH (creates parent folders, replaces the file). 'server' as in $SSH_EXEC.",
            obj("server" to "string", "path" to "string", "content" to "string", required = listOf("path", "content"))) { a ->
            val content = (a["content"] as? JsonPrimitive)?.contentOrNull ?: throw IllegalArgumentException("'content' is required")
            val (name, r) = ssh.upload(a.str("server"), a.need("path"), content)
            execAnswer(name, r)
        },
    )

    private var maxExecSec = SshLimits.DEFAULT_EXEC_TIMEOUT_MAX_SEC

    private fun execAnswer(server: String, r: SshExecResult): JsonObject = buildJsonObject {
        put("server", server)
        r.exitCode?.let { put("exitCode", it) }
        put("stdout", r.stdout)
        put("stderr", r.stderr)
        if (r.truncated) put("truncated", true)
        if (r.timedOut) put("timedOut", true)
    }

    private fun limitsOf(api: DaemonApi): SshLimits {
        fun n(key: String, def: Long) = api.config(key)?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: def
        maxExecSec = n(SshLimits.EXEC_TIMEOUT_MAX_KEY, SshLimits.DEFAULT_EXEC_TIMEOUT_MAX_SEC)
        return SshLimits(
            connectTimeoutMs = n(SshLimits.CONNECT_TIMEOUT_KEY, SshLimits.DEFAULT_CONNECT_TIMEOUT_SEC) * 1000,
            execTimeoutMs = n(SshLimits.EXEC_TIMEOUT_KEY, SshLimits.DEFAULT_EXEC_TIMEOUT_SEC) * 1000,
            maxOutputBytes = n(SshLimits.MAX_OUTPUT_KEY, SshLimits.DEFAULT_MAX_OUTPUT_BYTES.toLong()).toInt(),
            maxUploadBytes = n(SshLimits.MAX_UPLOAD_KEY, SshLimits.DEFAULT_MAX_UPLOAD_BYTES.toLong()).toInt(),
        )
    }

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
        } catch (e: Exception) {
            ToolResult("unavailable: could not complete the request (${e::class.simpleName})", isError = true)
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

    private fun apiUrlOf(api: DaemonApi): String = api.endpoint(API_ENDPOINT).orEmpty().trim()

    companion object {
        /** module.json `daemon.endpoints` names: the hosting API (with its /api prefix) and the plans/billing site. */
        const val API_ENDPOINT = "api"
        const val SITE_ENDPOINT = "site"
        const val SSH_EXEC = "infantry_sites_ssh_exec"
        const val SSH_UPLOAD = "infantry_sites_ssh_upload"
    }
}

private typealias JsonObjectBuilderBlock = JsonObjectBuilder.() -> Unit
