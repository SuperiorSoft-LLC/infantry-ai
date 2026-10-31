package com.superiorsoftllc.agenthq.cli.mcp

import com.superiorsoftllc.agenthq.daemon.requireBoard

import com.superiorsoftllc.agenthq.cli.Cli
import com.superiorsoftllc.agenthq.cli.CliTestEnv
import com.superiorsoftllc.agenthq.daemon.DaemonFixture
import com.superiorsoftllc.agenthq.daemon.saveProfile
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * IMG-3: tickets carry image attachments, through the REAL `agenthq mcp serve` entrance against a real in-process
 * daemon, images uploaded by the REAL `agenthq image upload` CLI (purpose ticket), and the REAL ticket start composing
 * the worker brief with the daemon's own [com.superiorsoftllc.agenthq.context.TicketContextComposer].
 *
 * Acceptance: (1) a ticket created with an image reads it back from agenthq_ticket_get; (2) its start's brief lists
 * `[image: <abs path>]` under "Attached images", the file existing; (3) a stored path escaping uploads/ticket is refused and
 * the ticket is unchanged; (5) deleting a ticket deletes its unshared image; (6) a shared image survives deleting one
 * of its tickets; plus removing an image by update deletes it once no ticket references it.
 * Acceptance (4), a store with no images column, is `SchemaMigrationTest`.
 */
class McpTicketImagesE2eTest {

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

        fun callTool(name: String, arguments: String = "{}"): JsonObject =
            request("tools/call", """{"name":"$name","arguments":$arguments}""")["result"]!!.jsonObject

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
        val lineage = tempDir("agenthq-mcp-img-lineage")
        val args = arrayOf("mcp", "serve", "--port", fixture.port.toString())
        served.worker = thread(name = "mcp-serve-img-e2e", isDaemon = true) {
            Cli.run(args, serverOut, stderr, { null }, { serverIn }, cwd = cwd)
            serverOut.close()
        }
        servers += served
        served.request(
            "initialize",
            """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"img-e2e","version":"0"}}""",
        )
        return served
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

    private fun DaemonFixture.createProject(name: String, directory: File): String {
        send(Envelope.PROJECT_SAVE, """{"name":"$name","directory":${JsonPrimitive(directory.absolutePath)}}""")
        val frame = client.awaitWhere("project $name") { env ->
            env.type == Envelope.PROJECT && json.parseToJsonElement(env.data).jsonObject["name"]?.jsonPrimitive?.content == name
        }
        return json.parseToJsonElement(frame.data).jsonObject["id"]!!.jsonPrimitive.content
    }

    /** A real 1x1 PNG, salted so each upload is a distinct sha256. */
    private fun png(salt: String): ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
    ) + salt.toByteArray()

    /** `agenthq image upload <project> <file> --purpose <purpose>`: the stored file's absolute path. */
    private fun upload(port: Int, projectId: String, salt: String, purpose: String = "ticket"): File {
        val source = File(tempDir("agenthq-img3-src"), "$salt.png").apply { writeBytes(png(salt)) }
        val out = StringBuilder()
        val err = StringBuilder()
        val exit = Cli.run(
            arrayOf("image", "upload", projectId, source.absolutePath, "--purpose", purpose, "--port", port.toString()),
            out, err, CliTestEnv.getenv,
        )
        assertEquals(0, exit, "upload must succeed: out=$out err=$err")
        return File(out.toString().trim()).also { assertTrue(it.isFile, "uploaded file exists: $it") }
    }

    private fun rel(root: File, file: File): String =
        root.canonicalFile.toPath().relativize(file.canonicalFile.toPath()).toString().replace('\\', '/')

    private fun quoted(vararg paths: String) = paths.joinToString(",", "[", "]") { JsonPrimitive(it).toString() }

    @Test
    fun ticketsCarryImagesThroughMcpAndTheWorkerBriefListsThem() {
        val gated = GatedRunner()
        val state = tempDir("agenthq-mcp-img-daemon")
        val fixture = DaemonFixture.launch(stateDir = state, runner = gated, workingDir = File(state, "workdir").apply { mkdirs() }, pluginsRoot = com.superiorsoftllc.agenthq.daemon.TestDaemonPlugins.boardRoot())
            .also { fixtures += it; it.client.await(Envelope.READY) }
        val board = fixture.daemon.requireBoard()
        val root = tempDir("agenthq-mcp-img-project")
        val projectId = fixture.createProject("img3", root)
        fixture.client.saveProfile("""{"id":"backend-1","name":"Backend","role":"backend"}""")
        McpServeConfig.fileIn(root).apply { parentFile.mkdirs() }.writeText("""{"projectId":"$projectId"}""")
        val agent = serve(fixture, root)

        val a = upload(fixture.port, projectId, "a")
        val relA = rel(root, a)
        assertEquals(".agenthq/uploads/ticket/${a.name}", relA)

        // (1) create with an image via MCP; agenthq_ticket_get returns it.
        val created = payload(agent.callTool(
            "agenthq_ticket_create",
            """{"title":"Fix the header","role":"backend","images":${quoted(relA)}}""",
        ))
        val t1 = created.str("ticketId")
        assertEquals(listOf(relA), created.images(), "the create answer lists the stored image")
        assertEquals(listOf(relA), payload(agent.callTool("agenthq_ticket_get", """{"ticketId":"$t1"}""")).images())

        // (3) a stored-image path that escapes uploads/ticket is refused, and the ticket is unchanged. IMG-AGENT-1: a
        // LOCAL image file anywhere else is uploaded and attached instead (TicketImageAttachE2eTest), so the only
        // paths the daemon still sees and refuses are the ones naming uploads/ticket.
        val before = board.get(t1)
        val chat = upload(fixture.port, projectId, "chat", purpose = "chat")
        for (bad in listOf(".agenthq/uploads/ticket/../chat/${chat.name}", "${root.absolutePath}/.agenthq/uploads/ticket/../chat/${chat.name}")) {
            assertRefused(agent.callTool("agenthq_ticket_update", """{"ticketId":"$t1","images":${quoted(bad)}}"""), "uploads/ticket")
            assertEquals(before, board.get(t1), "a refused path leaves the ticket unchanged ($bad)")
        }

        // (6) two tickets share one image: deleting one keeps the file, the other still lists it.
        val b = upload(fixture.port, projectId, "b")
        val c = upload(fixture.port, projectId, "c")
        val relB = rel(root, b)
        val relC = rel(root, c)
        val t2 = payload(agent.callTool("agenthq_ticket_create", """{"title":"Shares b","images":${quoted(b.absolutePath)}}""")).str("ticketId")
        val t3 = payload(agent.callTool("agenthq_ticket_create", """{"title":"Shares b, owns c","images":${quoted(relB, relC)}}""")).str("ticketId")
        assertEquals(
            listOf(relB),
            payload(agent.callTool("agenthq_ticket_get", """{"ticketId":"$t2"}""")).images(),
            "an absolute path under uploads/ticket is stored as its relPath",
        )
        payload(agent.callTool("agenthq_ticket_delete", """{"ticketId":"$t2"}"""))
        assertTrue(b.isFile, "a file another ticket still references stays")
        val got3 = payload(agent.callTool("agenthq_ticket_get", """{"ticketId":"$t3"}"""))
        assertEquals(listOf(relB, relC), got3.images(), "the other ticket still lists the shared image")

        // (5) deleting a ticket deletes its unshared images.
        payload(agent.callTool("agenthq_ticket_delete", """{"ticketId":"$t3"}"""))
        assertFalse(b.exists(), "b is no longer referenced by any ticket: deleted")
        assertFalse(c.exists(), "c was only t3's: deleted")

        // Removing an image by update follows the same rule.
        val d = upload(fixture.port, projectId, "d")
        val t4 = payload(agent.callTool("agenthq_ticket_create", """{"title":"Owns d","images":${quoted(rel(root, d))}}""")).str("ticketId")
        val cleared = payload(agent.callTool("agenthq_ticket_update", """{"ticketId":"$t4","images":[]}"""))
        assertEquals(emptyList(), cleared.images())
        assertFalse(d.exists(), "an image removed by update and referenced by no ticket is deleted")

        // None of that touched t1's image or the chat upload.
        val absA = File(root, relA).absoluteFile.normalize()
        assertTrue(absA.isFile, "t1's image is still there")
        assertTrue(chat.isFile, "a chat upload is never a ticket's to delete")

        // LAST, so no push from the run lands while a later ticket_save waits for its answer (see the PM note:
        // FrameStream.request matches a `ticket` answer by type only).
        // (2) start it: the composed brief lists the image's absolute path, and the file exists.
        val started = agent.callTool("agenthq_ticket_start", """{"ticketId":"$t1"}""")
        assertFalse(text(started).isBlank())
        assertEquals("false", started["isError"]!!.jsonPrimitive.content, "start must succeed: ${text(started)}")
        fixture.await(what = "the worker's first prompt") { gated.prompts.isNotEmpty() }
        val brief = gated.prompts.single()
        assertTrue(brief.contains("Attached images"), "the brief has the Attached images heading: ${brief.takeLast(600)}")
        assertTrue(brief.contains("[image: ${absA.path}]"), "the brief lists [image: <abs path>]: ${brief.takeLast(600)}")
        assertTrue(absA.isFile, "the listed file exists")
    }

    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content
}
