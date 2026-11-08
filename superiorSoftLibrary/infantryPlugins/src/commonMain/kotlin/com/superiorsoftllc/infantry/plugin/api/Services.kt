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

    /** VOICE-SEAM-1 (@since plugin API 2.1.0, revision 3): read text aloud with the host's voice. Null where the platform has none. */
    val SPEECH_OUT = ServiceKey<SpeechOutput>("speech.out")

    /**
     * VOICE-SEAM-1 (@since plugin API 2.1.0, revision 3): capture the microphone. A SENSITIVE service: besides the
     * declaration, the owner must grant it (`allow` in modules.json, written when the owner accepts it at install) for
     * every tier but core. Null where the platform has no capture.
     */
    val AUDIO_IN = ServiceKey<AudioCapture>("audio.in")

    /**
     * VOICE-SEAM-1 (@since plugin API 2.1.0): the service ids that need the owner's explicit grant whatever the module's
     * tier (core excepted): the install asks for them even for an official plugin.
     */
    val SENSITIVE: Set<String> = setOf(AUDIO_IN.id)
}

/** The services one module may use. [get] is null when the service is not declared, denied, or not on this platform. */
interface PluginServices {
    val module: String
    fun <T : Any> get(key: ServiceKey<T>): T?
}

/**
 * Tells the owner something: the notification bus (NOTIFY-CORE-1, docs/wiki/Notifications.md). [notify] with a [Notification]
 * routes it to the in-app toast and the OS by the owner's settings; the two-string form is an info notification. The host's
 * implementation overrides every member; the defaults keep a one-lambda implementation (a test double) compiling.
 */
fun interface Notifications {
    fun notify(title: String, body: String)

    /** NOTIFY-CORE-1 (@since revision 6): posts [notification]; the answer is its id (assigned when blank). */
    fun notify(notification: Notification): String {
        notify(notification.title, notification.body)
        return notification.id
    }

    /** NOTIFY-CORE-1: takes notification [id] down on every channel (it stays in the history). */
    fun dismiss(id: String) {}

    /**
     * NOTIFY-CORE-1: registers [presenter] as [presenterId]'s presenter (use the module name) for [channels]. It is active on a
     * channel when `notifications.presenter.<channel>` names it, or is blank and it is the last one registered. Close to remove.
     */
    fun present(presenterId: String, channels: Set<String>, presenter: NotificationPresenter): AutoCloseable = AutoCloseable {}

    /** NOTIFY-CORE-1: [listener] hears every pressed notification action. Close to stop. */
    fun onAction(listener: (NotificationActionEvent) -> Unit): AutoCloseable = AutoCloseable {}
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

/**
 * VOICE-SEAM-1 (@since plugin API 2.1.0): how one text is spoken. A data class so later fields (rate, pitch, ...) are
 * added without breaking callers.
 */
data class SpeakOptions(
    /** 0..100; the host clamps it. */
    val volumePercent: Int = 100,
    /** A name from [SpeechOutput.voices]; null = the host's default voice. */
    val voice: String? = null,
    /** Cut off whatever is being said (and drop what is queued) before this text. */
    val interrupt: Boolean = false,
)

/**
 * VOICE-SEAM-1 (@since plugin API 2.1.0): the host's text-to-speech ([Services.SPEECH_OUT]). The host speaks with an
 * installed voice engine when the owner enabled one, else with the OS voice. Calls return at once; speech runs in the background.
 */
interface SpeechOutput {
    /** Queues [text]; false = nothing will be spoken (no voice available, or blank text). */
    fun speak(text: String, options: SpeakOptions = SpeakOptions()): Boolean

    /** Stops what is being said and drops what is queued. */
    fun stop()

    /** Voice names [SpeakOptions.voice] accepts; empty = only the default. */
    fun voices(): List<String>
}

/** VOICE-SEAM-1 (@since plugin API 2.1.0): a raw PCM format. The default is what speech recognisers want: 16 kHz mono signed 16-bit little-endian. */
data class PcmFormat(val sampleRateHz: Int = 16_000, val channels: Int = 1, val bitsPerSample: Int = 16)

/** VOICE-SEAM-1 (@since plugin API 2.1.0): one capture request. A data class so later fields are added without breaking callers. */
data class CaptureOptions(
    val format: PcmFormat = PcmFormat(),
    /** Ends the capture after this many seconds; null = the host's limit. The host's limit always caps it. */
    val maxSeconds: Int? = null,
)

/**
 * VOICE-SEAM-1 (@since plugin API 2.1.0): why a capture ended. [reason] is one of the constants below; a later host may
 * add more, so treat an unknown one like [FAILED].
 */
data class CaptureEnd(val reason: String, val detail: String? = null) {
    companion object {
        /** [CaptureHandle.stop] was called (by the plugin or by the owner from the host's listening indicator). */
        const val STOPPED = "stopped"
        /** The time limit was reached. */
        const val LIMIT = "limit"
        /** Another capture is running on this host (one at a time). */
        const val BUSY = "busy"
        /** No microphone, or the format is not supported. */
        const val UNAVAILABLE = "unavailable"
        /** The device failed while capturing. */
        const val FAILED = "failed"
    }
}

/** VOICE-SEAM-1 (@since plugin API 2.1.0): a running capture. */
interface CaptureHandle {
    /** True until the capture has ended. */
    val active: Boolean

    /** Ends the capture; onEnd follows with [CaptureEnd.STOPPED]. Safe to call twice. */
    fun stop()
}

/**
 * VOICE-SEAM-1 (@since plugin API 2.1.0): the microphone ([Services.AUDIO_IN]). One capture at a time per host, and the
 * host shows its own listening indicator while one runs (the owner can stop it there).
 */
interface AudioCapture {
    /**
     * Starts capturing. [onChunk] gets raw PCM in [CaptureOptions.format] on a host thread, in order; [onEnd] is called
     * exactly once, after the last chunk. When the capture cannot start, [onEnd] is called before this returns (with
     * [CaptureEnd.BUSY] or [CaptureEnd.UNAVAILABLE]) and the handle is already inactive.
     */
    fun start(options: CaptureOptions = CaptureOptions(), onChunk: (ByteArray) -> Unit, onEnd: (CaptureEnd) -> Unit): CaptureHandle
}
