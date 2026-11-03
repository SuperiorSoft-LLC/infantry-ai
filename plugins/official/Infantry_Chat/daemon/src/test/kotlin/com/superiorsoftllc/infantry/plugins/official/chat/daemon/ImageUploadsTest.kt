package com.superiorsoftllc.infantry.plugins.official.chat.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.files.DirectoryProjectFiles
import com.superiorsoftllc.infantry.daemon.api.files.ProjectFiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DP-10a: the moved IMG-1 / IMG-5 behaviour (store, dedupe, refusals, retention) over a real [DirectoryProjectFiles]. */
class ImageUploadsTest {
    private val dirs = mutableListOf<File>()
    private fun project(): File = Files.createTempDirectory("infantry-chat-img").toFile().also { dirs += it }
    @AfterTest fun clean() { dirs.forEach { it.deleteRecursively() }; dirs.clear() }

    private fun png(salt: String = "") = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
    ) + salt.toByteArray()

    private fun upload(bytes: ByteArray, purpose: String = "chat", mime: String = "image/png", name: String = "x.png") =
        ImageUpload("p1", purpose, name, mime, Base64.getEncoder().encodeToString(bytes))

    @Test
    fun aPngIsStoredAtTheLayoutPathWithOneGitignore() {
        val root = project()
        val stored = ImageUploads.store(DirectoryProjectFiles(root), upload(png()), 1_000L)
        val file = File(stored.path)
        assertTrue(file.isFile)
        assertEquals(".infantry/uploads/chat/${stored.sha256}.png", stored.relPath)
        assertEquals("image/png", stored.mimeType)
        assertEquals("*\n", File(root, ".infantry/uploads/.gitignore").readText())
        assertEquals(File(root, stored.relPath).canonicalPath, file.canonicalPath)
    }

    @Test
    fun theSameBytesAsChatAndAsTicketGiveTwoFilesAndASamePurposeDedupes() {
        val files = DirectoryProjectFiles(project())
        val a = ImageUploads.store(files, upload(png("s")), 1L)
        val b = ImageUploads.store(files, upload(png("s")), 2L)
        val t = ImageUploads.store(files, upload(png("s"), purpose = "ticket"), 3L)
        assertEquals(a.path, b.path)
        assertTrue(a.path != t.path)
        assertEquals(1, files.list("uploads/chat").size)
    }

    @Test
    fun refusalsWriteNothing() {
        val root = project()
        val files = DirectoryProjectFiles(root)
        assertFailsWith<IllegalArgumentException> { ImageUploads.store(files, upload("not an image".toByteArray())) }
        assertFailsWith<IllegalArgumentException> { ImageUploads.store(files, upload(png(), mime = "image/gif")) }
        assertFailsWith<IllegalArgumentException> { ImageUploads.store(files, upload(png(), purpose = "nope")) }
        assertFailsWith<IllegalArgumentException> { ImageUploads.store(files, upload(ByteArray(10 * 1024 * 1024 + 1) { 1 })) }
        assertFalse(File(root, ".infantry").exists(), "a refused upload leaves no trace")
    }

    @Test
    fun purposeFileStoresAnyTypeUnderTheNamesExtension() {
        val stored = ImageUploads.store(DirectoryProjectFiles(project()), upload("hello".toByteArray(), purpose = "file", mime = "text/plain", name = "n.TXT"), 1L)
        assertTrue(stored.relPath.endsWith(".txt"))
        assertEquals("text/plain", stored.mimeType)
    }

    @Test
    fun theFrameAnswersImageOrTheOldRefusalText() {
        val root = project()
        val api = apiWith("p1", DirectoryProjectFiles(root))
        val payload = buildJsonObject {
            put("projectId", JsonPrimitive("p1")); put("purpose", JsonPrimitive("chat"))
            put("mimeType", JsonPrimitive("image/png")); put("base64", JsonPrimitive(Base64.getEncoder().encodeToString(png())))
        }.toString()
        val ok = ImageFrames.answer(api, Frame("image_upload", payload)) { 5L }
        assertEquals("image", ok.type, ok.data)
        assertTrue(Json.parseToJsonElement(ok.data).jsonObject["relPath"]!!.jsonPrimitive.content.startsWith(".infantry/uploads/chat/"))
        val bad = ImageFrames.answer(api, Frame("image_upload", """{"projectId":"p1","purpose":"zip","base64":"AA=="}""")) { 5L }
        assertEquals("error", bad.type)
        assertTrue(bad.data.startsWith("image_upload refused: purpose 'zip' is not accepted"), bad.data)
        val unknown = ImageFrames.answer(api, Frame("image_upload", """{"projectId":"p1","purpose":"chat","base64":"AA==","extra":1}""")) { 5L }
        assertTrue(unknown.data.startsWith("image_upload refused: "), unknown.data)
        val noProject = ImageFrames.answer(api, Frame("image_upload", """{"projectId":"zz","purpose":"chat","base64":"AA=="}""")) { 5L }
        assertTrue(noProject.data.startsWith("image_upload refused: no project 'zz'"), noProject.data)
    }

    private fun apiWith(id: String, files: ProjectFiles): DaemonApi = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DaemonApi::class.java)) { _, m, args ->
        if (m.name == "files") {
            if (args[0] != id) throw IllegalArgumentException("no project '${args[0]}'")
            files
        } else error("unexpected ${m.name}")
    } as DaemonApi

    // ---- IMG-5 retention ----

    private fun retention(files: ProjectFiles, now: () -> Long, days: () -> Int = { 14 }) =
        ChatImageRetention({ listOf("p1", "gone") }, { id -> if (id == "p1") files else throw IllegalArgumentException("no project") }, days, now)

    @Test
    fun anExpiredChatFileIsSweptWhileNewerAndTicketUploadsAreKept() {
        val files = DirectoryProjectFiles(project())
        var now = 1_000_000_000_000L
        val oldChat = ImageUploads.store(files, upload(png("old-chat")), now)
        val oldTicket = ImageUploads.store(files, upload(png("old-ticket"), purpose = "ticket"), now)
        now += TimeUnit.DAYS.toMillis(10)
        val newChat = ImageUploads.store(files, upload(png("new-chat")), now)
        now += TimeUnit.DAYS.toMillis(5)
        val deleted = retention(files, { now }).sweep()
        assertEquals(1, deleted.size)
        assertFalse(File(oldChat.path).exists())
        assertTrue(File(newChat.path).isFile)
        assertTrue(File(oldTicket.path).isFile, "a ticket upload is never swept, however old")
    }

    @Test
    fun retentionZeroKeepsEverythingAndADedupeReUploadRefreshesTheClock() {
        val files = DirectoryProjectFiles(project())
        var now = 1_000_000_000_000L
        val first = ImageUploads.store(files, upload(png("k")), now)
        now += TimeUnit.DAYS.toMillis(3650)
        assertEquals(emptyList(), retention(files, { now }, { 0 }).sweep())
        assertTrue(File(first.path).isFile)
        now = 1_000_000_000_000L
        ImageUploads.store(files, upload(png("k2")), now)
        now += TimeUnit.DAYS.toMillis(10)
        val again = ImageUploads.store(files, upload(png("k2")), now)
        now += TimeUnit.DAYS.toMillis(5)
        retention(files, { now }).sweep()
        assertTrue(File(again.path).isFile, "a re-uploaded image is still in use")
    }

    @Test
    fun theSettingIsReadAsAnIntegerInRangeElseTheDefault() {
        assertEquals(14, ChatImageRetention.daysOf(null))
        assertEquals(0, ChatImageRetention.daysOf("0"))
        assertEquals(30, ChatImageRetention.daysOf("30"))
        assertEquals(14, ChatImageRetention.daysOf("99999"))
        assertEquals(14, ChatImageRetention.daysOf("\"x\""))
        assertEquals(TimeUnit.HOURS.toMillis(6), ChatImageRetention.DEFAULT_INTERVAL_MILLIS)
    }
}
