// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.notes.daemon

import com.superiorsoftllc.infantry.daemon.api.SessionInfo
import com.superiorsoftllc.infantry.daemon.api.knowledge.AgentBriefing
import com.superiorsoftllc.infantry.daemon.api.knowledge.BriefingBudget
import com.superiorsoftllc.infantry.daemon.api.knowledge.KnowledgeBriefing
import com.superiorsoftllc.infantry.daemon.api.knowledge.KnowledgeEntry
import com.superiorsoftllc.infantry.daemon.api.knowledge.KnowledgeSections
import com.superiorsoftllc.infantry.daemon.api.plugins.LoadedModule
import com.superiorsoftllc.infantry.plugin.api.KnowledgeDeclarations

/**
 * PK-2 (plan CAL-PLAN-2 B3.2, B3.3, B4): the provider of plugin knowledge. It keeps one global note `plugin:<Module>/<id>` per
 * knowledge entry of a loaded module (declared in module.json, or contributed at run time), answers [AgentBriefing], and tells the
 * live sessions in an entry's scope when tools appear, change or go. Pure over its [Ports], so a test drives it without a daemon.
 *
 * A notice is coalesced (one per session per change burst, [Ports.coalesceMs]) and by default does NOT wake an idle session: it is
 * held and delivered when the session next starts a turn ([onTurnStarted]); a session already working gets it at once.
 */
class PluginKnowledgeProvider(private val ports: Ports) : AgentBriefing {
    /** The seams the provider needs; the daemon half wires each from its DaemonApi. */
    class Ports(
        val saveNote: (name: String, description: String, body: String) -> Unit,
        val deleteNote: (name: String) -> Unit,
        val noteNames: () -> List<String>,
        val loaded: () -> List<LoadedModule>,
        /** The text of [file] inside the folder of a module, or null. */
        val readFile: (module: LoadedModule, file: String) -> String?,
        val sessions: () -> List<SessionInfo>,
        val send: (sessionId: String, subject: String, body: String) -> Boolean,
        val notifyLive: () -> Boolean,
        val noticeWakes: () -> Boolean,
        val coalesceMs: () -> Long,
        /** Runs the block once, after the delay (the provider never has two timers armed). */
        val schedule: (delayMs: Long, block: () -> Unit) -> Unit,
        val log: (String) -> Unit = {},
    )

    private class Item(val entry: KnowledgeEntry, val fingerprint: String)
    internal enum class Kind { ADDED, REMOVED }
    internal class Change(val kind: Kind, val entry: KnowledgeEntry)

    private val lock = Any()
    private var current: Map<String, Item>? = null // note name -> item; null until the first reconcile (the start sends no notices)
    private val dynamic = LinkedHashMap<String, KnowledgeEntry>() // "module/id" -> entry
    private val pending = LinkedHashMap<String, LinkedHashMap<String, Change>>() // session -> note name -> change, awaiting the timer
    private val held = LinkedHashMap<String, LinkedHashMap<String, Change>>() // session -> changes held until its next turn
    private var timerArmed = false

    /** Every entry now in force: the declared ones of the loaded modules, then the contributed ones of a loaded module. */
    private fun desired(): Map<String, Item> {
        val modules = ports.loaded()
        val out = LinkedHashMap<String, Item>()
        for (m in modules) {
            for (d in KnowledgeDeclarations.valid(m.manifest.knowledge)) {
                val body = d.file.trim().takeIf { it.isNotEmpty() }?.let { f -> ports.readFile(m, f) }?.let { KnowledgeSections.textFor(it, null) }.orEmpty()
                val e = KnowledgeEntry.of(m.id, d, body)
                out[KnowledgeBriefing.noteName(e)] = Item(e, m.manifest.version + "|" + fingerprint(e))
            }
        }
        val ids = modules.map { it.id }.toSet()
        for (e in dynamic.values.filter { it.module in ids }) out.putIfAbsent(KnowledgeBriefing.noteName(e), Item(e, "dyn|" + fingerprint(e)))
        return out
    }

    private fun fingerprint(e: KnowledgeEntry): String =
        listOf(e.summary, e.body, e.tools, e.roles, e.skipRoles, e.projects).joinToString("\u0000").hashCode().toString(16)

    /** Brings the notes in line with the loaded modules and queues notices for what changed. Call at start and on `modules.changed`. */
    fun reconcile() {
        var arm = false
        synchronized(lock) {
            val now = desired()
            val before = current
            for ((name, item) in now) {
                if (before?.get(name)?.fingerprint == item.fingerprint) continue
                runCatching { ports.saveNote(name, item.entry.summary, noteBody(item.entry)) }.onFailure { ports.log("plugin knowledge: could not write $name: ${it.message}") }
                if (before != null) arm = queue(Change(Kind.ADDED, item.entry), name) || arm
            }
            // Plugin notes that no loaded module declares any more (also those left by an earlier run) go.
            for (name in ports.noteNames().filter { it.startsWith(PREFIX) && it !in now }) {
                runCatching { ports.deleteNote(name) }
                before?.get(name)?.let { arm = queue(Change(Kind.REMOVED, it.entry), name) || arm }
            }
            current = now
        }
        if (arm) arm()
    }

    private fun noteBody(e: KnowledgeEntry): String = buildString {
        append(e.summary)
        if (e.tools.isNotEmpty()) append("\n\nTools: ").append(e.tools.joinToString(", "))
        if (e.body.isNotBlank()) append("\n\n").append(e.body.trim())
    }

    /** Queues [change] for every live session in scope (under [lock]); true when at least one session will hear of it. */
    private fun queue(change: Change, name: String): Boolean {
        if (!ports.notifyLive()) return false
        var any = false
        for (s in ports.sessions()) {
            if (s.neverRun || s.state in DEAD || !change.entry.appliesTo(s.role, s.projectId)) continue
            pending.getOrPut(s.id) { LinkedHashMap() }[name] = change
            any = true
        }
        return any
    }

    private fun arm() {
        synchronized(lock) { if (timerArmed) return; timerArmed = true }
        ports.schedule(ports.coalesceMs().coerceAtLeast(0)) { flush() }
    }

    /** The coalesce timer fired: one notice per session. */
    internal fun flush() {
        val batch = synchronized(lock) {
            timerArmed = false
            LinkedHashMap(pending).also { pending.clear() }
        }
        val states = ports.sessions().associate { it.id to it.state }
        for ((sid, changes) in batch) {
            val state = states[sid] ?: continue // the session is gone
            synchronized(lock) { merge(held.getOrPut(sid) { LinkedHashMap() }, changes) }
            if (ports.noticeWakes() || state == "BUSY") deliver(sid)
            // else it stays held; onTurnStarted delivers it with the session's next turn.
        }
    }

    private fun merge(into: LinkedHashMap<String, Change>, add: Map<String, Change>) {
        for ((name, c) in add) {
            val old = into[name]
            // Added then removed before the session heard of it: it never knew, so say nothing.
            if (old?.kind == Kind.ADDED && c.kind == Kind.REMOVED) into.remove(name) else into[name] = c
        }
    }

    /** A session started a turn: whatever was held for it arrives now. */
    fun onTurnStarted(sessionId: String) {
        if (synchronized(lock) { held[sessionId]?.isNotEmpty() } == true) deliver(sessionId)
    }

    /** A session ended: nothing more is owed to it. */
    fun onSessionEnded(sessionId: String) {
        synchronized(lock) { held.remove(sessionId); pending.remove(sessionId) }
    }

    private fun deliver(sessionId: String) {
        val changes = synchronized(lock) { held.remove(sessionId) } ?: return
        val text = notice(changes.values.toList()) ?: return
        if (!ports.send(sessionId, "Plugin tools changed", text)) ports.log("plugin knowledge: notice to $sessionId was not delivered")
    }

    /** The notice text for [changes]; null when empty. Added/upgraded and removed are separate paragraphs. */
    internal fun notice(changes: List<Change>): String? {
        val added = changes.filter { it.kind == Kind.ADDED }.map { it.entry }
        val removed = changes.filter { it.kind == Kind.REMOVED }.map { it.entry }
        val parts = ArrayList<String>()
        if (added.isNotEmpty()) {
            val tools = added.flatMap { it.tools }.distinct()
            parts += buildString {
                append("New tools available:")
                for (e in added) append("\n- ").append(e.summary).append(" (details: infantry memory show ").append(KnowledgeBriefing.noteName(e)).append(")")
                append("\nIf your tool list doesn't show them yet, call them with `infantry tool <name>`")
                if (tools.isNotEmpty()) append(" (").append(tools.joinToString(", ")).append(")")
                append('.')
            }
        }
        if (removed.isNotEmpty()) {
            val names = removed.flatMap { it.tools.ifEmpty { listOf(it.id) } }.distinct()
            parts += "These tools were removed; do not call: ${names.joinToString(", ")}."
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    // ---- the SDK ports ----

    override fun section(role: String?, projectId: String?, budget: BriefingBudget): String? =
        KnowledgeBriefing.render(synchronized(lock) { (current ?: desired()).values.map { it.entry } }, role, projectId, budget)

    /** PluginKnowledge.contribute for [module]: adds the entry and reconciles; closing removes it. */
    fun contribute(module: String, entry: KnowledgeEntry): AutoCloseable {
        val key = "$module/${entry.id}"
        synchronized(lock) { dynamic[key] = entry.copy(module = module) }
        reconcile()
        return AutoCloseable {
            synchronized(lock) { dynamic.remove(key) }
            reconcile()
        }
    }

    companion object {
        const val PREFIX = "plugin:"
        private val DEAD = setOf("FAILED", "ORPHANED")
    }
}
