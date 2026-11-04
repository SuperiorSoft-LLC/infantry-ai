// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.net.DaemonClient
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private class ImageLink(scope: CoroutineScope) : DaemonClient(scope) {
    val sent = mutableListOf<Envelope>()
    var respond: (Envelope) -> List<Envelope> = { emptyList() }
    private val incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 64)
    override val frames: SharedFlow<Envelope> get() = incoming
    override suspend fun send(envelope: Envelope): Boolean {
        synchronized(sent) { sent += envelope }
        respond(envelope).forEach { check(incoming.tryEmit(it)) { "frame buffer full" } }
        return true
    }
    suspend fun push(e: Envelope) {
        val deadline = kotlin.time.TimeSource.Monotonic.markNow() + 10_000.milliseconds
        while (incoming.subscriptionCount.value == 0 && deadline.hasNotPassedNow()) delay(10)
        check(incoming.tryEmit(e)) { "frame buffer full" }
    }
    fun fetches() = synchronized(sent) { sent.filter { it.type == Controller.IMAGE_FETCH } }
}

private object FakeBitmap : androidx.compose.ui.graphics.ImageBitmap {
    override val width = 1
    override val height = 1
    override val config = androidx.compose.ui.graphics.ImageBitmapConfig.Argb8888
    override val hasAlpha = true
    override val colorSpace = androidx.compose.ui.graphics.colorspace.ColorSpaces.Srgb
    override fun prepareToDraw() {}
    override fun readPixels(buffer: IntArray, startX: Int, startY: Int, width: Int, height: Int, bufferOffset: Int, stride: Int) {}
}

/**
 * CHAT-IMAGE-REMOTE-1: a client that cannot read the daemon's file loads a chat image by upload id over its own connection
 * (`image_fetch`), caches it, and tells the truth about a failure: "expired" only when the daemon says the upload is gone,
 * otherwise a retry that loads it once the fetch succeeds.
 */
class DaemonImagesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")
    private val id = "a".repeat(64) + ".png"
    /** Skiko's native library is not on this module's test classpath: a stand-in bitmap for any bytes. */
    private val fake: (ByteArray) -> androidx.compose.ui.graphics.ImageBitmap = { FakeBitmap }
    /** The daemon's absolute path, which a phone cannot open. */
    private val daemonPath = "C:\\Users\\owner\\project\\.infantry\\uploads\\chat\\$id"

    private fun <T> withController(body: suspend (Controller, ImageLink) -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = ImageLink(scope)
            val controller = Controller(scope, link)
            return runBlocking {
                link.push(Envelope(Envelope.READY, "contract=1.150.0 provider=stub instance=i1"))
                body(controller, link)
            }
        } finally {
            scope.cancel()
        }
    }

    private fun bytesAnswer(env: Envelope) = Envelope(
        Controller.IMAGE_BYTES,
        """{"id":"$id","mimeType":"image/png","base64":"${Base64.getEncoder().encodeToString(png)}"}""",
        requestId = env.requestId,
    )

    @Test
    fun anUploadedImageLoadsByIdOverTheConnectionAndIsCached(): Unit = withController<Unit> { c, link ->
        link.respond = { env -> if (env.type == Controller.IMAGE_FETCH) listOf(bytesAnswer(env)) else emptyList() }
        val got = c.fetchUploadedImage(id)
        assertIs<DaemonImage.Bytes>(got)
        assertContentEquals(png, got.bytes)
        val asked = link.fetches().single()
        assertEquals(id, json.parseToJsonElement(asked.data).jsonObject["id"]!!.jsonPrimitive.content, "asked by id, not by path")
        assertTrue(asked.requestId != null, "matched by request id")
        assertIs<DaemonImage.Bytes>(c.fetchUploadedImage(id))
        assertEquals(1, link.fetches().size, "the second load is served from the client cache")
    }

    @Test
    fun aRemovedImageIsMissingAndAFailedFetchIsNotCached(): Unit = withController<Unit> { c, link ->
        link.respond = { env -> if (env.type == Controller.IMAGE_FETCH) listOf(Envelope(Controller.IMAGE_BYTES, """{"id":"$id","missing":true}""", requestId = env.requestId)) else emptyList() }
        assertEquals(DaemonImage.Missing, c.fetchUploadedImage(id))
        link.respond = { env -> if (env.type == Controller.IMAGE_FETCH) listOf(Envelope(Envelope.ERROR, "image_fetch refused: busy", requestId = env.requestId)) else emptyList() }
        assertIs<DaemonImage.Failed>(c.fetchUploadedImage(id))
        link.respond = { env -> if (env.type == Controller.IMAGE_FETCH) listOf(bytesAnswer(env)) else emptyList() }
        assertIs<DaemonImage.Bytes>(c.fetchUploadedImage(id), "a failure is not cached: the retry asks again")
        assertEquals(3, link.fetches().size)
    }

    @Test
    fun aRemovedImageShowsExpiredAndAFailedFetchShowsRetryThenLoads(): Unit = runBlocking {
        val noLocal: (String) -> ByteArray? = { null }
        val cannotLook: (String) -> Boolean? = { false }
        assertEquals(ImageLoad.Expired, loadImage(daemonPath, null, noLocal, cannotLook, { DaemonImage.Missing }, fake))
        assertIs<ImageLoad.Failed>(loadImage(daemonPath, null, noLocal, cannotLook, { DaemonImage.Failed("not connected to the daemon") }, fake),
            "a fetch failure is never called expired, even though this host cannot see the file")
        assertIs<ImageLoad.Shown>(loadImage(daemonPath, null, noLocal, cannotLook, { DaemonImage.Bytes(png) }, fake), "the retry loads it")
    }

    @Test
    fun theDaemonsOwnMachineReadsTheFileAndAPathThatIsNoUploadKeepsTheLocalAnswer(): Unit = runBlocking {
        var asked = 0
        val source = DaemonImageSource { asked++; DaemonImage.Missing }
        assertIs<ImageLoad.Shown>(loadImage(daemonPath, null, { png }, { true }, source, fake))
        assertEquals(0, asked, "a readable local file is never fetched")
        assertEquals(ImageLoad.Expired, loadImage("/tmp/swept/0000.png", null, { null }, { false }, source, fake), "IMG-5: a local swept file")
        assertEquals(0, asked, "a path that names no upload is not asked for")
        assertEquals(id, UploadIds.of(daemonPath))
        assertEquals(null, UploadIds.of("/x/../secret.png"))
    }
}
