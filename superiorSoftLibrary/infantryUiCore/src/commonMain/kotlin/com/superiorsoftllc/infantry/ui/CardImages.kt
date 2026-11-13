// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import com.superiorsoftllc.infantry.ui.shell.LocalShellContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * CARD-IMG-1 (docs/CARD-REGISTRY.md 2.5): what a card's image came to. A card draws [Shown]; anything else draws its own
 * placeholder (a source badge), never an error: [Loading] while the daemon fetches, [Unavailable] when it refused (a host the
 * card type did not declare, not https, not an image) or failed (offline, 404, too big). [reason] is for a tooltip, if any.
 */
sealed interface CardImage {
    data object Loading : CardImage
    class Shown(val bitmap: ImageBitmap) : CardImage
    data class Unavailable(val reason: String, val refused: Boolean = false) : CardImage
}

/**
 * CARD-IMG-1: the image at [url] for a card of [type] (the part after `card:`), fetched by the DAEMON (frame `card_image`),
 * never by this client: the daemon fetches only https URLs on hosts that card type declared (module.json
 * `cards[].images.hosts`, plus the owner's `cards.images.allow`), with no cookies or referrer, a size cap and a type check. A
 * null or blank [url] is [CardImage.Unavailable] at once. Answers are kept in a small in-memory cache for the window.
 */
@Composable
fun rememberCardImage(type: String, url: String?): CardImage {
    // The window rebuilds its shell context (and link) on every recomposition: keying the load on the link would cancel the
    // request on each state tick, so it never finished. Key on what is asked; read the link current when asking.
    val link by rememberUpdatedState(LocalShellContext.current?.link)
    val connected = link != null
    val image by produceState<CardImage>(CardImage.Loading, type, url, connected) {
        value = CardImageLoader.load(type, url, link?.let { { t: String, d: String -> link?.request(t, d)?.let { it.type to it.data } } })
    }
    return image
}

/** CARD-IMG-1: the fetch behind [rememberCardImage], apart from Compose so it can be tested. */
internal object CardImageLoader {
    const val FRAME = "card_image"
    private const val ANSWER_WAIT_MS = 60_000L
    /** The client cache's bound: the decoded answers kept (a card list rarely shows more). */
    private const val CACHE_ENTRIES = 128
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val cache = LinkedHashMap<String, CardImage>()

    @OptIn(ExperimentalResourceApi::class)
    suspend fun load(
        type: String,
        url: String?,
        request: (suspend (type: String, data: String) -> Pair<String, String>?)?,
        decode: (ByteArray) -> ImageBitmap = { it.decodeToImageBitmap() },
    ): CardImage {
        val u = url?.trim().orEmpty()
        if (u.isEmpty()) return CardImage.Unavailable("no image")
        val key = "$type\n$u"
        lock.withLock { cache.remove(key)?.let { cache[key] = it; return it } }
        if (request == null) return CardImage.Unavailable("not connected to the daemon")
        val body = buildJsonObject { put("type", type); put("url", u) }.toString()
        val answer = try {
            withTimeoutOrNull(ANSWER_WAIT_MS) { request(FRAME, body) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return CardImage.Unavailable("the daemon did not answer")
        val result = read(answer.first, answer.second, decode)
        // Keep answers that will not change by asking again soon (an image, a refusal); a failure is retried next time.
        if (result is CardImage.Shown || (result is CardImage.Unavailable && result.refused)) lock.withLock {
            cache[key] = result
            while (cache.size > CACHE_ENTRIES) cache.remove(cache.keys.first())
        }
        return result
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun read(type: String, data: String, decode: (ByteArray) -> ImageBitmap): CardImage {
        // An `error` frame (no Infantry_Cards loaded, a policy gate): not cached, the plugin may arrive later.
        if (type != FRAME) return CardImage.Unavailable(data.take(200).ifEmpty { "refused" })
        val o = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return CardImage.Unavailable("unreadable answer")
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        if (s("ok") != "true") return s("refused")?.let { CardImage.Unavailable(it, refused = true) } ?: CardImage.Unavailable(s("failed") ?: "unavailable")
        val bytes = runCatching { Base64.decode(s("base64").orEmpty()) }.getOrNull() ?: return CardImage.Unavailable("unreadable answer")
        return withContext(Dispatchers.Default) {
            try {
                CardImage.Shown(decode(bytes))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CardImage.Unavailable("not a readable image", refused = true)
            }
        }
    }

    /** Tests only. */
    suspend fun clear() = lock.withLock { cache.clear() }
}
