package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.harness.ServiceLoaderPluginSource
import com.superiorsoftllc.agenthq.protocol.Envelope
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SMOKE-1: opt-in proof that the REAL `claude` / `pi` CLIs work through the registry path (WIRE-4/WIRE-5).
 *
 * Skipped unless `AGENTHQ_REAL_CLI=1` AND the binary is on PATH. Real in-process daemon, real WebSocket, the
 * default harness registry (ServiceLoader, no AGENTHQ_PROVIDERS, no descriptors), a real child process.
 * Every failure message names the stage that failed and dumps the frames seen so far.
 */
class RealCliSmokeTest {
    private val timeoutMs = 90_000L
    private val word = "PONG"

    private fun onPath(bin: String): Boolean {
        val exts = if (System.getProperty("os.name").lowercase().contains("win")) listOf("", ".exe", ".cmd", ".bat") else listOf("")
        return System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }
            .any { dir -> exts.any { File(dir, bin + it).isFile } }
    }

    private fun assumeReal(bin: String) {
        assumeTrue("set AGENTHQ_REAL_CLI=1 to run the real-CLI smoke", System.getenv("AGENTHQ_REAL_CLI") == "1")
        assumeTrue("`$bin` is not on PATH", onPath(bin))
    }

    private fun smoke(provider: String) {
        val fixture = DaemonFixture.launch(harnesses = loadHarnesses(ServiceLoaderPluginSource(), descriptorDir = null))
        fun dump() = fixture.client.frames.joinToString("\n") { "  [${it.type}] ${it.data.take(200)}" }
        fun stage(name: String, block: () -> Unit) = try { block() } catch (e: Throwable) {
            throw AssertionError("SMOKE-1 [$provider] stage '$name' failed: ${e.message}\nframes:\n${dump()}", e)
        }
        fun textSince(from: Int) = fixture.client.frames.drop(from)
            .filter { it.type == Envelope.TEXT_DELTA }.joinToString("") { it.data }

        try {
            stage("ready") { fixture.client.await(Envelope.READY) }
            // NEW is lazy: the session exists once the first PROMPT arrives (same order HarnessSpawnWiringTest uses).
            val mark1 = fixture.client.frames.size
            stage("new+prompt-1") {
                fixture.send(Envelope(Envelope.NEW, """{"provider":"$provider"}"""))
                fixture.send(Envelope(Envelope.PROMPT, "reply with the single word $word"))
                fixture.await(what = "a session on $provider") { fixture.daemon.manager.list().singleOrNull()?.provider == provider }
            }
            stage("finished-1") { fixture.client.await(Envelope.FINISHED, 0, timeoutMs) }
            stage("reply-1") {
                assertTrue(textSince(mark1).contains(word, ignoreCase = true), "no '$word' in reply: '${textSince(mark1).take(300)}'")
            }
            stage("no-error-1") { assertTrue(fixture.client.of(Envelope.ERROR).isEmpty(), "ERROR frames: ${fixture.client.of(Envelope.ERROR)}") }
            val psid = fixture.daemon.manager.list().single().providerSessionId
            stage("providerSessionId") { assertNotNull(psid, "providerSessionId was not captured") }

            // Turn 2 -- must resume the same provider session (it has to remember turn 1).
            val mark2 = fixture.client.frames.size
            stage("prompt-2") { fixture.send(Envelope(Envelope.PROMPT, "what single word did I ask you to reply with earlier? answer with only that word")) }
            stage("finished-2") { fixture.client.await(Envelope.FINISHED, 1, timeoutMs) }
            stage("resume-reply") {
                assertTrue(textSince(mark2).contains(word, ignoreCase = true), "turn 2 did not recall '$word' (not resumed?): '${textSince(mark2).take(300)}'")
            }
            stage("no-error-2") { assertTrue(fixture.client.of(Envelope.ERROR).isEmpty(), "ERROR frames: ${fixture.client.of(Envelope.ERROR)}") }
            stage("same-session") { assertEquals(psid, fixture.daemon.manager.list().single().providerSessionId, "providerSessionId changed: not a resume") }
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    @Test
    fun realClaudeAnswersAndResumes() { assumeReal("claude"); smoke("claude") }

    @Test
    fun realPiAnswersAndResumes() { assumeReal("pi"); smoke("pi") }
}
