package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.agents.AgentProfiles
import com.superiorsoftllc.agenthq.cli.Cli
import com.superiorsoftllc.agenthq.protocol.SpawnOptions
import com.superiorsoftllc.agenthq.session.SessionManager
import com.superiorsoftllc.agenthq.session.SessionRecord
import java.io.File

/**
 * ASSISTANT-1 (contract 1.115.0): the ONE Assistant of an installation.
 *
 * It is an ordinary session in every way a client can see (so the chat, `session_swap`, resume, kill all work on it), with
 * three differences: it belongs to no project ([SessionRecord.projectId] null, so a project switch never hides it),
 * [SessionRecord.global] marks it, and it runs in its own directory under the daemon's state dir, never the user's home.
 * Its role is `assistant` and stays so (`session_swap` refuses a role change on a global session); the model is free.
 */
object AssistantService {
    const val ROLE = "assistant"


    private val lock = Any()

    /** The Assistant session, created on the first call. Later calls (and other clients) get the same one. */
    fun ensure(manager: SessionManager, agents: AgentProfiles): SessionRecord = synchronized(lock) {
        manager.assistantDir?.let { runCatching { it.mkdirs(); syncKnowledge(it) } }
        manager.list().firstOrNull { it.global }?.let { return it }
        val dir = (manager.assistantDir ?: throw IllegalArgumentException("this daemon has no assistant directory configured")).also { it.mkdirs() }
        val resolved = agents.resolveSpawn(SpawnOptions(role = ROLE))
        manager.startNew(
            prompt = "",
            workingDir = dir,
            providerId = resolved.providerId,
            model = resolved.model,
            effort = resolved.effort,
            role = ROLE,
            projectId = null,
            instance = agents.instanceFor(null, ROLE),
            global = true,
        )
    }

    /** Classpath location of the bundled user-guide knowledge file. */
    const val KNOWLEDGE_RESOURCE = "assistant/KNOWLEDGE.md"
    const val KNOWLEDGE_FILE = "KNOWLEDGE.md"

    /**
     * Copies the bundled KNOWLEDGE.md into the Assistant's directory: written when missing, overwritten when the bundled
     * copy is newer than the one on disk (a daemon update), left alone otherwise. Returns true when it wrote the file.
     */
    fun syncKnowledge(dir: File, loader: ClassLoader = AssistantService::class.java.classLoader): Boolean {
        val url = loader.getResource(KNOWLEDGE_RESOURCE) ?: return false
        val conn = url.openConnection().apply { useCaches = false }
        val bundledModified = conn.lastModified
        val bytes = conn.getInputStream().use { it.readBytes() }
        val target = File(dir, KNOWLEDGE_FILE)
        if (target.isFile) {
            if (bundledModified <= 0L || bundledModified <= target.lastModified()) return false
            if (target.readBytes().contentEquals(bytes)) return false
        }
        target.writeBytes(bytes)
        if (bundledModified > 0L) target.setLastModified(bundledModified)
        return true
    }

    /**
     * The Assistant's standing instructions: its role persona, then how to operate Infantry AI, generated from the real
     * CLI help (so a new command shows up here with no edit). It is frozen into the session's instance at spawn like any
     * persona, and it asks for no restriction: the Assistant has the same tools and shell any worker has.
     */
    fun instructions(persona: String?, help: String = Cli.helpText()): String = buildString {
        persona?.trim()?.takeIf { it.isNotEmpty() }?.let { appendLine(it).appendLine() }
        appendLine("You are the Assistant of this Infantry AI installation. You are global: not bound to one project, you can see and act in all of them, and you are always reachable from the top bar.")
        appendLine("Your job is to help the person using the app work it: explain screens, errors and settings, and DO things for them (create and move tickets, start and stop sessions, change settings, read the board) when they ask. You have no tool restrictions: every agenthq MCP tool, the `agenthq` / `infantry` CLI on your PATH, and file and shell access like any worker. Prefer acting over describing, and say plainly what you did.")
        appendLine()
        appendLine("How Infantry AI is organised:")
        appendLine("- Projects hold a board of tickets (columns: todo, in progress, review, done; `pending` is derived from a blocked_by link and never set by hand), a knowledge base (notes, decisions, issues) and a team of roles.")
        appendLine("- A ticket is started with `agenthq ticket start <id>`: a worker with the ticket's role, provider and model runs on the ticket's own branch. Never use spawn for ticket work.")
        appendLine("- Roles (architect, backend, frontend, qa, pm, assistant, ...) carry a persona and standing rules; a role binding says which provider and model it runs on. Triggers start work on a schedule or an event.")
        appendLine("- Settings (the gear, bottom right) hold the connection, the harness locations, models, voice, remote access and pairing for a phone. The CLI covers the same ground; run `agenthq --help` for the live list.")
        appendLine("- Mail: sessions message each other and the owner with `agenthq send`; questions to the owner use `agenthq ask`.")
        appendLine("- User guide: $KNOWLEDGE_FILE in your working directory holds setup, FAQ, troubleshooting, known issues, roadmap and help links; read the matching section before you answer those questions.")
        appendLine()
        appendLine("The complete CLI help of the daemon you are talking to (authoritative, generated now):")
        appendLine()
        append(help.trim())
    }
}
