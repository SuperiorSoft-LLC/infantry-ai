package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.daemon.AccessPolicy
import com.superiorsoftllc.infantry.daemon.DaemonFixture
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BUG-CLIENV-2: every `INFANTRY_*` read the CLI makes goes through the `getenv` handed to [Cli.run].
 *
 * The token (`INFANTRY_TOKEN` / `INFANTRY_TOKEN_FILE`), the endpoints file (`INFANTRY_ENDPOINTS`) and the
 * state directory (`INFANTRY_STATE_DIR`) used to be read from `System.getenv` directly, so [CliTestEnv]
 * could not strip them: an operator's real `INFANTRY_TOKEN` reached the fixture daemons. Each test here
 * hands the CLI a variable the test JVM does not carry, and asserts the CLI acted on it -- a CLI that
 * read the JVM environment instead never sees it. Tokens are dummies; no real credential file is read.
 */
class CliInjectedEnvTest {

    private val token = "dummy-clienv2-token-0123456789abcdef0123456789"
    private val fixtures = mutableListOf<DaemonFixture>()
    private val temps = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        fixtures.asReversed().forEach {
            runCatching { it.stop() }
            runCatching { it.deleteState() }
        }
        temps.forEach { it.deleteRecursively() }
    }

    private fun tempDir(): File = Files.createTempDirectory("infantry-clienv2").toFile().also { temps += it }

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = mapOf(*pairs)
        return { key -> map[key] ?: CliTestEnv.getenv(key) }
    }

    private fun pods(port: Int, getenv: (String) -> String?): Pair<Int, String> {
        val err = StringBuilder()
        val exit = Cli.run(arrayOf("pods", "--port", port.toString()), StringBuilder(), err, getenv)
        return exit to err.toString()
    }

    @Test
    fun theTokenComesFromTheInjectedEnvironment() {
        val fixture = DaemonFixture.launch(token = token).also { fixtures += it }
        val (exit, err) = pods(fixture.port, env(AccessPolicy.TOKEN_ENV to token))
        assertEquals(0, exit, "the injected INFANTRY_TOKEN must reach the daemon: $err")

        val (bare, bareErr) = pods(fixture.port, CliTestEnv.getenv)
        assertTrue(bare != 0, "no injected token: the daemon refuses, whatever the JVM carries")
        assertTrue("no token was sent" in bareErr, bareErr)
    }

    @Test
    fun theTokenFileComesFromTheInjectedEnvironment() {
        val fixture = DaemonFixture.launch(token = token).also { fixtures += it }
        val wrong = File(tempDir(), "token.txt").apply { writeText("dummy-wrong-token-0123456789abcdef0123456789abc") }
        val (exit, err) = pods(fixture.port, env(AccessPolicy.TOKEN_FILE_ENV to wrong.absolutePath))
        assertTrue(exit != 0, "a wrong token is refused")
        assertTrue(
            "the token from ${AccessPolicy.TOKEN_FILE_ENV} was not accepted" in err,
            "the refusal names the injected INFANTRY_TOKEN_FILE as the source: $err",
        )
    }

    @Test
    fun theEndpointsFileComesFromTheInjectedEnvironment() {
        val file = File(tempDir(), "endpoints.json").apply {
            writeText("""{"endpoints":[{"name":"fx","url":"ws://127.0.0.1:9999/ws"}]}""")
        }
        val url = runCatching {
            Cli.targetUrl(arrayOf("pods", "--endpoint", "fx"), env("INFANTRY_ENDPOINTS" to file.absolutePath))
        }
        assertEquals("ws://127.0.0.1:9999/ws", url.getOrNull(), "INFANTRY_ENDPOINTS must be read from getenv: $url")
    }

    @Test
    fun theStateDirectoryComesFromTheInjectedEnvironment() {
        val state = tempDir()
        File(state, "endpoints.json").writeText("""{"endpoints":[{"name":"fx","url":"ws://127.0.0.1:9998/ws"}]}""")
        val url = runCatching {
            Cli.targetUrl(arrayOf("pods", "--endpoint", "fx"), env("INFANTRY_STATE_DIR" to state.absolutePath))
        }
        assertEquals("ws://127.0.0.1:9998/ws", url.getOrNull(), "INFANTRY_STATE_DIR must be read from getenv: $url")
    }
}
