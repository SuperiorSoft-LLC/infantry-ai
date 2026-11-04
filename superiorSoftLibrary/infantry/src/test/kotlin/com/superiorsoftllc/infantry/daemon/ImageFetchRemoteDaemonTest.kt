// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.cli.freshToken
import com.superiorsoftllc.infantry.cli.lanIpv4
import com.superiorsoftllc.infantry.cli.lanPathAnswers
import com.superiorsoftllc.infantry.orchestration.GatedRunner
import com.superiorsoftllc.infantry.protocol.Envelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * CHAT-IMAGE-REMOTE-1: a client on another host cannot read the daemon's upload path, so it loads a chat image by upload id
 * (`image_fetch`, answered by Infantry_Chat). The daemon binds every interface with a token, as the owner's remote access does,
 * and the client dials this machine's LAN address (the phone dials the tailnet one): bytes come back by id, a removed image is
 * answered `missing` (the client says "expired"), and a path in place of an id is refused.
 */
class ImageFetchRemoteDaemonTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val cleanups = mutableListOf<() -> Unit>()

    @AfterTest
    fun cleanUp() {
        cleanups.asReversed().forEach { runCatching { it() } }
        cleanups.clear()
    }

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile().also { dir -> cleanups += { dir.deleteRecursively() } }

    private fun png(salt: String): ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
    ) + salt.toByteArray()

    @Test
    fun aClientOnANonLoopbackAddressLoadsAChatImageByIdAndARemovedOneIsMissing() {
        val lan = lanIpv4()
        if (lan == null || !lanPathAnswers(lan)) {
            println("SKIP: this machine has no reachable non-loopback IPv4 address (lan=$lan)")
            return
        }
        val state = tempDir("infantry-imgfetch-state")
        val fixture = DaemonFixture.launch(
            stateDir = state, runner = GatedRunner(), workingDir = File(state, "workdir").apply { mkdirs() },
            host = "0.0.0.0", connectHost = lan, token = freshToken(),
            pluginsRoot = TestDaemonPlugins.root("official/Infantry_Chat").also { r -> cleanups += { r.deleteRecursively() } },
        )
        cleanups += { fixture.stop() }
        fixture.client.await(Envelope.READY)
        assertNotEquals("127.0.0.1", fixture.connectHost, "the client dials a non-loopback address")

        val root = tempDir("infantry-imgfetch-proj")
        fixture.send(Envelope.PROJECT_SAVE, """{"name":"remote","directory":${JsonPrimitive(root.absolutePath)}}""")
        val projectId = json.parseToJsonElement(
            fixture.client.awaitWhere("project") { it.type == Envelope.PROJECT }.data,
        ).jsonObject["id"]!!.jsonPrimitive.content

        val bytes = png("remote-phone")
        val from = fixture.client.mark()
        fixture.send("image_upload", buildJsonObject {
            put("projectId", JsonPrimitive(projectId)); put("purpose", JsonPrimitive("chat"))
            put("mimeType", JsonPrimitive("image/png")); put("base64", JsonPrimitive(Base64.getEncoder().encodeToString(bytes)))
        }.toString())
        val stored = json.parseToJsonElement(fixture.client.awaitAfter(from, "upload") { it.type == "image" || it.type == Envelope.ERROR }.data).jsonObject
        val path = stored["path"]!!.jsonPrimitive.content
        val id = path.substringAfterLast('/').substringAfterLast('\\')

        fun fetch(requestId: String, ask: String): Envelope {
            val mark = fixture.client.mark()
            fixture.send(Envelope("image_fetch", buildJsonObject { put("id", JsonPrimitive(ask)) }.toString(), requestId = requestId))
            return fixture.client.awaitAfter(mark, "image_fetch $requestId") { it.requestId == requestId }
        }

        val got = fetch("f1", id)
        assertEquals("image_bytes", got.type, got.data)
        val o = json.parseToJsonElement(got.data).jsonObject
        assertEquals("image/png", o["mimeType"]!!.jsonPrimitive.content)
        assertContentEquals(bytes, Base64.getDecoder().decode(o["base64"]!!.jsonPrimitive.content), "the bytes by id are the uploaded ones")

        assertTrue(File(path).delete(), "the retention sweep's effect: the file is gone")
        val gone = fetch("f2", id)
        assertEquals("image_bytes", gone.type, gone.data)
        assertEquals("true", json.parseToJsonElement(gone.data).jsonObject["missing"]!!.jsonPrimitive.content, gone.data)

        val escape = fetch("f3", "../../secret.png")
        assertEquals(Envelope.ERROR, escape.type, escape.data)
        assertTrue(escape.data.startsWith("image_fetch refused:"), escape.data)
    }
}
