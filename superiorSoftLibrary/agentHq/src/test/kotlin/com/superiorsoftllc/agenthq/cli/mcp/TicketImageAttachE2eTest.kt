package com.superiorsoftllc.agenthq.cli.mcp

import com.superiorsoftllc.agenthq.daemon.requireBoard

import com.superiorsoftllc.agenthq.cli.Cli
import com.superiorsoftllc.agenthq.cli.CliTestEnv
import com.superiorsoftllc.agenthq.daemon.DaemonFixture
import com.superiorsoftllc.agenthq.orchestration.GatedRunner
import com.superiorsoftllc.agenthq.protocol.Envelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.File
import java.io.PipedReader
import java.io.PipedWriter
import java.io.PrintWriter
import java.nio.file.Files
import java.util.Base64
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * IMG-AGENT-1: an agent attaches an image to a ticket by naming the FILE it has, through the REAL `agenthq mcp serve`
 * (`images` / `addImages` / `removeImages`) and the REAL CLI (`ticket create|update --image`, `--remove-image`), against
 * a real in-process daemon. Each local file is uploaded with `image_upload` purpose ticket, so the ticket's `images`
 * (what the board UI shows as attachments) holds `.agenthq/uploads/ticket/<sha256>.png`.
 *
 * Refusals: a missing file, a file that is not png/jpeg/gif/webp, and a file over 10 MB are refused with a message
 * naming the file, and NOTHING is saved: no ticket created or changed, and no upload written, even for the good
 * files listed alongside the bad one.
 */
class TicketImageAttachE2eTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val dirs = mutableListOf<File>()
    private val fixtures = mutableListOf<DaemonFixture>()
    private val servers = mutableListOf<Served>()

    @AfterTest
    fun cleanUp() {
        servers.forEach { it.close() }
        fixtures.asReversed().forEach { runCatching { it.stop() }; runCatching { it.deleteState() } }
        dirs.forEach { it.deleteRecursively() }
    }

    private fun tempDir(prefix: String) = Files.createTempDirectory(prefix).toFile().also { dirs += it }

    private class Served(private val stdin: PrintWriter, private val stdout: BufferedReader, val stderr: StringBuffer) {
        lateinit var worker: Thread
        private var nextId = 1

        fun request(method: String, params: String = "{}"): JsonObject {
            val id = nextId++
            stdin.print("""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""" + "\n")
            stdin.flush()
            val line = stdout.readLine() ?: error("stdout closed; stderr=$stderr")
            val answer = Json.parseToJsonElement(line).jsonObject
            assertEquals(id, answer["id"]!!.jsonPrimitive.int, "answer must carry the request id: $answer")
            return answer
        }

        /** The board tools answer terse text by default; these tests read the JSON payload (verbose). */
        private fun verbose(a: String) = if (a.trim() == "{}") """{"verbose":true}""" else a.replaceFirst("{", """{"verbose":true,""")

        fun callTool(name: String, arguments: String = "{}"): JsonObject =
            request("tools/call", """{"name":"$name","arguments":${verbose(arguments)}}""")["result"]!!.jsonObject

        fun close() {
            runCatching { stdin.close() }
            worker.join(5_000)
        }
    }

    private fun serve(fixture: DaemonFixture, cwd: File): Served {
        val toServer = PipedWriter()
        val serverIn = PipedReader(toServer, 1 shl 16)
        val fromServer = PipedReader(1 shl 16)
        val serverOut = PrintWriter(PipedWriter(fromServer))
        val stderr = StringBuffer()
        val served = Served(PrintWriter(toServer), BufferedReader(fromServer), stderr)
        val lineage = tempDir("agenthq-img-attach-lineage")
        val args = arrayOf("mcp", "serve", "--port", fixture.port.toString())
        served.worker = thread(name = "mcp-serve-img-attach", isDaemon = true) {
            Cli.run(args, serverOut, stderr, { null }, { serverIn }, cwd = cwd)
            serverOut.close()
        }
        servers += served
        served.request(
            "initialize",
            """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"img-attach","version":"0"}}""",
        )
        return served
    }

    private class Run(val exit: Int, val out: String, val err: String)

    private fun cli(port: Int, vararg args: String): Run {
        val out = StringBuilder()
        val err = StringBuilder()
        val exit = Cli.run(arrayOf(*args, "--port", port.toString()), out, err, CliTestEnv.getenv)
        return Run(exit, out.toString(), err.toString())
    }

    private fun text(result: JsonObject): String =
        result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content

    private fun payload(result: JsonObject): JsonObject {
        assertEquals("false", result["isError"]!!.jsonPrimitive.content, "tool reported an error: ${text(result)}")
        return Json.parseToJsonElement(text(result)).jsonObject
    }

    private fun assertRefused(result: JsonObject, contains: String) {
        assertEquals("true", result["isError"]!!.jsonPrimitive.content, "expected a refusal: ${text(result)}")
        assertTrue(text(result).contains(contains), "refusal must say '$contains': ${text(result)}")
    }

    private fun JsonObject.images(): List<String> =
        (this["images"] ?: error("the answer carries no images field: $this")).jsonArray.map { it.jsonPrimitive.content }

    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    private fun DaemonFixture.createProject(name: String, directory: File): String {
        send(Envelope.PROJECT_SAVE, """{"name":"$name","directory":${JsonPrimitive(directory.absolutePath)}}""")
        val frame = client.awaitWhere("project $name") { env ->
            env.type == Envelope.PROJECT && json.parseToJsonElement(env.data).jsonObject["name"]?.jsonPrimitive?.content == name
        }
        return json.parseToJsonElement(frame.data).jsonObject["id"]!!.jsonPrimitive.content
    }

    /** A real 1x1 PNG, salted so each file is a distinct sha256. */
    private fun png(salt: String): ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
    ) + salt.toByteArray()

    private fun pngFile(name: String, salt: String = name): File =
        File(tempDir("agenthq-img-attach-src"), "$name.png").apply { writeBytes(png(salt)) }

    private fun quoted(vararg paths: String) = paths.joinToString(",", "[", "]") { JsonPrimitive(it).toString() }

    private fun uploads(root: File): Set<String> =
        File(root, ".agenthq/uploads/ticket").listFiles()?.map { it.name }?.toSet().orEmpty()

    private fun setUp(name: String): Triple<DaemonFixture, File, String> {
        val state = tempDir("agenthq-img-attach-daemon")
        val fixture = DaemonFixture.launch(stateDir = state, runner = GatedRunner(), workingDir = File(state, "workdir").apply { mkdirs() }, pluginsRoot = com.superiorsoftllc.agenthq.daemon.TestDaemonPlugins.boardRoot("official/Infantry_Chat"))
            .also { fixtures += it; it.client.await(Envelope.READY) }
        val root = tempDir("agenthq-img-attach-project")
        return Triple(fixture, root, fixture.createProject(name, root))
    }

    /** The three refused files: missing, not an image (a text file named .png), and 1 byte over 10 MB (a real PNG head). */
    private fun badFiles(): List<Pair<File, String>> {
        val dir = tempDir("agenthq-img-attach-bad")
        val missing = File(dir, "missing.png")
        val text = File(dir, "notes.png").apply { writeText("not an image at all") }
        val huge = File(dir, "huge.png").apply { writeBytes(png("huge") + ByteArray(10 * 1024 * 1024)) }
        return listOf(missing to "does not exist", text to "not a png, jpeg, gif or webp", huge to "10 MB")
    }

    @Test
    fun mcpCreateAndUpdateAttachLocalFilesAndRefuseBadOnesSavingNothing() {
        val (fixture, root, projectId) = setUp("img-attach-mcp")
        val board = fixture.daemon.requireBoard()
        McpServeConfig.fileIn(root).apply { parentFile.mkdirs() }.writeText("""{"projectId":"$projectId"}""")
        val agent = serve(fixture, root)

        // Create with a local file: it is uploaded, and the ticket's images holds its relPath.
        val a = pngFile("screenshot-a")
        val created = payload(agent.callTool("agenthq_ticket_create", """{"title":"Has a screenshot","images":${quoted(a.absolutePath)}}"""))
        val t1 = created.str("ticketId")
        val relA = created.images().single()
        assertTrue(relA.startsWith(".agenthq/uploads/ticket/") && relA.endsWith(".png"), "stored as a ticket upload: $relA")
        assertTrue(File(root, relA).readBytes().contentEquals(a.readBytes()), "the stored file is the agent's file")
        assertEquals(listOf(relA), board.get(t1).images, "the board's ticket carries the image")
        assertTrue(created.str("message").contains("1 image(s)"), "the answer says the image was saved: $created")

        // The same bytes again land at the same path (the image upload dedupe).
        val again = payload(agent.callTool("agenthq_ticket_create", """{"title":"Same bytes","images":${quoted(pngFile("copy", salt = "screenshot-a").absolutePath)}}"""))
        assertEquals(listOf(relA), again.images(), "identical bytes give the same path")

        // addImages appends a local file; removeImages drops one, named by its original file.
        val b = pngFile("screenshot-b")
        val added = payload(agent.callTool("agenthq_ticket_update", """{"ticketId":"$t1","addImages":${quoted(b.absolutePath)}}"""))
        assertEquals(2, added.images().size, "addImages keeps the stored image and adds one: ${added.images()}")
        assertEquals(relA, added.images().first())
        val relB = added.images()[1]
        val removed = payload(agent.callTool("agenthq_ticket_update", """{"ticketId":"$t1","removeImages":${quoted(a.absolutePath)}}"""))
        assertEquals(listOf(relB), removed.images(), "removeImages by the original file drops that image")
        // ...or by its stored file name.
        val byName = payload(agent.callTool("agenthq_ticket_update", """{"ticketId":"$t1","removeImages":${quoted(relB.substringAfterLast('/'))}}"""))
        assertEquals(emptyList(), byName.images())

        // A removal naming an image the ticket does not have, or images together with addImages, is refused.
        val before = board.get(t1)
        assertRefused(agent.callTool("agenthq_ticket_update", """{"ticketId":"$t1","removeImages":["nope.png"]}"""), "has no image 'nope.png'")
        assertRefused(
            agent.callTool("agenthq_ticket_update", """{"ticketId":"$t1","images":[],"addImages":${quoted(b.absolutePath)}}"""),
            "not both",
        )
        assertEquals(before, board.get(t1), "a refused edit leaves the ticket unchanged")

        // Each bad file is refused, on create and update, with a good file alongside it: nothing is saved or uploaded.
        val good = pngFile("good-but-with-bad-company")
        for ((bad, why) in badFiles()) {
            val ticketsBefore = board.list().size
            val uploadsBefore = uploads(root)
            assertRefused(
                agent.callTool("agenthq_ticket_create", """{"title":"Never saved","images":${quoted(good.absolutePath, bad.absolutePath)}}"""),
                why,
            )
            assertRefused(
                agent.callTool("agenthq_ticket_update", """{"ticketId":"$t1","addImages":${quoted(good.absolutePath, bad.absolutePath)}}"""),
                why,
            )
            assertEquals(ticketsBefore, board.list().size, "a refused create saves no ticket (${bad.name})")
            assertEquals(before, board.get(t1), "a refused update leaves the ticket unchanged (${bad.name})")
            assertEquals(uploadsBefore, uploads(root), "a refusal writes no upload, not even the good file (${bad.name})")
        }
    }

    @Test
    fun cliImageFlagsAttachShowAndRemoveAndRefuseBadFilesSavingNothing() {
        val (fixture, root, projectId) = setUp("img-attach-cli")
        val board = fixture.daemon.requireBoard()
        val port = fixture.port

        val a = pngFile("cli-a")
        val b = pngFile("cli-b")
        val created = cli(port, "ticket", "create", "--title", "CLI screenshot", "--project", projectId, "--image", a.absolutePath, "--image", b.absolutePath)
        assertEquals(0, created.exit, "create must succeed: out=${created.out} err=${created.err}")
        assertTrue(created.out.contains("(2 image(s))"), created.out)
        val ticket = board.list().single { it.title == "CLI screenshot" }
        assertEquals(2, ticket.images.size, "the board's ticket carries both images: ${ticket.images}")
        val absA = File(root, ticket.images[0]).absoluteFile.normalize()
        assertTrue(absA.readBytes().contentEquals(a.readBytes()), "the stored file is the given file")

        // ticket show prints IMAGES with each absolute path.
        val shown = cli(port, "ticket", "show", ticket.id, "--project", projectId)
        assertEquals(0, shown.exit, shown.err)
        assertTrue(shown.out.contains("IMAGES:"), shown.out)
        for (rel in ticket.images) {
            val abs = File(root, rel).absoluteFile.normalize().path
            assertTrue(shown.out.contains("  $abs"), "ticket show lists $abs: ${shown.out}")
        }

        // update --image ADDS; --remove-image drops one named by the path `ticket show` printed.
        val c = pngFile("cli-c")
        val added = cli(port, "ticket", "update", ticket.id, "--project", projectId, "--image", c.absolutePath)
        assertEquals(0, added.exit, "update must succeed: out=${added.out} err=${added.err}")
        assertTrue(added.out.contains("3 image(s)"), added.out)
        assertEquals(ticket.images, board.get(ticket.id).images.take(2), "the stored images stay, in order")
        val dropped = cli(port, "ticket", "update", ticket.id, "--project", projectId, "--remove-image", absA.path)
        assertEquals(0, dropped.exit, "remove must succeed: out=${dropped.out} err=${dropped.err}")
        assertEquals(2, board.get(ticket.id).images.size)
        assertTrue(ticket.images[0] !in board.get(ticket.id).images, "the removed image is gone from the ticket")

        // A removal naming no attached image is refused; nothing changes.
        val before = board.get(ticket.id)
        val unknown = cli(port, "ticket", "update", ticket.id, "--project", projectId, "--remove-image", "nope.png")
        assertNotEquals(0, unknown.exit)
        assertTrue(unknown.err.contains("has no image 'nope.png'"), unknown.err)
        assertEquals(before, board.get(ticket.id))

        // Each bad file is refused with a clear message; no ticket saved or changed, no upload written.
        val good = pngFile("cli-good")
        for ((bad, why) in badFiles()) {
            val ticketsBefore = board.list().size
            val uploadsBefore = uploads(root)
            val create = cli(port, "ticket", "create", "--title", "Never saved", "--project", projectId, "--image", good.absolutePath, "--image", bad.absolutePath)
            assertNotEquals(0, create.exit, "a bad file refuses the create (${bad.name})")
            assertTrue(create.err.contains(why) && create.err.contains("nothing was saved"), create.err)
            val update = cli(port, "ticket", "update", ticket.id, "--project", projectId, "--image", good.absolutePath, "--image", bad.absolutePath)
            assertNotEquals(0, update.exit, "a bad file refuses the update (${bad.name})")
            assertTrue(update.err.contains(why), update.err)
            assertEquals(ticketsBefore, board.list().size, "no ticket saved (${bad.name})")
            assertEquals(before, board.get(ticket.id), "the ticket is unchanged (${bad.name})")
            assertEquals(uploadsBefore, uploads(root), "no upload written (${bad.name})")
        }
    }
}
