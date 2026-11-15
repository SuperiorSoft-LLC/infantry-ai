// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.account.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.HttpCall
import com.superiorsoftllc.infantry.daemon.api.HttpReply
import com.superiorsoftllc.infantry.daemon.api.capabilities.CapabilityCall
import com.superiorsoftllc.infantry.plugin.api.Notification
import com.superiorsoftllc.infantry.plugin.api.NotificationAction
import com.superiorsoftllc.infantry.plugin.api.NotificationLevel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Who may call what (a capability call carries `source`: `plugin:<module>`, `session:<id>`, `script:<name>` or `cli`).
 * `account.token` goes only to a module the provider lists as a consumer: never a session (an agent never holds a customer
 * token), the CLI or a script. Signing in and out is the owner's act: the CLI, never a session or a script.
 */
object AccountGate {
    fun tokenRefusal(source: String, provider: ProviderDescriptor): String? =
        if (source.startsWith("plugin:") && source.removePrefix("plugin:") in provider.consumers) null
        else "account.token is given only to the modules ${provider.name} lists as consumers; '$source' is not one"

    fun ownerRefusal(capability: String, source: String): String? =
        if (source == "cli") null else "$capability is the owner's act (the Account tab or the CLI); '$source' may not"
}

/**
 * ACCOUNT-1: Infantry_Account's daemon half (the account design doc (docs/design) 1.1 to 1.5). Providers register an endpoint
 * `account.provider/<id>`; this plugin names none of them. The UI half reads and acts through the three routes below
 * (display data only: no route ever returns a token); other plugins ask through the `account.*` capabilities.
 */
class AccountDaemonPlugin : DaemonPlugin {
    private val closers = mutableListOf<AutoCloseable>()

    override fun start(api: DaemonApi) {
        val service = AccountService(
            http = api.http,
            secrets = api.pluginSecrets,
            endpoints = { api.services.endpoints(PROVIDER_PREFIX).mapKeys { it.key.removePrefix(PROVIDER_PREFIX) } },
            notify = { title, body, key ->
                api.notifications.notify(Notification(title, body, NotificationLevel.WARNING, dedupeKey = key, actions = listOf(NotificationAction("open", "Sign in"))))
            },
            log = api::log,
            refreshMinutes = { api.config("account.refreshMinutes")?.toLongOrNull()?.coerceAtLeast(1) ?: DEFAULT_REFRESH_MINUTES },
        )
        closers += api.schedule("account-poll", POLL_PERIOD_MS) { service.tick() }

        fun cap(id: String, run: (CapabilityCall) -> JsonElement) { closers += api.capabilities.handle(id) { run(it) } }
        cap("account.status") { service.status(it.args.str("provider")) }
        cap("account.entitlement") { service.entitlement(it.args.need("provider"), it.args.need("entitlement")) }
        cap("account.token") { call ->
            val p = service.provider(call.args.need("provider"))
            AccountGate.tokenRefusal(call.source, p)?.let { throw IllegalStateException(it) }
            val (token, expiresAt) = service.accessToken(p.id)
            buildJsonObject { put("accessToken", token.reveal()); put("expiresAt", expiresAt) }
        }
        cap("account.signIn") { call ->
            AccountGate.ownerRefusal("account.signIn", call.source)?.let { throw IllegalStateException(it) }
            flowJson(service.signIn(call.args.need("provider")))
        }
        cap("account.signOut") { call ->
            AccountGate.ownerRefusal("account.signOut", call.source)?.let { throw IllegalStateException(it) }
            buildJsonObject { put("wasSignedIn", service.signOut(call.args.need("provider"))) }
        }

        // The UI half's three routes (relative: /plugins/Infantry_Account/...). Display data only.
        api.routes.handle("GET", "state") { call -> reply { service.status(refresh = call.query["refresh"] == "1") } }
        api.routes.handle("POST", "signin") { call -> reply { flowJson(service.signIn(body(call).need("provider"))) } }
        api.routes.handle("POST", "signout") { call -> reply { buildJsonObject { put("wasSignedIn", service.signOut(body(call).need("provider"))) } } }
    }

    override fun stop() {
        closers.forEach { runCatching { it.close() } }
        closers.clear()
    }

    private fun body(call: HttpCall): JsonObject = Json.parseToJsonElement(call.body.ifBlank { "{}" }).jsonObject

    private fun reply(run: () -> JsonElement): HttpReply = try {
        HttpReply(200, run().toString(), "application/json")
    } catch (e: IllegalArgumentException) {
        HttpReply(400, e.message ?: "bad request")
    } catch (e: Exception) {
        HttpReply(502, e.message ?: e::class.simpleName.orEmpty())
    }

    private fun flowJson(f: SignInFlow): JsonObject = buildJsonObject {
        put("id", f.id)
        put("provider", f.providerId)
        put("userCode", f.userCode)
        put("verificationUri", f.verificationUri)
        put("expiresAt", f.expiresAt)
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.need(key: String): String = str(key) ?: throw IllegalArgumentException("'$key' is required")

    companion object {
        const val PROVIDER_PREFIX = "account.provider/"
        const val POLL_PERIOD_MS = 1000L
        const val DEFAULT_REFRESH_MINUTES = 15L
    }
}
