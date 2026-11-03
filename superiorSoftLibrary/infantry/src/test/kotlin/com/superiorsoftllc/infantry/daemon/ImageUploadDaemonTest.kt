package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.cli.Cli
import com.superiorsoftllc.infantry.cli.CliTestEnv
import com.superiorsoftllc.infantry.orchestration.GatedRunner
import com.superiorsoftllc.infantry.protocol.Envelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * IMG-1: `image_upload` stores an image under `<project directory>/.infantry/uploads/<sha256>.<ext>` and answers its
 * path. Every test runs a REAL daemon and drives it through the REAL `infantry image upload` CLI over the real
 * WebSocket (or the raw socket, for the daemon-side refusals the CLI cannot reach), then reads the disk.
 */
class ImageUploadDaemonTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val cleanups = mutableListOf<() -> Unit>()

    @AfterTest
    fun cleanUp() {
        cleanups.asReversed().forEach { runCatching { it() } }
        cleanups.clear()
    }

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile().also { dir ->
        cleanups += { dir.deleteRecursively() }
    }

    private fun launch(): DaemonFixture {
        val state = tempDir("infantry-img-state")
        val fixture = DaemonFixture.launch(
            stateDir = state, runner = GatedRunner(), workingDir = File(state, "workdir").apply { mkdirs() },
            // DP-10a: image_upload is answered by the Infantry_Chat daemon plugin.
            pluginsRoot = TestDaemonPlugins.root("official/Infantry_Chat").also { r -> cleanups += { r.deleteRecursively() } },
        )
        cleanups += { fixture.stop() }
        fixture.client.await(Envelope.READY)
        return fixture
    }

    private fun DaemonFixture.createProject(name: String, directory: File): String {
        send(Envelope.PROJECT_SAVE, """{"name":"$name","directory":${JsonPrimitive(directory.absolutePath)}}""")
        val frame = client.awaitWhere("project $name") { env ->
            env.type == Envelope.PROJECT && json.parseToJsonElement(env.data).jsonObject["name"]?.jsonPrimitive?.content == name
        }
        return json.parseToJsonElement(frame.data).jsonObject["id"]!!.jsonPrimitive.content
    }

    private data class Result(val exit: Int, val out: String, val err: String)

    private fun cli(port: Int, vararg args: String): Result {
        val out = StringBuilder()
        val err = StringBuilder()
        val exit = Cli.run(arrayOf(*args, "--port", port.toString()), out, err, CliTestEnv.getenv)
        return Result(exit, out.toString(), err.toString())
    }

    /** A real 1x1 PNG, with [salt] appended so two tests' bytes differ (the magic bytes are what is checked). */
    private fun png(salt: String = ""): ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
    ) + salt.toByteArray()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun uploads(root: File) = File(root, ".infantry/uploads")
    private fun purposeDir(root: File, purpose: String) = File(uploads(root), purpose)

    /** Acceptance 1 + 5: the file is at the returned path, byte-identical, sha matching; the .gitignore exists. */
    @Test
    fun aPngIsStoredByteIdenticalAtThePrintedPathWithAGitignore() {
        val root = tempDir("infantry-img-proj")
        val source = File(tempDir("infantry-img-src"), "shot.png").apply { writeBytes(png("one")) }
        launch().run {
            val projectId = createProject("img", root)
            val result = cli(port, "image", "upload", projectId, source.absolutePath)
            assertEquals(0, result.exit, "upload must succeed: out=${result.out} err=${result.err}")
            val printed = File(result.out.trim())
            val sha = sha256(source.readBytes())
            assertEquals(File(purposeDir(root, "ticket"), "$sha.png").canonicalPath, printed.canonicalPath, "the CLI defaults to purpose ticket: uploads/ticket/<sha256>.png")
            assertTrue(printed.isFile, "the printed path must exist on disk")
            assertContentEquals(source.readBytes(), printed.readBytes(), "the stored file is byte-identical")
            assertEquals(sha, sha256(printed.readBytes()))
            val ignore = File(uploads(root), ".gitignore")
            assertTrue(ignore.isFile, "uploads/.gitignore must exist after the first upload")
            assertEquals("*", ignore.readText().trim())
        }
    }

    /** The raw frame answers every field of the contract, sha256 and bytes from the daemon's own read. */
    @Test
    fun theImageFrameCarriesPathRelPathShaBytesAndMime() {
        val root = tempDir("infantry-img-frame")
        val bytes = png("frame")
        launch().run {
            val projectId = createProject("frame", root)
            val answer = upload(projectId, "x.png", "image/png", bytes, purpose = "chat")
            assertEquals("image", answer.type, "image_upload must be answered by an image frame: ${answer.data}")
            val o = json.parseToJsonElement(answer.data).jsonObject
            val sha = sha256(bytes)
            assertEquals(".infantry/uploads/chat/$sha.png", o["relPath"]!!.jsonPrimitive.content)
            assertEquals(File(purposeDir(root, "chat"), "$sha.png").canonicalPath, File(o["path"]!!.jsonPrimitive.content).canonicalPath)
            assertTrue(File(o["path"]!!.jsonPrimitive.content).isAbsolute, "path is absolute")
            assertEquals(sha, o["sha256"]!!.jsonPrimitive.content)
            assertEquals(bytes.size.toLong(), o["bytes"]!!.jsonPrimitive.content.toLong())
            assertEquals("image/png", o["mimeType"]!!.jsonPrimitive.content)
        }
    }

    /** Acceptance 2: the same bytes twice give the same path and ONE file on disk. */
    @Test
    fun theSameBytesTwiceGiveTheSamePathAndOneFile() {
        val root = tempDir("infantry-img-dedupe")
        val src = tempDir("infantry-img-src2")
        val a = File(src, "a.png").apply { writeBytes(png("same")) }
        val b = File(src, "b-copy.png").apply { writeBytes(png("same")) }
        launch().run {
            val projectId = createProject("dedupe", root)
            val first = cli(port, "image", "upload", projectId, a.absolutePath)
            val second = cli(port, "image", "upload", projectId, b.absolutePath)
            assertEquals(0, first.exit, first.err)
            assertEquals(0, second.exit, second.err)
            assertEquals(first.out.trim(), second.out.trim(), "same bytes, same path")
            val images = purposeDir(root, "ticket").listFiles().orEmpty().toList()
            assertEquals(1, images.size, "one file on disk, saw ${images.map { it.name }}")
        }
    }

    /** Acceptance 3: a .txt renamed .png is refused by its magic bytes, and nothing is written. */
    @Test
    fun aTextFileRenamedPngIsRefusedAndNothingIsWritten() {
        val root = tempDir("infantry-img-magic")
        val fake = File(tempDir("infantry-img-src3"), "notes.png").apply { writeText("just some text, not an image\n") }
        launch().run {
            val projectId = createProject("magic", root)
            val result = cli(port, "image", "upload", projectId, fake.absolutePath)
            assertEquals(1, result.exit, "a fake png must be refused: out=${result.out} err=${result.err}")
            assertTrue(result.err.contains("image_upload refused"), "a clear refusal: ${result.err}")
            assertTrue(result.err.contains("not a png, jpeg, gif or webp"), "names what is accepted: ${result.err}")
            assertFalse(File(root, ".infantry").exists(), "nothing written, not even the uploads folder")
        }
    }

    /** A real png declared as a jpeg is refused: the mime string must agree with the bytes. */
    @Test
    fun aMimeThatContradictsTheBytesIsRefused() {
        val root = tempDir("infantry-img-mime")
        launch().run {
            val projectId = createProject("mime", root)
            val answer = upload(projectId, "x.jpg", "image/jpeg", png("mime"))
            assertEquals(Envelope.ERROR, answer.type, answer.data)
            assertTrue(answer.data.startsWith("image_upload refused:"), answer.data)
            assertFalse(File(root, ".infantry").exists())
        }
    }

    /** Acceptance 4: a file over 10 MB is refused, and nothing is written. */
    @Test
    fun aFileOverTenMegabytesIsRefusedAndNothingIsWritten() {
        val root = tempDir("infantry-img-big")
        val big = File(tempDir("infantry-img-src4"), "big.png").apply {
            writeBytes(png() + ByteArray(10 * 1024 * 1024))
        }
        launch().run {
            val projectId = createProject("big", root)
            val result = cli(port, "image", "upload", projectId, big.absolutePath)
            assertEquals(1, result.exit, "an oversized upload must be refused: out=${result.out} err=${result.err}")
            assertTrue(result.err.contains("image_upload refused"), result.err)
            assertTrue(result.err.contains("10 MB"), "names the limit: ${result.err}")
            assertFalse(File(root, ".infantry").exists(), "nothing written")
        }
    }

    /** An unknown project id is refused. */
    @Test
    fun anUnknownProjectIsRefused() {
        launch().run {
            val answer = upload("no-such-project", "x.png", "image/png", png("unknown"))
            assertEquals(Envelope.ERROR, answer.type, answer.data)
            assertTrue(answer.data.startsWith("image_upload refused: no project 'no-such-project'"), answer.data)
        }
    }

    /** [purpose] null = the key is left out of the payload entirely. */
    /** Acceptance 6: the same bytes as chat and as ticket give two files, one per purpose folder, one .gitignore. */
    @Test
    fun theSameBytesAsChatAndAsTicketGiveTwoFiles() {
        val root = tempDir("infantry-img-purpose")
        val source = File(tempDir("infantry-img-src5"), "both.png").apply { writeBytes(png("both")) }
        launch().run {
            val projectId = createProject("purpose", root)
            val chat = cli(port, "image", "upload", projectId, source.absolutePath, "--purpose", "chat")
            val ticket = cli(port, "image", "upload", projectId, source.absolutePath, "--purpose", "ticket")
            assertEquals(0, chat.exit, chat.err)
            assertEquals(0, ticket.exit, ticket.err)
            val sha = sha256(source.readBytes())
            assertEquals(File(purposeDir(root, "chat"), "$sha.png").canonicalPath, File(chat.out.trim()).canonicalPath)
            assertEquals(File(purposeDir(root, "ticket"), "$sha.png").canonicalPath, File(ticket.out.trim()).canonicalPath)
            assertTrue(File(chat.out.trim()).isFile && File(ticket.out.trim()).isFile, "two files on disk")
            assertTrue(File(uploads(root), ".gitignore").isFile, "one .gitignore at uploads/")
            assertFalse(File(purposeDir(root, "chat"), ".gitignore").exists(), "no per-purpose .gitignore")
            assertFalse(File(purposeDir(root, "ticket"), ".gitignore").exists(), "no per-purpose .gitignore")
        }
    }

    /** An unknown purpose, and a missing one, are refused with nothing written. */
    @Test
    fun anUnknownOrMissingPurposeIsRefused() {
        val root = tempDir("infantry-img-badpurpose")
        launch().run {
            val projectId = createProject("badpurpose", root)
            val unknown = upload(projectId, "x.png", "image/png", png("p1"), purpose = "avatar")
            assertEquals(Envelope.ERROR, unknown.type, unknown.data)
            assertTrue(unknown.data.startsWith("image_upload refused: purpose 'avatar'"), unknown.data)
            val missing = upload(projectId, "x.png", "image/png", png("p2"), purpose = null)
            assertEquals(Envelope.ERROR, missing.type, missing.data)
            assertTrue(missing.data.startsWith("image_upload refused:"), missing.data)
            assertFalse(File(root, ".infantry").exists(), "nothing written")
            val cli = cli(port, "image", "upload", projectId, File(tempDir("infantry-img-src6"), "y.png").apply { writeBytes(png("p3")) }.absolutePath, "--purpose", "avatar")
            assertEquals(2, cli.exit, "the CLI refuses an unknown --purpose as a usage error: ${cli.err}")
        }
    }

    /**
     * ATTACH-INLINE-1 (contract 1.89.0): purpose `file` takes any bytes under its own extension, up to 20 MB; the same
     * text as `chat` is still refused, and a file over 20 MB writes nothing.
     */
    @Test
    fun purposeFileStoresAnyFileUpTo20Mb() {
        val root = tempDir("infantry-img-file")
        val text = "a,b\n1,2\n".toByteArray()
        launch().run {
            val projectId = createProject("files", root)
            val answer = upload(projectId, "notes.CSV", "", text, purpose = "file")
            assertEquals("image", answer.type, answer.data)
            val o = json.parseToJsonElement(answer.data).jsonObject
            val sha = sha256(text)
            assertEquals(".infantry/uploads/file/$sha.csv", o["relPath"]!!.jsonPrimitive.content)
            assertEquals(text.toList(), File(o["path"]!!.jsonPrimitive.content).readBytes().toList())
            assertEquals("application/octet-stream", o["mimeType"]!!.jsonPrimitive.content)
            val odd = upload(projectId, "no extension", "text/plain", "odd".toByteArray(), purpose = "file")
            assertTrue(json.parseToJsonElement(odd.data).jsonObject["relPath"]!!.jsonPrimitive.content.endsWith(".bin"), odd.data)

            val asChat = upload(projectId, "notes.csv", "", text, purpose = "chat")
            assertEquals(Envelope.ERROR, asChat.type, "a chat IMAGE is still checked by its bytes: ${asChat.data}")
            val huge = upload(projectId, "big.bin", "", ByteArray(20 * 1024 * 1024 + 1), purpose = "file")
            assertEquals(Envelope.ERROR, huge.type, huge.data)
            assertTrue(huge.data.contains("20 MB"), huge.data)
            assertEquals(2, purposeDir(root, "file").listFiles().orEmpty().count { it.isFile }, "nothing written for the refusals")
        }
    }

    private fun DaemonFixture.upload(
        projectId: String, fileName: String, mimeType: String, bytes: ByteArray, purpose: String? = "ticket",
    ): Envelope {
        val from = client.mark()
        val payload = buildJsonObject {
            put("projectId", JsonPrimitive(projectId))
            purpose?.let { put("purpose", JsonPrimitive(it)) }
            put("fileName", JsonPrimitive(fileName))
            put("mimeType", JsonPrimitive(mimeType))
            put("base64", JsonPrimitive(Base64.getEncoder().encodeToString(bytes)))
        }
        send("image_upload", payload.toString())
        return client.awaitAfter(from, "the image_upload answer") { it.type == "image" || it.type == Envelope.ERROR }
    }
}
