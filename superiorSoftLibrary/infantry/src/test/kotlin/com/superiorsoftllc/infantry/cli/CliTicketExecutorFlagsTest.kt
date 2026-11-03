package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.daemon.DaemonFixture
import com.superiorsoftllc.infantry.daemon.TestWait
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.Ticket
import com.superiorsoftllc.infantry.harness.AgentProvider
import com.superiorsoftllc.infantry.harness.RunRequest
import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.runner.StubScript
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BUG-E2E-1: `infantry ticket create` / `ticket update` carry `--role`, `--provider` and `--model`, the same
 * TICKET-EXEC-UI fields the UI ticket editor saves through `ticket_save`. Over a REAL daemon and the REAL [Cli.run]:
 * the ticket is read back from the daemon's board, never from what the CLI printed.
 *
 * The `claude` provider here is a fake that LISTS its models, so "a model the provider does not list" is a real
 * refusal path and not an accept-anything provider.
 */
class CliTicketExecutorFlagsTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val fixtures = mutableListOf<DaemonFixture>()

    private class ListingProvider(override val id: String, private val known: List<String>) : AgentProvider {
        override fun spec(request: RunRequest) = ProcessSpec(listOf("noop"))
        override fun defaultModel(): String? = null
        override fun knownModels() = known
    }

    private fun launch(): DaemonFixture = DaemonFixture.launch(
        StubScript.default(),
        null,
        Files.createTempDirectory("infantry-ticket-exec-cli-test").toFile(),
        additionalProviders = listOf(ListingProvider("claude", listOf("haiku", "sonnet", "opus"))),
    ).also { fixtures += it }

    @AfterTest
    fun stopAll() {
        fixtures.asReversed().forEach {
            runCatching { it.stop() }
            runCatching { it.deleteState() }
        }
        fixtures.clear()
    }

    private data class Result(val exit: Int, val out: String, val err: String)

    private fun cli(port: Int, vararg args: String): Result {
        val out = StringBuilder()
        val err = StringBuilder()
        val exit = Cli.run(arrayOf(*args, "--port", port.toString()), out, err, getenv = { null })
        return Result(exit, out.toString(), err.toString())
    }

    /** The board as the daemon stores it, asked for fresh; [tag] keeps one request's answer apart from another's. */
    private var listings = 0

    private fun board(fixture: DaemonFixture): List<Ticket> {
        fixture.send(Envelope(Envelope.TICKET_LIST, ""))
        val frame = fixture.client.await(Envelope.TICKETS, occurrence = listings++, timeoutMs = TestWait.defaultMs)
        return json.decodeFromString(ListSerializer(Ticket.serializer()), frame.data)
    }

    private fun stored(fixture: DaemonFixture, title: String): Ticket? = board(fixture).singleOrNull { it.title == title }

    @Test
    fun createStoresRoleProviderAndModel() {
        val fixture = launch()
        val created = cli(
            fixture.port, "ticket", "create", "--title", "exec pins", "--desc", "d",
            "--role", "backend", "--provider", "claude", "--model", "haiku",
        )
        assertEquals(0, created.exit, "ticket create with --role/--provider/--model must succeed; err=${created.err}")
        val ticket = stored(fixture, "exec pins") ?: error("no ticket 'exec pins' on the board")
        assertEquals("claude", ticket.provider, "provider read back")
        assertEquals("haiku", ticket.model, "model read back")
        assertEquals("role:backend", ticket.agentId, "role read back as the role:<id> agentId the editor stores")
    }

    @Test
    fun updateChangesOnlyTheGivenPins() {
        val fixture = launch()
        assertEquals(0, cli(fixture.port, "ticket", "create", "--title", "to edit").exit)
        val before = stored(fixture, "to edit") ?: error("no ticket 'to edit'")
        val updated = cli(fixture.port, "ticket", "update", before.id.takeLast(8), "--provider", "claude", "--model", "sonnet")
        assertEquals(0, updated.exit, "ticket update must succeed; err=${updated.err}")
        assertTrue(updated.out.startsWith("updated "), updated.out)
        val after = stored(fixture, "to edit") ?: error("ticket 'to edit' vanished")
        assertEquals("claude", after.provider)
        assertEquals("sonnet", after.model)
        assertEquals(before.agentId, after.agentId, "a flag left out keeps the stored value")

        assertEquals(0, cli(fixture.port, "ticket", "update", before.id, "--role", "qa").exit)
        val roled = stored(fixture, "to edit") ?: error("ticket 'to edit' vanished")
        assertEquals("role:qa", roled.agentId)
        assertEquals("sonnet", roled.model, "--role alone keeps the model")
    }

    @Test
    fun aModelTheProviderDoesNotListIsRefusedNotCoerced() {
        val fixture = launch()
        val refused = cli(fixture.port, "ticket", "create", "--title", "bad model", "--provider", "claude", "--model", "haikuu")
        assertNotEquals(0, refused.exit, "an unlisted model must fail; out=${refused.out}")
        assertTrue("unknown model 'haikuu'" in refused.err, "the refusal names the model: ${refused.err}")
        assertNull(stored(fixture, "bad model"), "a refused create stores nothing")
    }

    @Test
    fun anUnknownProviderIsRefused() {
        val fixture = launch()
        val refused = cli(fixture.port, "ticket", "create", "--title", "bad provider", "--provider", "nosuch", "--model", "haiku")
        assertNotEquals(0, refused.exit, "an unknown provider must fail; out=${refused.out}")
        assertTrue("nosuch" in refused.err, "the refusal names the provider: ${refused.err}")
        assertNull(stored(fixture, "bad provider"), "a refused create stores nothing")
    }

    @Test
    fun aMalformedRoleIsRefusedBeforeConnecting() {
        val result = cli(1, "ticket", "create", "--title", "t", "--role", "Not A Role")
        assertEquals(2, result.exit, "a usage error, not a connection failure: ${result.err}")
        assertTrue("--role" in result.err, result.err)
    }
}
