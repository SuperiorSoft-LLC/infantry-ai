package com.superiorsoftllc.agenthq.ui.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * USER-PORTRAIT (contract 1.107.0): the human's own picture, shown in chat instead of the "Y" placeholder. Daemon
 * state (`user.portrait`, a base64 PNG), so every connected client shows the same one; absent = the bundled default.
 */
object UserPortrait {
    const val KEY = "user.portrait"
    const val SINCE_MAJOR = 1
    const val SINCE_MINOR = 107

    /** The largest file a person may pick; it is downscaled to [SIDE] px before it is saved. */
    const val MAX_PICK_BYTES = 5L * 1024 * 1024
    const val SIDE = 256

    const val TITLE = "Your portrait"
    const val APPLIES_TO = "Shown for you in chat on every client connected to this daemon"

    /** What a picked file is, from its first bytes (never its name), or null for anything else. */
    fun detect(bytes: ByteArray): String? = when {
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "png"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "jpeg"
        bytes.size >= 12 && bytes.decodeToString(0, 4) == "RIFF" && bytes.decodeToString(8, 12) == "WEBP" -> "webp"
        else -> null
    }

    /** A person-readable reason a picked file is refused, or null when it may be used. [size] is the real file size. */
    fun refusal(bytes: ByteArray?, size: Long = bytes?.size?.toLong() ?: 0): String? = when {
        size > MAX_PICK_BYTES -> "That image is ${size / 1024 / 1024} MB; the limit is 5 MB."
        bytes == null || bytes.isEmpty() -> "That file could not be read."
        detect(bytes) == null -> "Only PNG, JPEG and WebP images can be used as a portrait."
        else -> null
    }

    /** The result of reading a `settings` frame: a full frame without the key means the portrait is unset. */
    class Update(val base64: String?)

    fun fromSettingsFrame(data: String): Update? {
        val obj = runCatching { Json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return null
        val value = obj[KEY] as? JsonPrimitive
        if (value != null) return Update(value.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() })
        return if (obj.containsKey(LifecycleSetting.KEY)) Update(null) else null
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun decode(base64: String?): ByteArray? = base64?.let { runCatching { Base64.decode(it) }.getOrNull() }

    @OptIn(ExperimentalEncodingApi::class)
    fun encode(bytes: ByteArray): String = Base64.encode(bytes)
}
