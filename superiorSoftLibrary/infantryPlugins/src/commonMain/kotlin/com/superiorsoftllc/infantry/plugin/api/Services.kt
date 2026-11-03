// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugin.api

/**
 * PLUGINS-1: platform services. A plugin reaches the app only through the services handed to it, never through app
 * internals. Each service is gated: the module must declare it in module.json, and the owner can deny it (a community
 * module also needs it allowed in modules.json). The registry is the extension point: a later service is one more key.
 */
class ServiceKey<T : Any>(val id: String) {
    override fun toString() = id
}

object Services {
    val NOTIFICATIONS = ServiceKey<Notifications>("notifications")
    val STORAGE = ServiceKey<PluginStorage>("storage")
    val EVENTS = ServiceKey<EventBus>("events")
    val SETTINGS = ServiceKey<PluginSettings>("settings")
    val DAEMON = ServiceKey<DaemonAccess>("daemon")
    val ENDPOINTS = ServiceKey<EndpointAccess>("endpoints")
    /** DV-2: the daemon-provided declarative views (`plugin_view` frames). */
    val VIEWS = ServiceKey<ViewAccess>("views")
}

/** The services one module may use. [get] is null when the service is not declared, denied, or not on this platform. */
interface PluginServices {
    val module: String
    fun <T : Any> get(key: ServiceKey<T>): T?
}

/** Tells the owner something. Today a line in the log (and the in-app toast where the host has one); OS toasts later. */
fun interface Notifications {
    fun notify(title: String, body: String)
}

/** The module's own data area; no module can see another's. Names are plain file names. */
interface PluginStorage {
    fun read(name: String): String?
    fun write(name: String, text: String)
    fun list(): List<String>
}

/** The module's own settings (key/value), kept by the host. */
interface PluginSettings {
    fun get(key: String): String?
    fun set(key: String, value: String)
}

/** One event on the bus. */
data class PluginEvent(val name: String, val data: Map<String, String> = emptyMap())

/** The first events; a module may only publish or observe the ones it declared. */
object Events {
    const val TAB_OPENED = "tab.opened"
    const val SESSION_SELECTED = "session.selected"
    const val TICKET_CHANGED = "ticket.changed"
    const val MODULES_LOADED = "modules.loaded"
}

interface EventBus {
    fun publish(event: PluginEvent)
    /** Calls [observer] for every [name] event; the returned function unsubscribes. */
    fun subscribe(name: String, observer: (PluginEvent) -> Unit): () -> Unit
}

/** The daemon, limited to the routes the module declared (and, under the demo lock, the routes the lock allows). */
fun interface DaemonAccess {
    /** [route] is `METHOD /path`, exactly as declared. */
    suspend fun call(route: String, body: String?): DaemonReply
}

sealed interface DaemonReply {
    data class Ok(val status: Int, val body: String) : DaemonReply
    /** The host would not send it: not declared, or not allowed by the demo lock. */
    data class Refused(val reason: String) : DaemonReply
    data class Failed(val reason: String) : DaemonReply
}

/** PLUGIN-ENDPOINTS-1: the module's own backends, by declared endpoint id. No credential of the owner's ever rides along. */
interface EndpointAccess {
    suspend fun call(endpoint: String, method: String, path: String, body: String? = null, headers: Map<String, String> = emptyMap()): EndpointReply
}

sealed interface EndpointReply {
    /** A 3xx is returned as is; redirects are never followed. */
    data class Ok(val status: Int, val body: String, val headers: Map<String, String> = emptyMap()) : EndpointReply
    data class Refused(val reason: String) : EndpointReply
    data class Failed(val reason: String) : EndpointReply
}

/** DV-2: one view a loaded daemon module declares (`plugin_views_result`). No widget is named: [place] is `tab` or `none`. */
data class ViewInfo(val module: String, val id: String, val title: String, val place: String, val platforms: List<String>? = null)

/** DV-2: the view a button belongs to; the daemon refuses a tool its module does not own. */
data class ViewOrigin(val module: String, val view: String)

/** DV-2: what an open view shows. [Page.rev] is the daemon's counter; an older answer is never shown after a newer one. */
sealed interface ViewState {
    data object Loading : ViewState
    data class Page(val rev: Long, val page: kotlinx.serialization.json.JsonObject) : ViewState
    /** One line: why the daemon refused or could not answer. */
    data class Error(val line: String) : ViewState
}

/** DV-2: views from the daemon. The module id is the owner's own; the host does not interpret the page. */
interface ViewAccess {
    suspend fun list(): List<ViewInfo>
    /** Subscribes while collected; cancelling the collection closes the subscription. Re-opens after a reconnect. */
    fun open(module: String, view: String, params: kotlinx.serialization.json.JsonObject): kotlinx.coroutines.flow.Flow<ViewState>
    /** One `plugin_tool_call` carrying [origin]; success = the tool's text. */
    suspend fun act(origin: ViewOrigin, tool: String, args: kotlinx.serialization.json.JsonObject): Result<String>
}
