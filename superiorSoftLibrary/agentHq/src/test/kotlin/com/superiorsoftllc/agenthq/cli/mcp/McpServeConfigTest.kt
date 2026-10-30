package com.superiorsoftllc.agenthq.cli.mcp

import com.superiorsoftllc.agenthq.cli.Cli
import com.superiorsoftllc.agenthq.daemon.DaemonFixture
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.runner.StubScript
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.File
import java.io.PipedReader
import java.io.PipedWriter
import java.io.PrintWriter
import java.io.StringReader
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * P11-C: project-scoped defaults for `agenthq mcp serve` -- `--project` and an optional `.agenthq/mcp.json` in the
 * working directory. The resolution is tested both as the pure merge and through the REAL [Cli.run] (the
 * effective values are what the server logs at start); the default project is tested end to end: a spawn with
 * no `projectId` lands in the configured project on a real in-process daemon.
 */
class McpServeConfigTest {

    private val dirs = mutableListOf<File>()
    private val fixtures = mutableListOf<DaemonFixture>()
    private val workers = mutableListOf<Pair<PrintWriter, Thread>>()

    @AfterTest
    fun cleanUp() {
        workers.forEach { (stdin, worker) -> runCatching { stdin.close() }; worker.join(5_000) }
        fixtures.asReversed().forEach { runCatching { it.stop() }; runCatching { it.deleteState() } }
        dirs.forEach { it.deleteRecursively() }
    }

    private fun projectDir(mcpJson: String? = null): File {
        val dir = Files.createTempDirectory("agenthq-mcp-config").toFile().also { dirs += it }
        if (mcpJson != null) McpServeConfig.fileIn(dir).apply { parentFile.mkdirs() }.writeText(mcpJson)
        return dir
    }

    private class Run(val exit: Int, val stderr: String)

    /** Runs `mcp serve` with stdin already at EOF: it logs its effective config, then exits 0. */
    private fun startOnly(cwd: File, vararg flags: String): Run {
        val stderr = StringBuilder()
        val exit = Cli.run(
            arrayOf("mcp", "serve", "--port", "1", *flags),
            StringBuilder(), stderr, { null }, { StringReader("") }, cwd = cwd,
        )
        return Run(exit, stderr.toString())
    }

    // ---- the pure merge ----

    @Test
    fun theFileAloneSetsEveryDefault() {
        val file = McpServeConfig.parse(
            """{"projectId":"alpha","maxDepth":1,"maxSpawns":3,"allowKillAny":true}""", File("mcp.json"),
        )
        assertEquals(McpServeConfig("alpha", 1, 3, true), McpServeConfig.merge(McpServeConfig(), file))
    }

    @Test
    fun aFlagOverridesTheFileFieldByField() {
        val file = McpServeConfig("alpha", 1, 3, null)
        val merged = McpServeConfig.merge(McpServeConfig(projectId = "beta", maxSpawns = 7), file)
        assertEquals(McpServeConfig("beta", 1, 7, null), merged)
    }

    @Test
    fun aMalformedFileIsRefusedWithAReason() {
        val f = File("mcp.json")
        assertTrue(assertFailsWith<McpConfigException> { McpServeConfig.parse("{not json", f) }.message!!.contains("not valid JSON"))
        assertTrue(assertFailsWith<McpConfigException> { McpServeConfig.parse("[]", f) }.message!!.contains("JSON object"))
        assertTrue(assertFailsWith<McpConfigException> { McpServeConfig.parse("""{"maxSpawn":1}""", f) }.message!!.contains("'maxSpawn'"))
        assertTrue(assertFailsWith<McpConfigException> { McpServeConfig.parse("""{"maxDepth":"2"}""", f) }.message!!.contains("'maxDepth'"))
        assertTrue(assertFailsWith<McpConfigException> { McpServeConfig.parse("""{"maxSpawns":-1}""", f) }.message!!.contains("'maxSpawns'"))
        assertTrue(assertFailsWith<McpConfigException> { McpServeConfig.parse("""{"projectId":""}""", f) }.message!!.contains("'projectId'"))
        assertTrue(assertFailsWith<McpConfigException> { McpServeConfig.parse("""{"allowKillAny":"yes"}""", f) }.message!!.contains("'allowKillAny'"))
    }

    // ---- through the CLI ----

    @Test
    fun theCliAppliesTheFileWhenNoFlagIsGiven() {
        val run = startOnly(projectDir("""{"projectId":"alpha","maxDepth":1,"maxSpawns":3,"allowKillAny":true}"""))
        assertEquals(0, run.exit, run.stderr)
        assertTrue(
            run.stderr.contains("agenthq mcp: depth 0/1, max 3 open spawns, project alpha"),
            "the effective config comes from the file: ${run.stderr}",
        )
    }

    @Test
    fun cliFlagsOverrideTheFile() {
        val run = startOnly(
            projectDir("""{"projectId":"alpha","maxDepth":1,"maxSpawns":3}"""),
            "--project", "beta", "--max-spawns", "5",
        )
        assertEquals(0, run.exit, run.stderr)
        assertTrue(
            run.stderr.contains("agenthq mcp: depth 0/1, max 5 open spawns, project beta"),
            "flags win, the file fills the rest: ${run.stderr}",
        )
    }

    @Test
    fun withNeitherTheBuiltInDefaultsApply() {
        val run = startOnly(projectDir())
        assertEquals(0, run.exit, run.stderr)
        assertTrue(
            run.stderr.contains("agenthq mcp: depth 0/daemon limits.concurrency.maxDepth, max daemon limits.concurrency.mcpMaxSpawns open spawns, no default project"),
            run.stderr,
        )
    }

    @Test
    fun aMalformedFileStopsTheServerWithAClearStderrError() {
        val dir = projectDir("""{"maxSpawns": "four"}""")
        val run = startOnly(dir)
        assertEquals(2, run.exit, "a bad config is a usage error: ${run.stderr}")
        val path = McpServeConfig.fileIn(dir).absolutePath
        assertTrue(
            run.stderr.contains("error: .agenthq/mcp.json ($path) 'maxSpawns' must be a whole number >= 0"),
            "the error names the file and the key: ${run.stderr}",
        )
        assertTrue(!run.stderr.contains("serving MCP"), "the server never starts: ${run.stderr}")
    }

    // ---- the default project, end to end ----

    @Test
    fun spawnWithoutAProjectIdUsesTheConfiguredDefault() {
        val fixture = DaemonFixture.launch(StubScript.default(), null, Files.createTempDirectory("agenthq-mcp-config-d").toFile(),
            pluginsRoot = com.superiorsoftllc.agenthq.daemon.TestDaemonPlugins.boardRoot("official/Infantry_Agents"))
            .also { fixtures += it; it.client.await(Envelope.READY) }
        val cwd = projectDir("""{"projectId":"alpha"}""")

        val toServer = PipedWriter()
        val serverIn = PipedReader(toServer, 1 shl 16)
        val fromServer = PipedReader(1 shl 16)
        val serverOut = PrintWriter(PipedWriter(fromServer))
        val stdout = BufferedReader(fromServer)
        val stdin = PrintWriter(toServer)
        val stderr = StringBuffer()
        val args = arrayOf("mcp", "serve", "--port", fixture.port.toString())
        val worker = thread(name = "mcp-serve-config", isDaemon = true) {
            Cli.run(args, serverOut, stderr, { null }, { serverIn }, cwd = cwd)
            serverOut.close()
        }
        workers += stdin to worker

        fun call(id: Int, method: String, params: String): JsonObject {
            stdin.print("""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""" + "\n")
            stdin.flush()
            return Json.parseToJsonElement(stdout.readLine() ?: error("stdout closed; stderr=$stderr")).jsonObject
        }
        call(1, "initialize", """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"0"}}""")

        val result = call(2, "tools/call", """{"name":"agenthq_spawn","arguments":{"role":"qa"}}""")["result"]!!.jsonObject
        val text = result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("false", result["isError"]!!.jsonPrimitive.content, text)
        val id = Json.parseToJsonElement(text).jsonObject["sessionId"]!!.jsonPrimitive.content
        assertEquals("alpha", fixture.daemon.manager.get(id)!!.projectId, "the spawn landed in the configured project")

        // An explicit projectId on the call still wins over the server default.
        val explicit = call(3, "tools/call", """{"name":"agenthq_spawn","arguments":{"role":"qa","projectId":"gamma"}}""")["result"]!!.jsonObject
        val explicitText = explicit["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("false", explicit["isError"]!!.jsonPrimitive.content, explicitText)
        val explicitId = Json.parseToJsonElement(explicitText).jsonObject["sessionId"]!!.jsonPrimitive.content
        assertEquals("gamma", fixture.daemon.manager.get(explicitId)!!.projectId)
    }
}
