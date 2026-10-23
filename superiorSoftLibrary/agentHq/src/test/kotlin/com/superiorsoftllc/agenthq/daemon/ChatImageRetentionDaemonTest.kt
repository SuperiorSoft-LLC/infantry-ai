package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.orchestration.GatedRunner
import com.superiorsoftllc.agenthq.protocol.Envelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * IMG-5: the chat image retention sweep, on a REAL daemon (Ktor, WebSocket, SQLite) whose clock is fake and whose
 * 6-hour ticker is fired by hand. Uploads go through the real `image_upload` frame; the disk is the oracle.
 */
class ChatImageRetentionDaemonTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val cleanups = mutableListOf<() -> Unit>()
    private val now = AtomicLong(System.currentTimeMillis())
    private var tick: (() -> Unit)? = null
    private var tickInterval = 0L

    @AfterTest
    fun cleanUp() {
        cleanups.asReversed().forEach { runCatching { it() } }
        cleanups.clear()
    }

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile().also { dir ->
        cleanups += { dir.deleteRecursively() }
    }

    private fun launch(): DaemonFixture {
        val state = tempDir("agenthq-retention-state")
        val fixture = DaemonFixture.launch(
            stateDir = state, runner = GatedRunner(), workingDir = File(state, "workdir").apply { mkdirs() },
            clock = now::get,
            chatImageTicker = { interval, task -> tickInterval = interval; tick = task; AutoCloseable { tick = null } },
        )
        cleanups += { fixture.stop() }
        fixture.client.await(Envelope.READY)
        return fixture
    }

    private fun advanceDays(days: Long) { now.addAndGet(TimeUnit.DAYS.toMillis(days)) }

    /** The 6-hour tick, fired by hand: the very task the daemon handed its ticker. */
    private fun fireSweep() = assertNotNull(tick, "the daemon must have scheduled the sweep at start").invoke()

    private fun DaemonFixture.createProject(name: String, directory: File): String {
        send(Envelope.PROJECT_SAVE, """{"name":"$name","directory":${JsonPrimitive(directory.absolutePath)}}""")
        val frame = client.awaitWhere("project $name") { env ->
            env.type == Envelope.PROJECT && json.parseToJsonElement(env.data).jsonObject["name"]?.jsonPrimitive?.content == name
        }
        return json.parseToJsonElement(frame.data).jsonObject["id"]!!.jsonPrimitive.content
    }

    private fun png(salt: String): ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
    ) + salt.toByteArray()

    /** Upload through the real frame; answers the stored file. */
    private fun DaemonFixture.upload(projectId: String, bytes: ByteArray, purpose: String): File {
        val from = client.mark()
        val payload = buildJsonObject {
            put("projectId", JsonPrimitive(projectId))
            put("purpose", JsonPrimitive(purpose))
            put("fileName", JsonPrimitive("x.png"))
            put("mimeType", JsonPrimitive("image/png"))
            put("base64", JsonPrimitive(Base64.getEncoder().encodeToString(bytes)))
        }
        send(Envelope.IMAGE_UPLOAD, payload.toString())
        val answer = client.awaitAfter(from, "the image_upload answer") { it.type == "image" || it.type == Envelope.ERROR }
        assertEquals("image", answer.type, "the upload must be accepted: ${answer.data}")
        return File(json.parseToJsonElement(answer.data).jsonObject["path"]!!.jsonPrimitive.content)
    }

    /** Acceptance 1 + 2: past the 14-day default, an old chat file goes, a newer one and an equally old ticket file stay. */
    @Test
    fun anExpiredChatFileIsSweptWhileANewerOneAndTicketUploadsAreKept() {
        val root = tempDir("agenthq-retention-proj")
        launch().run {
            assertEquals(TimeUnit.HOURS.toMillis(6), tickInterval, "the sweep repeats every 6 hours")
            val projectId = createProject("retention", root)
            val oldChat = upload(projectId, png("old-chat"), "chat")
            val oldTicket = upload(projectId, png("old-ticket"), "ticket")
            advanceDays(10)
            val newChat = upload(projectId, png("new-chat"), "chat")
            advanceDays(5) // old ones are 15 days old, the new chat file 5

            fireSweep()

            assertFalse(oldChat.exists(), "a chat upload older than the 14-day window is deleted")
            assertTrue(newChat.isFile, "a chat upload inside the window is kept")
            assertTrue(oldTicket.isFile, "a ticket upload is never swept, however old")
        }
    }

    /** Acceptance 3: retention 0 keeps everything forever. */
    @Test
    fun retentionZeroDeletesNothing() {
        val root = tempDir("agenthq-retention-zero")
        launch().run {
            val projectId = createProject("forever", root)
            val chat = upload(projectId, png("forever"), "chat")
            val answer = client.answerTo(Envelope.SETTINGS_SET, """{"key":"images.chatImageRetentionDays","value":0}""")
            assertEquals(Envelope.SETTINGS, answer.type, "retention 0 must be accepted: ${answer.data}")
            advanceDays(3650)

            fireSweep()

            assertTrue(chat.isFile, "retention 0 = keep forever")
        }
    }

    /** Acceptance 4: a dedupe re-upload refreshes the mtime, so the file survives the next sweep. */
    @Test
    fun aDedupeReUploadRefreshesTheMtimeAndTheFileSurvives() {
        val root = tempDir("agenthq-retention-dedupe")
        launch().run {
            val projectId = createProject("dedupe", root)
            val bytes = png("still-used")
            val first = upload(projectId, bytes, "chat")
            advanceDays(10)
            val again = upload(projectId, bytes, "chat")
            assertEquals(first.canonicalPath, again.canonicalPath, "the same bytes dedupe to the same file")
            assertEquals(now.get() / 1000, again.lastModified() / 1000, "the dedupe hit stamps the file with the daemon's now")
            advanceDays(5) // 15 days since the first upload, 5 since the re-upload

            fireSweep()

            assertTrue(again.isFile, "a re-uploaded chat image is still in use and survives the sweep")
        }
    }
}
