package com.superiorsoftllc.agenthq.client

import com.superiorsoftllc.agenthq.session.InfantryEnv

import com.superiorsoftllc.agenthq.protocol.AgentProfile
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.FinishedPayload
import com.superiorsoftllc.agenthq.protocol.ProviderInfo
import com.superiorsoftllc.agenthq.protocol.ProviderModels
import com.superiorsoftllc.agenthq.protocol.SpawnOptions
import com.superiorsoftllc.agenthq.protocol.StatusPayload
import com.superiorsoftllc.agenthq.protocol.Ticket
import com.superiorsoftllc.agenthq.protocol.TicketLinkRequest
import com.superiorsoftllc.agenthq.protocol.TicketLinkType
import com.superiorsoftllc.agenthq.protocol.TicketMove
import com.superiorsoftllc.agenthq.protocol.TicketReorder
import com.superiorsoftllc.agenthq.protocol.TicketSave
import com.superiorsoftllc.agenthq.protocol.TicketStatus
import com.superiorsoftllc.agenthq.mailbox.MailRefused
import com.superiorsoftllc.agenthq.mailbox.MailSendRequest
import com.superiorsoftllc.agenthq.mailbox.Message
import com.superiorsoftllc.agenthq.mailbox.ThreadSummary
import com.superiorsoftllc.agenthq.mailbox.WaitingOn
import com.superiorsoftllc.agenthq.session.SessionRecord
import com.superiorsoftllc.agenthq.worktree.CleanupResult
import com.superiorsoftllc.agenthq.worktree.WorktreeStatus
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch

private val json = Json { ignoreUnknownKeys = true }

/**
 * The AgentHQ CLI client: connect to the daemon, read stdin, send each line, print what comes back.
 *
 * Implemented on the JDK's own `java.net.http.WebSocket` so the module needs no client dependency.
 *
 * Interactive commands (Phase 3):
 *  - a plain line        -> a `prompt` frame (continues this connection's session, or starts one)
 *  - `/sessions`         -> list known sessions (`sessions` frame)
 *  - `/worktrees`        -> list session worktrees and their status (`worktrees` frame)
 *  - `/cleanup <id> [f]` -> remove a session's worktree (refuses a dirty one unless `force`)
 *  - `/kill <id> [force]`-> terminate a session (graceful, or `force` immediately)
 *  - `/interrupt <id>`   -> stop a BUSY session's current turn; the session stays alive and returns to rest
 *  - `/resume <id> <p>`  -> resume a finished/dead session with the next turn `p`
 *  - `/new`              -> the next prompt starts a fresh session instead of continuing
 *  - `/new <provider> [model]` -> the same, on that provider and model (contract 1.3.0); the choice
 *                           holds for every session this connection starts until the next `/new`
 *  - `/models`           -> every provider's models (`models` frame); `/model <id>` switches the next sessions to that model
 *  - `/providers`        -> list the providers this daemon can spawn (`providers` frame)
 *  - `/new @<agentId>`   -> the next sessions spawn from that agent profile (contract 1.4.0)
 *  - `/agents`           -> list the agent profiles (`agent_list`)
 *  - `/agent-save <json>`-> create or overwrite a profile, e.g. `{"name":"Backend","role":"backend"}`
 *  - `/agent-delete <id>`-> delete a profile
 *  - `/tickets`          -> the backlog, column by column (`ticket_list`, contract 1.5.0)
 *  - `/ticket <id>`      -> one ticket (`ticket_get`)
 *  - `/ticket-new <title>` -> create a ticket at the bottom of todo
 *  - `/ticket-save <json>` -> create or edit a ticket, e.g. `{"id":"ticket-1a2b3c4d","ticketType":"defect"}`
 *  - `/ticket-move <id> [status] [position] [wontfix]` -> e.g. `/ticket-move <id> done wontfix`
 *  - `/ticket-link <id> <type> <otherId>` / `/ticket-unlink ...` -> e.g. `/ticket-link <a> blocked_by <b>`
 *  - `/ticket-reorder [status] <id> <id> ...` -> those tickets go on top of their column, in that order
 *  - `/ticket-delete <id>` -> delete a ticket and its links
 *  - `exit` / `quit`     -> close the socket
 *
 * One-shot mode (args present) still sends the args as ONE prompt and exits on the daemon `exit`.
 */
fun main(args: Array<String>) {
    val url = InfantryEnv.get("AGENTHQ_WS_URL") ?: "ws://127.0.0.1:8787/ws"
    val oneShotPrompt = args.joinToString(" ").trim()
    val closed = CountDownLatch(1)

    // A daemon with a bearer token refuses an unauthenticated handshake (HTTP 401): present the token the
    // CLI reads (AGENTHQ_TOKEN_FILE, else AGENTHQ_TOKEN). With neither set, nothing is sent.
    val builder = HttpClient.newHttpClient().newWebSocketBuilder()
    com.superiorsoftllc.agenthq.daemon.RemoteAccess.clientToken()
        ?.let { builder.header("Authorization", "Bearer $it") }
    val socket = builder
        .buildAsync(URI.create(url), ClientListener(closed, exitOnDone = oneShotPrompt.isNotEmpty()))
        .join()

    if (oneShotPrompt.isNotEmpty()) {
        println("[agentHq-client] one-shot -> $url")
        socket.send(Envelope(Envelope.PROMPT, oneShotPrompt))
    } else {
        println("[agentHq-client] connected to $url")
        println("[agentHq-client] prompt, or /sessions /kill <id> [force] /resume <id> <prompt> /new; 'exit' to quit")
        println("[agentHq-client] providers: /providers | /new <provider> [model] (the next sessions run there)")
        println("[agentHq-client] agents: /agents | /new @<agentId> | /agent-save <json> | /agent-delete <id>")
        println("[agentHq-client] backlog: /tickets | /ticket <id> | /ticket-new <title> | /ticket-save <json> | /ticket-delete <id>")
        println("[agentHq-client]          /ticket-move <id> [status] [position] [wontfix] | /ticket-reorder [status] <id>...")
        println("[agentHq-client]          /ticket-link <id> <type> <otherId> | /ticket-unlink <id> <type> <otherId>")
        println("[agentHq-client] mail: /mail <to> <subject> :: <body> | /need <to> <subject> :: <body> (asks for a reply)")
        println("[agentHq-client]       /inbox /outbox /threads /thread <id> /read <msgId> /waiting")
        Thread({
            try {
                BufferedReader(InputStreamReader(System.`in`)).forEachLine { line ->
                    val trimmed = line.trim()
                    when {
                        trimmed.isEmpty() -> Unit
                        trimmed == "exit" || trimmed == "quit" -> {
                            runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join() }
                            return@forEachLine
                        }
                        trimmed == "/new" -> {
                            socket.send(Envelope(Envelope.NEW))
                            println("[agentHq-client] next prompt starts a fresh session")
                        }
                        trimmed.startsWith("/new @") -> {
                            val agentId = trimmed.removePrefix("/new @").trim()
                            val options = SpawnOptions(agentId = agentId)
                            socket.send(Envelope(Envelope.NEW, json.encodeToString(SpawnOptions.serializer(), options)))
                            println("[agentHq-client] next prompt starts a fresh session from agent profile $agentId")
                        }
                        trimmed.startsWith("/new ") -> {
                            val parts = trimmed.removePrefix("/new").trim().split(Regex("\\s+"))
                            val options = SpawnOptions(provider = parts[0], model = parts.getOrNull(1))
                            socket.send(Envelope(Envelope.NEW, json.encodeToString(SpawnOptions.serializer(), options)))
                            println(
                                "[agentHq-client] next prompt starts a fresh session on ${options.provider}" +
                                    (options.model?.let { " model $it" } ?: " (its default model)"),
                            )
                        }
                        trimmed == "/models" || trimmed == "/model" -> socket.send(Envelope(Envelope.MODELS))
                        trimmed.startsWith("/model ") -> {
                            val id = trimmed.removePrefix("/model").trim()
                            when (val pick = resolveModelPick(id, lastModels)) {
                                is ModelPick.Switch -> {
                                    socket.send(Envelope(Envelope.NEW, json.encodeToString(SpawnOptions.serializer(), pick.options)))
                                    println("[agentHq-client] next prompt starts a fresh session on ${pick.options.provider} model ${pick.options.model}")
                                }
                                is ModelPick.Refused -> {
                                    println("[agentHq-client] ${pick.reason}")
                                    if (lastModels.isEmpty()) socket.send(Envelope(Envelope.MODELS))
                                }
                            }
                        }
                        trimmed == "/providers" -> socket.send(Envelope(Envelope.PROVIDERS))
                        trimmed == "/agents" -> socket.send(Envelope(Envelope.AGENT_LIST))
                        trimmed.startsWith("/agent-save") -> {
                            val body = trimmed.removePrefix("/agent-save").trim()
                            if (body.isEmpty()) println("[agentHq-client] usage: /agent-save {\"name\":\"Backend\",\"role\":\"backend\"}")
                            else socket.send(Envelope(Envelope.AGENT_SAVE, body))
                        }
                        trimmed.startsWith("/agent-delete") -> {
                            val id = trimmed.removePrefix("/agent-delete").trim()
                            if (id.isEmpty()) println("[agentHq-client] usage: /agent-delete <agentId>")
                            else socket.send(Envelope(Envelope.AGENT_DELETE, id))
                        }
                        trimmed == "/tickets" -> socket.send(Envelope(Envelope.TICKET_LIST))
                        trimmed.startsWith("/ticket-") -> ticketCommand(socket, trimmed)
                        trimmed.startsWith("/ticket ") ->
                            socket.send(Envelope(Envelope.TICKET_GET, trimmed.removePrefix("/ticket").trim()))
                        trimmed == "/sessions" -> socket.send(Envelope(Envelope.SESSIONS))
                        trimmed == "/worktrees" -> socket.send(Envelope(Envelope.WORKTREES))
                        trimmed.startsWith("/cleanup") -> {
                            val rest = trimmed.removePrefix("/cleanup").trim()
                            if (rest.isEmpty()) println("[agentHq-client] usage: /cleanup <sessionId> [force]")
                            else socket.send(Envelope(Envelope.WORKTREE_REMOVE, rest))
                        }
                        trimmed.startsWith("/interrupt") -> {
                            val id = trimmed.removePrefix("/interrupt").trim()
                            if (id.isEmpty()) println("[agentHq-client] usage: /interrupt <sessionId>")
                            else socket.send(Envelope(Envelope.INTERRUPT, id))
                        }
                        trimmed.startsWith("/kill") -> {
                            val rest = trimmed.removePrefix("/kill").trim()
                            if (rest.isEmpty()) println("[agentHq-client] usage: /kill <sessionId> [force]")
                            else socket.send(Envelope(Envelope.KILL, rest))
                        }
                        trimmed.startsWith("/session-delete") -> {
                            val id = trimmed.removePrefix("/session-delete").trim()
                            if (id.isEmpty()) println("[agentHq-client] usage: /session-delete <sessionId>")
                            else socket.send(Envelope(Envelope.SESSION_DELETE, id))
                        }
                        trimmed.startsWith("/resume") -> {
                            val rest = trimmed.removePrefix("/resume").trim()
                            val split = rest.indexOf(' ')
                            if (split < 0) {
                                println("[agentHq-client] usage: /resume <sessionId> <prompt>")
                            } else {
                                socket.send(
                                    Envelope(
                                        Envelope.RESUME,
                                        rest.substring(0, split).trim() + "\n" + rest.substring(split + 1),
                                    ),
                                )
                            }
                        }
                        trimmed == "/threads" -> socket.send(Envelope(Envelope.MAIL_THREADS))
                        trimmed == "/waiting" -> socket.send(Envelope(Envelope.MAIL_WAITING))
                        trimmed == "/inbox" -> socket.send(Envelope(Envelope.MAIL_INBOX))
                        trimmed == "/outbox" -> socket.send(Envelope(Envelope.MAIL_OUTBOX))
                        trimmed.startsWith("/thread ") ->
                            socket.send(Envelope(Envelope.MAIL_THREAD, trimmed.removePrefix("/thread").trim()))
                        trimmed.startsWith("/read ") ->
                            socket.send(Envelope(Envelope.MAIL_READ, trimmed.removePrefix("/read").trim()))
                        trimmed == "/mail" || trimmed.startsWith("/mail ") ->
                            sendMail(socket, trimmed.removePrefix("/mail").trim(), needsInput = false)
                        trimmed == "/need" || trimmed.startsWith("/need ") ->
                            sendMail(socket, trimmed.removePrefix("/need").trim(), needsInput = true)
                        else -> socket.send(Envelope(Envelope.PROMPT, line))
                    }
                }
            } finally {
                runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "eof") }
            }
        }, "agenthq-client-stdin").apply { isDaemon = true }.start()
    }

    closed.await()
    runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join() }
    println("[agentHq-client] closed")
}

private fun WebSocket.send(envelope: Envelope) {
    runCatching { sendText(json.encodeToString(Envelope.serializer(), envelope), true).join() }
}

/**
 * The `/ticket-...` commands (contract 1.5.0), each turned into one backlog frame. The daemon validates
 * everything; this only splits the words, so a bad status or link type comes back as the daemon's `error`.
 */
private fun ticketCommand(socket: WebSocket, line: String) {
    val command = line.substringBefore(' ')
    val rest = line.substringAfter(' ', "").trim()
    val words = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
    fun usage(text: String) = println("[agentHq-client] usage: $text")
    when (command) {
        "/ticket-new" ->
            if (rest.isEmpty()) usage("/ticket-new <title>")
            else socket.send(Envelope(Envelope.TICKET_SAVE, json.encodeToString(TicketSave.serializer(), TicketSave(title = rest))))
        "/ticket-save" ->
            if (rest.isEmpty()) usage("/ticket-save {\"title\":\"Add login\",\"ticketType\":\"feature\"}")
            else socket.send(Envelope(Envelope.TICKET_SAVE, rest))
        "/ticket-move" -> {
            if (words.isEmpty()) return usage("/ticket-move <id> [status] [position] [wontfix]")
            val position = words.drop(1).firstNotNullOfOrNull { it.toIntOrNull() }
            val wontFix = words.drop(1).any { it.equals("wontfix", ignoreCase = true) }.takeIf { it }
            val status = words.drop(1).firstOrNull { it.toIntOrNull() == null && !it.equals("wontfix", ignoreCase = true) }
            val move = TicketMove(id = words[0], status = status, position = position, wontFix = wontFix)
            socket.send(Envelope(Envelope.TICKET_MOVE, json.encodeToString(TicketMove.serializer(), move)))
        }
        "/ticket-link", "/ticket-unlink" -> {
            if (words.size != 3) return usage("$command <id> <type> <otherId>  (type: ${TicketLinkType.ALL.joinToString(" ")})")
            val request = TicketLinkRequest(ticketId = words[0], type = words[1], relatedId = words[2])
            val type = if (command == "/ticket-link") Envelope.TICKET_LINK else Envelope.TICKET_UNLINK
            socket.send(Envelope(type, json.encodeToString(TicketLinkRequest.serializer(), request)))
        }
        "/ticket-reorder" -> {
            val status = words.firstOrNull()?.takeIf { it.lowercase() in TicketStatus.ALL }
            val ids = if (status != null) words.drop(1) else words
            if (ids.isEmpty()) return usage("/ticket-reorder [status] <id> <id> ...")
            socket.send(Envelope(Envelope.TICKET_REORDER, json.encodeToString(TicketReorder.serializer(), TicketReorder(status, ids))))
        }
        "/ticket-delete" ->
            if (rest.isEmpty()) usage("/ticket-delete <id>")
            else socket.send(Envelope(Envelope.TICKET_DELETE, rest))
        else -> println("[agentHq-client] unknown command $command (try /tickets)")
    }
}

/**
 * Parse `/mail <to> <subject> :: <body>` (and its `/need` variant) into a `mail_send` frame.
 *
 * `from` is left blank so the daemon fills in this connection's active session -- the CLI client
 * does not track session ids, the daemon does. The `::` separator is mandatory: it keeps the
 * subject (spaces allowed) unambiguous from the body.
 */
private fun sendMail(socket: WebSocket, rest: String, needsInput: Boolean) {
    val sep = rest.indexOf("::")
    if (sep < 0) {
        println("[agentHq-client] usage: /mail <to> <subject> :: <body>")
        return
    }
    val head = rest.substring(0, sep).trim()
    val body = rest.substring(sep + 2).trim()
    val to = head.substringBefore(' ').trim()
    val subject = head.substringAfter(' ', "").trim()
    if (to.isEmpty() || body.isEmpty()) {
        println("[agentHq-client] usage: /mail <to> <subject> :: <body>")
        return
    }
    val request = MailSendRequest(from = "", to = to, subject = subject, body = body, needsInput = needsInput)
    socket.send(Envelope(Envelope.MAIL_SEND, json.encodeToString(MailSendRequest.serializer(), request)))
}

private class ClientListener(
    private val closed: CountDownLatch,
    /** In one-shot mode, the daemon's `exit` frame is the end of the run; count down on it. */
    private val exitOnDone: Boolean,
) : WebSocket.Listener {
    private val buffer = StringBuilder()

    override fun onOpen(webSocket: WebSocket) {
        webSocket.request(1)
    }

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
        buffer.append(data)
        if (last) {
            val text = buffer.toString()
            buffer.setLength(0)
            render(text)
            webSocket.request(1)
        }
        return null
    }

    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
        println("[agentHq-client] daemon closed ($statusCode $reason)")
        closed.countDown()
        return null
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
        System.err.println("[agentHq-client] error: ${error.message}")
        closed.countDown()
    }

    private fun render(text: String) {
        val envelope = runCatching { json.decodeFromString(Envelope.serializer(), text) }.getOrElse {
            println(text)
            return
        }
        when (envelope.type) {
            Envelope.SESSION -> {
                val rec = runCatching {
                    json.decodeFromString(SessionRecord.serializer(), envelope.data)
                }.getOrNull()
                println(if (rec == null) "[session] ${envelope.data}" else "[session] ${describe(rec)}")
            }
            Envelope.SESSIONS -> {
                val list = runCatching {
                    json.decodeFromString(ListSerializer(SessionRecord.serializer()), envelope.data)
                }.getOrDefault(emptyList())
                println("[sessions] ${list.size}")
                list.forEach { println("  ${describe(it)}") }
            }
            Envelope.WORKTREES -> {
                val list = runCatching {
                    json.decodeFromString(ListSerializer(WorktreeStatus.serializer()), envelope.data)
                }.getOrDefault(emptyList())
                println("[worktrees] ${list.size}")
                list.forEach { println("  ${describeWorktree(it)}") }
            }
            Envelope.WORKTREE -> {
                val status = runCatching {
                    json.decodeFromString(WorktreeStatus.serializer(), envelope.data)
                }.getOrNull()
                println(if (status == null) "[worktree] ${envelope.data}" else "[worktree] ${describeWorktree(status)}")
            }
            Envelope.WORKTREE_REMOVE -> {
                val result = runCatching {
                    json.decodeFromString(CleanupResult.serializer(), envelope.data)
                }.getOrNull()
                println(if (result == null) "[cleanup] ${envelope.data}" else "[cleanup] ${result.message}")
            }
            // Phase 9: the public, provider-neutral vocabulary. These are the frames a UI renders;
            // the raw stdout/stderr/exit frames stay available below for debugging.
            Envelope.TEXT_DELTA -> print(envelope.data)
            Envelope.STATUS -> {
                val status = runCatching {
                    json.decodeFromString(StatusPayload.serializer(), envelope.data)
                }.getOrNull()
                println("[status] ${status?.state ?: envelope.data}${status?.detail?.let { " ($it)" } ?: ""}")
            }
            Envelope.FINISHED -> {
                val done = runCatching {
                    json.decodeFromString(FinishedPayload.serializer(), envelope.data)
                }.getOrNull()
                println(
                    if (done == null) {
                        "[finished] ${envelope.data}"
                    } else {
                        "[finished] ${done.outcome} exit=${done.exitCode}" +
                            (done.detail.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
                    },
                )
                if (exitOnDone) closed.countDown()
            }
            Envelope.MAIL -> {
                val message = runCatching {
                    json.decodeFromString(Message.serializer(), envelope.data)
                }.getOrNull()
                println(if (message == null) "[mail] ${envelope.data}" else "[mail] ${describeMail(message)}")
            }
            Envelope.MAILS -> {
                val list = runCatching {
                    json.decodeFromString(ListSerializer(Message.serializer()), envelope.data)
                }.getOrDefault(emptyList())
                println("[mail] ${list.size} message(s)")
                list.forEach { println("  ${describeMail(it)}") }
            }
            Envelope.MAIL_THREADS -> {
                val list = runCatching {
                    json.decodeFromString(ListSerializer(ThreadSummary.serializer()), envelope.data)
                }.getOrDefault(emptyList())
                println("[threads] ${list.size}")
                list.forEach { t ->
                    println(
                        "  ${t.id} kind=${t.kind} msgs=${t.messageCount} last=${t.lastAt}" +
                            (if (t.ceilingReached) " CEILING" else "") +
                            (if (t.openNeedsInput > 0) " waiting=${t.openNeedsInput}" else ""),
                    )
                }
            }
            Envelope.MAIL_WAITING -> {
                val list = runCatching {
                    json.decodeFromString(ListSerializer(WaitingOn.serializer()), envelope.data)
                }.getOrDefault(emptyList())
                println("[waiting] ${list.size}")
                list.forEach { println("  ${it.session.take(8)} waits on ${it.waitingOn.take(8)} (${it.messageId.take(8)})") }
            }
            Envelope.MAIL_REFUSED -> {
                val refused = runCatching {
                    json.decodeFromString(MailRefused.serializer(), envelope.data)
                }.getOrNull()
                println(if (refused == null) "[mail-refused] ${envelope.data}" else "[mail-refused] ${refused.reason}")
            }
            Envelope.MODELS -> {
                val list = runCatching {
                    json.decodeFromString(ListSerializer(ProviderModels.serializer()), envelope.data)
                }.getOrDefault(emptyList())
                lastModels = list
                list.forEach { g ->
                    val where = g.provider + if (g.group.isNotEmpty()) "/" + g.group else ""
                    println("[models] $where: " + if (g.models.isEmpty()) "none (${g.note ?: "unavailable"})" else g.models.joinToString(" "))
                }
                println("[models] pick one with: /model <id> (or /new <provider> <model>)")
            }
            Envelope.PROVIDERS -> {
                val list = runCatching {
                    json.decodeFromString(ListSerializer(ProviderInfo.serializer()), envelope.data)
                }.getOrDefault(emptyList())
                println("[providers] ${list.size}")
                list.forEach { p ->
                    println("  ${p.id}${if (p.default) " (default)" else ""} model=${p.defaultModel ?: "<CLI default>"}")
                }
            }
            Envelope.AGENT -> runCatching { json.decodeFromString(AgentProfile.serializer(), envelope.data) }
                .onSuccess { println("[agent] saved ${describeAgent(it)}") }
                .onFailure { println("[agent] ${envelope.data}") }
            Envelope.AGENTS -> {
                val list = runCatching {
                    json.decodeFromString(ListSerializer(AgentProfile.serializer()), envelope.data)
                }.getOrDefault(emptyList())
                println("[agents] ${list.size}")
                list.forEach { println("  ${describeAgent(it)}") }
            }
            Envelope.TICKET -> runCatching { json.decodeFromString(Ticket.serializer(), envelope.data) }
                .onSuccess { println("[ticket] ${describeTicket(it)}") }
                .onFailure { println("[ticket] ${envelope.data}") }
            Envelope.TICKETS -> {
                val list = runCatching {
                    json.decodeFromString(ListSerializer(Ticket.serializer()), envelope.data)
                }.getOrDefault(emptyList())
                println("[tickets] ${list.size}")
                // Already in board order; a heading per column that has tickets.
                list.groupBy { it.status }.forEach { (status, tickets) ->
                    println("  $status (${tickets.size})")
                    tickets.forEach { println("    ${describeTicket(it)}") }
                }
            }
            else -> {
                val prefix = when (envelope.type) {
                    Envelope.STDOUT -> "[raw] "
                    Envelope.STDERR -> "[stderr] "
                    Envelope.EXIT -> "[exit] "
                    Envelope.READY -> "[ready] "
                    Envelope.ERROR -> "[error] "
                    else -> "[${envelope.type}] "
                }
                println("$prefix${envelope.data}")
                if (exitOnDone && envelope.type == Envelope.EXIT) closed.countDown()
            }
        }
    }

    private fun describe(rec: SessionRecord): String = buildString {
        append(rec.id.take(8)).append(' ').append(rec.state)
        append(" provider=").append(rec.provider)
        rec.model?.let { append(" model=").append(it) }
        rec.role?.let { append(" role=").append(it) }
        rec.agentId?.let { append(" agent=").append(it) }
        append(" pid=").append(rec.childPid)
        append(" psid=").append(rec.providerSessionId)
        append(" exit=").append(rec.lastExitCode)
        append(" resumes=").append(rec.resumeCount)
        rec.killOutcome?.let { append(" kill=").append(it) }
        rec.lastError?.let { append(" err=").append(it) }
    }.replace('\n', ' ')

    private fun describeAgent(profile: AgentProfile): String = buildString {
        append(profile.id).append(" \"").append(profile.name).append("\" role=").append(profile.role)
        append(" provider=").append(profile.provider ?: "<default>")
        append(" model=").append(profile.model ?: "<default>")
        profile.instructions?.let { append(" instructions=").append(it.length).append(" chars") }
    }.replace('\n', ' ')

    private fun describeTicket(ticket: Ticket): String = buildString {
        append(ticket.id).append(' ').append(ticket.status).append('#').append(ticket.position)
        append(" \"").append(ticket.title).append('"')
        ticket.ticketType?.let { append(" type=").append(it) }
        if (ticket.wontFix) append(" WONT-FIX")
        ticket.holdReason?.let { append(" HELD (").append(it).append(')') }
        if (ticket.links.isNotEmpty()) {
            append(" links=").append(ticket.links.joinToString(", ") { "${it.type} ${it.ticketId}" })
        }
    }.replace('\n', ' ')

    private fun describeWorktree(status: WorktreeStatus): String = buildString {
        append(status.sessionId.take(8)).append(' ').append(status.branch)
        append(" path=").append(status.path)
        append(" exists=").append(status.exists)
        append(" dirty=").append(status.dirty)
        append(" ahead=").append(status.ahead)
        append(" behind=").append(status.behind)
        if (status.removed) append(" removed")
    }.replace('\n', ' ')

    private fun describeMail(message: Message): String = buildString {
        append(message.id.take(8)).append(' ').append(message.state)
        append(' ').append(message.from.take(8)).append(" -> ").append(message.to.take(8))
        append(" thread=").append(message.threadId)
        append(" seq=").append(message.seq)
        if (message.needsInput) append(" NEEDS-INPUT")
        if (message.subject.isNotBlank()) append(" \"").append(message.subject).append('"')
        message.failureReason?.let { append(" err=").append(it) }
    }.replace('\n', ' ')
}
