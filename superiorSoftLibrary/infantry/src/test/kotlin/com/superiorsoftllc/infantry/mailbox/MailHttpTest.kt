package com.superiorsoftllc.infantry.mailbox

import com.superiorsoftllc.infantry.agents.AgentProfiles
import com.superiorsoftllc.infantry.daemon.mailRoutes
import com.superiorsoftllc.infantry.persistence.AgentRepository
import com.superiorsoftllc.infantry.protocol.AgentProfile
import com.superiorsoftllc.infantry.session.SessionManager
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The agent-facing half of Phase 6: a spawned CLI agent answers its mail over the daemon's HTTP API.
 *
 * This closes the gap the WS-only mailbox left open. A delivered message is a *prompt* handed to a
 * child process; the child cannot speak WebSockets, so the delivered prompt carries `curl` commands
 * against this API. The test drives the WHOLE negotiation over real HTTP (embedded CIO server, real
 * JDK HttpClient) -- no in-process shortcut -- so it proves what an agent can actually do.
 */
class MailHttpTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** In-memory agent repository for testing. */
    private class FakeAgentRepository : AgentRepository {
        private val profiles = mutableMapOf<String?, AgentProfile>()
        override fun save(profile: AgentProfile, at: Long) {
            profiles[profile.id] = profile
        }
        override fun get(id: String): AgentProfile? = profiles[id]
        override fun all(): List<AgentProfile> = profiles.values.toList()
        override fun delete(id: String): Boolean = profiles.remove(id) != null
    }

    private class Harness(val manager: SessionManager, val mailbox: Mailbox, val router: MailRouter, val runner: ScriptedRunner)

    private fun harness(): Harness {
        val runner = ScriptedRunner(autoExit = true)
        val manager = SessionManager(FakeAgentProvider(), runner, tempSessionStore(), killGraceMs = 50)
        val mailbox = Mailbox(MemMailStore(), MailLimits())
        val agentProfiles = AgentProfiles(FakeAgentRepository(), manager)
        // A blank base URL default is fine here; the delivered prompt's curl text is not what this
        // test exercises -- it calls the endpoints directly, as the agent would.
        val router = MailRouter(manager, mailbox, agentProfiles)
        manager.eventSink = { event -> router.onSessionEvent(event) }
        return Harness(manager, mailbox, router, runner)
    }

    private fun withServer(router: MailRouter, mailbox: Mailbox, block: (String) -> Unit) {
        val server: EmbeddedServer<*, *> = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(ContentNegotiation) { json() }
            routing { mailRoutes(router, mailbox) }
        }.start(wait = false)
        try {
            val port = runBlocking {
                repeat(100) {
                    server.engine.resolvedConnectors().firstOrNull()?.port?.let { return@runBlocking it }
                    delay(20)
                }
                error("mail HTTP server did not bind")
            }
            block("http://127.0.0.1:$port")
        } finally {
            server.stop(100, 500)
        }
    }

    private val http: HttpClient = HttpClient.newHttpClient()

    private fun post(url: String, body: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI(url)).header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun get(url: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI(url)).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun message(body: String): Message = json.decodeFromString(Message.serializer(), body)

    @Test
    fun an_agent_negotiates_a_handoff_entirely_over_http() {
        val h = harness()
        val frontend = h.manager.startNew("frontend")
        val backend = h.manager.startNew("backend")
        val threadId = Threads.pair(frontend.id, backend.id)

        withServer(h.router, h.mailbox) { base ->
            // 1. Frontend sends the handoff (it is blocked waiting on the answer).
            val handoffRequest = json.encodeToString(
                MailSendRequest.serializer(),
                MailSendRequest(
                    from = frontend.id,
                    to = backend.id,
                    subject = "Handoff: token rotation",
                    body = "Implement POST /token/rotate, then reply.",
                    checkpoint = Checkpoint(state = "UI done", nextAction = "implement endpoint"),
                    needsInput = true,
                ),
            )
            val handoffResponse = post("$base/mail/send", handoffRequest)
            assertEquals(200, handoffResponse.statusCode(), "send should succeed: ${handoffResponse.body()}")
            val handoff = message(handoffResponse.body())
            // Delivery happened because the recipient was at rest -- the message reached B's console.
            assertEquals(MessageState.DELIVERED, handoff.state)
            assertTrue(
                h.runner.processes.any { it.input.contains("Implement POST /token/rotate") },
                "the handoff must reach the backend console",
            )

            // 2. Backend acknowledges it, over HTTP, as an agent would.
            val readResponse = post(
                "$base/mail/read",
                json.encodeToString(MailReadRequest.serializer(), MailReadRequest(handoff.id, backend.id)),
            )
            assertEquals(200, readResponse.statusCode())
            assertEquals(MessageState.READ, message(readResponse.body()).state)

            // 3. Backend answers in the same thread.
            val answerRequest = json.encodeToString(
                MailSendRequest.serializer(),
                MailSendRequest(
                    from = backend.id,
                    to = frontend.id,
                    body = "Done: POST /token/rotate shipped.",
                    replyTo = handoff.id,
                ),
            )
            val answerResponse = post("$base/mail/send", answerRequest)
            assertEquals(200, answerResponse.statusCode(), "reply should succeed: ${answerResponse.body()}")
            val answer = message(answerResponse.body())
            assertEquals(frontend.id, answer.to, "a reply goes back to the original sender")
            assertEquals(threadId, answer.threadId, "a reply stays in the same thread")
            assertEquals(MessageState.DELIVERED, answer.state)

            // 4. Read the thread back over HTTP: ordered, both delivered, question answered.
            val threadResponse = get("$base/mail/thread/${URLEncoder.encode(threadId, "UTF-8")}")
            assertEquals(200, threadResponse.statusCode())
            val thread = json.decodeFromString(ListSerializer(Message.serializer()), threadResponse.body())
            assertEquals(listOf(1L, 2L), thread.map { it.seq })
            assertEquals(MessageState.ANSWERED, thread.first().state)
            assertEquals(MessageState.DELIVERED, thread.last().state)
            assertTrue(thread.all { it.delivered })

            // 5. The frontend is no longer blocked.
            val waitingResponse = get("$base/mail/waiting")
            assertEquals(200, waitingResponse.statusCode())
            val waiting = json.decodeFromString(ListSerializer(WaitingOn.serializer()), waitingResponse.body())
            assertTrue(waiting.isEmpty(), "answering over HTTP clears the waiting signal")
        }
    }

    @Test
    fun a_refused_send_is_an_http_error_not_a_silent_drop() {
        val h = harness()
        withServer(h.router, h.mailbox) { base ->
            val bad = json.encodeToString(
                MailSendRequest.serializer(),
                MailSendRequest(from = "A", to = "A", body = "to myself"),
            )
            val response = post("$base/mail/send", bad)
            assertEquals(400, response.statusCode())
            val refused = json.decodeFromString(MailRefused.serializer(), response.body())
            assertTrue(refused.reason.isNotBlank())
        }
    }
}
