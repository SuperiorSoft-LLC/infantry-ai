package com.superiorsoftllc.agenthq.plugin.api

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
