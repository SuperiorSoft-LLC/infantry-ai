package com.superiorsoftllc.agenthq.plugins.official.run.daemon

import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.runner.ScriptedRunner
import com.superiorsoftllc.agenthq.runner.StubScript
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RUN-CMD-1: the store against a real [RunManager], so the in-flight guard is the production one. */
class CommandStoreTest {
    private val home: File = Files.createTempDirectory("agenthq-cmdstore-test").toFile()
    private val repo: File = File(home, "repo").also { it.mkdirs() }
    private val file = File(home, "commands.json")
    private val frames = CopyOnWriteArrayList<Envelope>()

    @AfterTest fun cleanUp() { home.deleteRecursively() }

    private fun entry(id: String, argv: String = """["x"]""") = """{"id":"$id","argv":$argv,"graceMillis":100}"""

    @Test fun `a save or delete of the command in flight is refused, another command is not`() {
        val store = CommandStore(file, repo)
        assertNull(store.save(entry("build")))
        assertNull(store.save(entry("test")))
        // A run that does not end on its own: the stub sleeps a minute before exiting.
        val runs = RunManager({ store.registry }, ScriptedRunner(StubScript(stdoutLines = listOf("a")), 60_000), { frames += it })
        val started = runs.start("""{"commandId":"build"}""")
        assertEquals(Envelope.RUN, started.reply.type, started.reply.data)
        started.launch()
        val before = file.readText()

        val saveRefusal = store.save(entry("build", """["y"]"""), runs::unlessRunning)
        assertNotNull(saveRefusal); assertTrue(saveRefusal.contains("running"), saveRefusal)
        val deleteRefusal = store.delete("""{"id":"build"}""", runs::unlessRunning)
        assertNotNull(deleteRefusal); assertTrue(deleteRefusal.contains("running"), deleteRefusal)
        assertEquals(before, file.readText(), "nothing written while it runs")

        assertNull(store.save(entry("test", """["z"]"""), runs::unlessRunning), "another command stays editable")
        assertNull(store.delete("""{"id":"test"}""", runs::unlessRunning))
        assertEquals(listOf("build"), store.registry.commands.map { it.id })

        val runId = Regex("\"runId\":\"([^\"]+)\"").find(started.reply.data)!!.groupValues[1]
        assertNull(runs.stop("$runId force"))
        val end = System.nanoTime() + 20_000_000_000L
        while (frames.none { it.type == Envelope.RUN_FINISHED } && System.nanoTime() < end) Thread.sleep(10)
        assertNull(store.delete("""{"id":"build"}""", runs::unlessRunning), "editable again once the run finished")
        assertTrue(store.registry.commands.isEmpty())
    }

    @Test fun `the next run_start reads the saved argv, not the one loaded at start`() {
        val store = CommandStore(file, repo)
        val runs = RunManager({ store.registry }, ScriptedRunner(StubScript(), 0), { frames += it })
        assertEquals(Envelope.ERROR, runs.start("""{"commandId":"build"}""").reply.type, "unknown before the save")
        assertNull(store.save(entry("build")))
        assertEquals(Envelope.RUN, runs.start("""{"commandId":"build"}""").reply.type)
    }
}
