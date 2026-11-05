// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.updater.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.HttpReply
import com.superiorsoftllc.infantry.daemon.api.updates.UpdateComponent
import com.superiorsoftllc.infantry.plugins.official.updater.ClientUpdateOffer
import com.superiorsoftllc.infantry.plugins.official.updater.ClientUpdates
import com.superiorsoftllc.infantry.plugins.official.updater.RouteAnswer
import com.superiorsoftllc.infantry.plugins.official.updater.ServerUpdateService
import com.superiorsoftllc.infantry.plugins.official.updater.UpdateRoutes
import com.superiorsoftllc.infantry.plugins.official.updater.UpdateSettings
import com.superiorsoftllc.infantry.plugins.official.updater.UpdaterEnv
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Properties

/**
 * UPDATER-1: Infantry_Updater's daemon half. It serves the update surface core used to (contract 1.110.0) and adds the phone's:
 *
 *  - `GET  /update/status`, `POST /update/check`, `POST /update/apply`: this daemon's own (headless archive / Docker) update.
 *  - `GET  /update/client?platform=android&current=<x.y.z>`: what the phone should install ([ClientUpdates]).
 *
 * Settings: the module's `updater.*` config ([DaemonApi.config]), over `update.properties` and the `INFANTRY_UPDATE_*`
 * overrides ([UpdateSettings.resolve]). The private-repo token: env `INFANTRY_UPDATE_TOKEN` ([DaemonApi.env]), else the
 * keystore's `update-token` ([DaemonApi.secret]), else the OS credential store; it is read only when `updater.source=private`
 * and is never logged, mirrored or answered. The effective settings (never the token) are mirrored to
 * `plugin-data/Infantry_Updater/settings.properties` for the window's own updater ([UpdateSettings.settingsMirror]).
 *
 * The daemon's own update runs on core's update engine (`DaemonApi.updates`, component `core`): this plugin registers the
 * `infantry-release` source type and the `core` applier ([ReleaseSourceType], [CoreArchiveApplier]) and sets the app's
 * source from its settings; its routes are the face of that engine ([CoreEnginePath]).
 */
class UpdaterDaemonPlugin : DaemonPlugin {
    private lateinit var api: DaemonApi
    private var timer: AutoCloseable? = null
    private val registrations = mutableListOf<AutoCloseable>()

    /** Core's update engine for the app, when it took this plugin's source type and applier. */
    private var engine: CoreEnginePath? = null
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    /** The service of the current settings: rebuilt when a `updater.*` value changes, so a status survives between calls. */
    private var built: Pair<Map<String, String?>, ServerUpdateService>? = null

    /** `<state dir>`: the plugin's own folder is `<state dir>/plugin-data/<module>/`. */
    private val stateRoot: File get() = api.stateDir.parentFile?.parentFile ?: UpdaterEnv.stateDir()

    private fun snapshot(): Map<String, String?> = UpdateSettings.SETTING_KEYS.associateWith { api.config(it) }

    @Synchronized
    private fun service(): ServerUpdateService {
        val now = snapshot()
        built?.let { (seen, service) -> if (seen == now) return service }
        mirror(now)
        val service = ServerUpdateService.fromEnvironment(
            runningAgents = { api.sessions.list().count { it.state in BUSY_STATES } },
            env = { name -> api.env(name) },
            keystore = { api.secret(UpdateSettings.TOKEN_SECRET) },
            config = { key -> now[key] },
            stateDir = stateRoot,
            engine = engine,
        )
        built = now to service
        engine?.let { runCatching { api.updates.setSource(UpdateComponent.CORE_ID, ReleaseSourceType.coreSource(service.config)) }.onFailure { api.log("updater: could not set the app's update source: ${it::class.simpleName}") } }
        return service
    }

    /** The window's updater cannot ask the daemon's config store, so the values it needs are written here. Never a token. */
    private fun mirror(values: Map<String, String?>) {
        runCatching {
            val file = UpdateSettings.settingsMirror(stateRoot)
            val props = Properties().apply { values.forEach { (k, v) -> if (v != null) setProperty(k, v) } }
            file.parentFile.mkdirs()
            file.writer().use { props.store(it, "Infantry_Updater settings (mirror of the module config; no token is ever stored here)") }
        }.onFailure { api.log("updater: could not write the settings mirror: ${it::class.simpleName}") }
    }

    private fun clientOffer(platform: String?, current: String?): ClientUpdateOffer {
        val now = snapshot()
        return ClientUpdates(resolve = { version ->
            UpdateSettings.resolve(
                installed = true, version = version, stateDir = stateRoot,
                setting = { UpdaterEnv.setting(it) },
                env = { name -> api.env(name) },
                keystore = { api.secret(UpdateSettings.TOKEN_SECRET) },
                config = { key -> now[key] },
            )
        }).offer(platform, current)
    }

    private fun reply(answer: RouteAnswer) = HttpReply(answer.status, answer.json, JSON)

    override fun start(api: DaemonApi) {
        this.api = api
        engine = runCatching {
            registrations += api.updates.registerSource(
                ReleaseSourceType(base = { service().config }, kind = { ServerUpdateService.kindFor(service().distribution) }),
            )
            registrations += api.updates.registerApplier(CoreArchiveApplier { service().install })
            CoreEnginePath(api.updates)
        }.onFailure { api.log("updater: core's update engine refused the source/applier (${it.message}); using the plugin's own path") }.getOrNull()
        api.routes.handle("GET", "/update/status") { reply(UpdateRoutes.status(service())) }
        api.routes.handle("POST", "/update/check") { reply(UpdateRoutes.check(service())) }
        api.routes.handle("POST", "/update/apply") { call -> reply(UpdateRoutes.apply(service(), call.body)) }
        api.routes.handle("GET", "/update/client") { call ->
            val offer = clientOffer(call.query["platform"], call.query["current"])
            val status = when (offer.result) { "failed" -> 502; "unsupported" -> 409; else -> 200 }
            HttpReply(status, json.encodeToString(ClientUpdateOffer.serializer(), offer), JSON)
        }
        // A background re-check every `updater.checkIntervalHours` (default 24; 0 = only at start and by hand). The check
        // only reads the release list: applying is always the owner's call.
        service() // builds the service from the settings and writes the window's settings mirror; no network
        val hours = api.config(UpdateSettings.KEY_INTERVAL)?.toIntOrNull()?.takeIf { it >= 0 } ?: UpdateSettings.DEFAULT_INTERVAL_HOURS
        if (hours > 0) {
            timer = api.schedule("update-check", hours * HOUR_MS) {
                runCatching { service().check() }.onFailure { api.log("updater: background check failed: ${it::class.simpleName}") }
            }
        }
        api.log("updater: routes up; source=${api.config(UpdateSettings.KEY_SOURCE) ?: "public"}, re-check every ${hours}h")
    }

    override fun stop() {
        runCatching { timer?.close() }
        timer = null
        registrations.forEach { runCatching { it.close() } }
        registrations.clear()
        engine = null
    }

    companion object {
        private const val JSON = "application/json"
        private const val HOUR_MS = 3_600_000L

        /** The session states that mean an agent is mid-turn (core's `isBusy`): an update waits for them or asks to confirm. */
        val BUSY_STATES = setOf("STARTING", "BUSY", "WAITING_INPUT")
    }
}
