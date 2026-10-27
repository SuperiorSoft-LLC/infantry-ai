package com.superiorsoftllc.agenthq.commands

import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.runner.ScriptedRunner
import com.superiorsoftllc.agenthq.runner.StubScript
import com.superiorsoftllc.agenthq.runner.TerminalListener
import com.superiorsoftllc.agenthq.runner.TerminalProcess
import com.superiorsoftllc.agenthq.runner.TerminalRunner
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SECRETS-2 (ticket e5d1b57f, contract 1.135.0): a run gets its secrets' fields as environment variables, masked in its output. */
class RunManagerSecretsTest {
    private val repo: File = Files.createTempDirectory("agenthq-run-secrets").toFile()
    private val registry = CommandRegistry.parse(
        """[{"id":"sign","name":"Sign","argv":["x"],"secrets":["android-signing","deploy"]},{"id":"plain","argv":["y"]},""" +
            """{"id":"unknown","argv":["z"],"secrets":["missing"]},{"id":"sneaky","argv":["z"],"secrets":["sneaky"]},""" +
            """{"id":"builtin","argv":["z"],"secrets":["update-token"]},{"id":"one","argv":["z"],"secrets":["android-signing"]}]""",
        repo,
    )
    private val frames = CopyOnWriteArrayList<Envelope>()
    private val password = "store-pass-0123456789"
    private val store = mapOf(
        "android-signing" to mapOf("storePassword" to password, "keyAlias" to "release"),
        "deploy" to mapOf("DEPLOY_TOKEN" to "tok-abcdefghijklmnop"),
        "sneaky" to mapOf("AGENTHQ_TOKEN" to "nope-nope-nope"),
        "update-token" to mapOf("value" to "ghp_UpdateTokenValue0123456789"),
    )

    private class FakeTerminal : TerminalRunner {
        lateinit var listener: TerminalListener
        var spec: ProcessSpec? = null
        override fun start(spec: ProcessSpec, cols: Int, rows: Int, listener: TerminalListener): TerminalProcess {
            this.spec = spec
            this.listener = listener
            return object : TerminalProcess {
                override val isAlive = true
                override val pid: Long = 7
                override fun write(bytes: ByteArray) {}
                override fun resize(cols: Int, rows: Int) {}
                override fun terminate() { listener.onExit(130) }
                override fun killTree() {}
            }
        }
        fun out(text: String) = listener.onBytes(text.toByteArray())
    }

    private fun manager(t: TerminalRunner, demo: Boolean = false) = RunManager(
        { registry }, ScriptedRunner(StubScript(stdoutLines = listOf("x"))), { frames += it },
        terminal = t, demoLock = { demo }, secrets = com.superiorsoftllc.agenthq.keystore.KeystoreHandler.runSecrets({ store[it] }),
    )

    private fun everyFrame() = frames.joinToString("\n") { f ->
        if (f.type == Envelope.RUN_BYTES) String(Base64.getDecoder().decode(Json.decodeFromString(RunBytes.serializer(), f.data).data)) else f.data
    }

    @Test fun `the command's secret fields reach the child's environment, and only there`() {
        val t = FakeTerminal()
        val m = manager(t)
        val s = m.start("""{"commandId":"one"}""")
        assertEquals(Envelope.RUN, s.reply.type, s.reply.data)
        assertFalse(s.reply.data.contains(password))
        s.launch()
        assertEquals(mapOf("storePassword" to password, "keyAlias" to "release"), t.spec!!.env)
    }

    @Test fun `a command's several secrets all reach the run, and a printed value is masked in bytes, lines and replay`() {
        val t = FakeTerminal()
        val m = manager(t)
        val s = m.start("""{"commandId":"sign"}""")
        s.launch()
        assertEquals("tok-abcdefghijklmnop", t.spec!!.env["DEPLOY_TOKEN"])
        t.out("using store-pass-01"); t.out("23456789 and tok-abcdefghijklmnop\n")
        t.listener.onExit(0)
        val runId = Json.decodeFromString(RunRecord.serializer(), s.reply.data).runId
        val replay = m.attach("""{"runId":"$runId"}""")
        val all = everyFrame() + String(Base64.getDecoder().decode(Json.decodeFromString(RunBytes.serializer(), replay.data).data))
        assertFalse(all.contains(password), all)
        assertFalse(all.contains("tok-abcdefghijklmnop"))
        assertTrue(all.contains("using ${SecretScrubber.MASK} and ${SecretScrubber.MASK}"), all)
    }

    @Test fun `an unknown secret, a daemon setting field, or the demo lock refuses the run without a value`() {
        val m = manager(FakeTerminal())
        val unknown = m.start("""{"commandId":"unknown"}""").reply
        assertEquals(Envelope.ERROR, unknown.type)
        assertTrue(unknown.data.contains("missing"))
        val sneaky = m.start("""{"commandId":"sneaky"}""").reply
        assertEquals(Envelope.ERROR, sneaky.type)
        assertFalse(sneaky.data.contains("nope-nope-nope"))

        val locked = manager(FakeTerminal(), demo = true).start("""{"commandId":"sign"}""").reply
        assertEquals(Envelope.ERROR, locked.type)
        assertTrue(locked.data.contains("demo lock"))
        // A run with no secret still starts under the demo lock.
        assertEquals(Envelope.RUN, manager(FakeTerminal(), demo = true).start("""{"commandId":"plain"}""").reply.type)
    }

    @Test fun `SEC-FIX-1 H1a a run_start naming secrets is refused, never merged with the command's`() {
        val t = FakeTerminal()
        val m = manager(t)
        for (data in listOf("""{"commandId":"plain","secrets":["deploy"]}""", """{"commandId":"one","secrets":[]}""", """{"commandId":"plain","secrets":"deploy"}""")) {
            val reply = m.start(data).reply
            assertEquals(Envelope.ERROR, reply.type, data)
            assertTrue(reply.data.contains("only its saved command's secrets"), reply.data)
        }
        assertNull(t.spec, "a refused run_start started a child")
    }

    @Test fun `SEC-FIX-1 H1b a built-in secret (the update token) never reaches a run, even when a command names it`() {
        val t = FakeTerminal()
        val reply = manager(t).start("""{"commandId":"builtin"}""").reply
        assertEquals(Envelope.ERROR, reply.type)
        assertTrue(reply.data.contains("built in"), reply.data)
        assertFalse(reply.data.contains("ghp_UpdateTokenValue"), reply.data)
        assertNull(t.spec)
    }

    @Test fun `piped runs mask the value in their lines`() {
        val m = RunManager(
            { registry }, ScriptedRunner(StubScript(stdoutLines = listOf("pw=$password"))), { frames += it },
            secrets = { store[it] },
        )
        val s = m.start("""{"commandId":"sign"}""")
        s.launch()
        val deadline = System.currentTimeMillis() + 5_000
        while (frames.none { it.type == Envelope.RUN_FINISHED } && System.currentTimeMillis() < deadline) Thread.sleep(10)
        val line = frames.first { it.type == Envelope.RUN_OUTPUT }
        assertFalse(line.data.contains(password))
        assertTrue(line.data.contains("pw=${SecretScrubber.MASK}"), line.data)
    }

    @Test fun `a command's secrets are saved and listed by name, and a bad name is refused`() {
        assertEquals(listOf("android-signing"), registry.find("one")!!.toSpec().secrets)
        assertTrue(registry.specsJson().contains(""""secrets":["android-signing"]"""))
        val bad = CommandRegistry.parse("""[{"id":"a","argv":["x"],"secrets":["../x"]}]""", repo)
        assertTrue(bad.refusal!!.contains("invalid secret"))
    }
}
