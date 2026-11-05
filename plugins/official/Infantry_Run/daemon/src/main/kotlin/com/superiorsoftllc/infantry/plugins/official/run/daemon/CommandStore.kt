// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.run.daemon

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** `command_delete` data (contract 1.94.0). */
@Serializable
internal data class CommandDeleteRequest(val id: String = "", val project: String? = null)

/**
 * RUN-CMD-1 (contract 1.94.0): the daemon's saved commands, editable from a client. [registry] is what
 * `command_list` and `run_start` read; [save] / [delete] rewrite `<INFANTRY_HOME>/commands.json` and reload it.
 *
 * Rules:
 *  - A save is validated by the SAME per-entry rules as a load ([CommandRegistry.validate]), and the whole new file
 *    is re-parsed before it is written, so a bad entry never reaches the disk.
 *  - The write is a temp file in the same directory, then one atomic move over the target.
 *  - A file the daemon REFUSED at load (a hand edit gone wrong) is never overwritten: the owner fixes or removes it.
 *  - Every refusal and every log line names the id only, never an argv element.
 *
 * Mutations are serialised on this store; the caller passes [RunManager.unlessRunning] as the guard so a command
 * with a run in flight is never changed under it.
 */
class CommandStore(
    private val file: File,
    private val rootOf: (String?) -> File?,
    initial: CommandRegistry = CommandRegistry.load(file, rootOf),
) {
    constructor(file: File, repo: File?) : this(file, { repo })

    @Volatile var registry: CommandRegistry = initial
        private set

    private val lock = Any()

    /** Upsert by id. Returns null when saved, else the refusal reason (no `command_save refused:` prefix). */
    fun save(data: String, guard: (String, () -> String?) -> String? = NO_GUARD, project: String? = null): String? {
        val entry = try {
            strict.decodeFromString(CommandFileEntry.serializer(), data)
        } catch (e: Exception) {
            // A decode message can quote the offending text (the argv): the class name only.
            return "expects {\"id\",\"name\"?,\"argv\":[...],\"cwd\"?,\"graceMillis\"?,\"secrets\"?:[...]} (${e::class.simpleName})"
        }
        // RUN-SCOPE-1: a save that names no project keeps the one the command already has (an older client never unassigns it).
        val projectId = (project ?: entry.project)?.takeIf { it.isNotBlank() } ?: registry.find(entry.id)?.projectId
        val root = CommandRegistry.canonicalRoot(rootOf(projectId)) ?: return CommandRegistry.NO_ROOT
        val saved = CommandRegistry.validate(entry.copy(project = projectId), root, "command").getOrElse { return it.message }
        return synchronized(lock) {
            guard(saved.id) change@{
                val current = onDisk() ?: return@change refusedOnDisk()
                // RUN-SCOPE-1: ids are unique across projects; another project's command is never overwritten. An unassigned
                // one may be claimed (that is "assign to project").
                current.find(saved.id)?.projectId?.let { owner ->
                    if (owner != saved.projectId) return@change ownedElsewhere(saved.id, owner)
                }
                val next = current.commands.toMutableList()
                val at = next.indexOfFirst { it.id == saved.id }
                if (at >= 0) next[at] = saved else next += saved
                write(next, "saved", saved.id)
            }
        }
    }

    /** Remove by id. Returns null when deleted, else the refusal reason (no `command_delete refused:` prefix). */
    fun delete(data: String, guard: (String, () -> String?) -> String? = NO_GUARD, project: String? = null): String? {
        val request = try {
            strict.decodeFromString(CommandDeleteRequest.serializer(), data)
        } catch (e: Exception) {
            return "expects {\"id\":\"<id>\"} (${e::class.simpleName})"
        }
        val id = request.id
        if (id.isBlank()) return "expects {\"id\":\"<id>\"} with a non-blank id"
        val wanted = (project ?: request.project)?.takeIf { it.isNotBlank() }
        return synchronized(lock) {
            guard(id) change@{
                val current = onDisk() ?: return@change refusedOnDisk()
                val found = current.find(id) ?: return@change "no such command '$id'"
                // RUN-SCOPE-1: a caller scoped to a project deletes only its own (or an unassigned) command.
                val owner = found.projectId
                if (wanted != null && owner != null && owner != wanted) return@change ownedElsewhere(id, owner)
                write(current.commands.filterNot { it.id == id }, "deleted", id)
            }
        }
    }

    /**
     * RUN-SCOPE-1 migration: every UNASSIGNED command whose working directory lies inside a project's folder is assigned to
     * that project (the deepest folder wins), its cwd rewritten relative to it. A command no folder contains stays unassigned
     * (the Run tab lists it under "Unassigned" with an assign action); nothing is ever deleted. [projects] is (id, folder).
     * Returns the ids assigned; the file is rewritten only when there is one.
     */
    fun assignUnassigned(projects: List<Pair<String, File>>): List<String> = synchronized(lock) {
        val current = onDisk() ?: return emptyList()
        val roots = projects.mapNotNull { (id, dir) -> CommandRegistry.canonicalRoot(dir)?.takeIf { it.isDirectory }?.let { id to it } }
            .sortedByDescending { it.second.path.length }
        val assigned = ArrayList<String>()
        val next = current.commands.map { c ->
            val dir = c.dir
            if (c.projectId != null || dir == null) return@map c
            val (pid, root) = roots.firstOrNull { (_, r) -> dir.toPath().startsWith(r.toPath()) } ?: return@map c
            assigned += c.id
            c.movedTo(pid, dir.relativeTo(root).path.replace('\\', '/').takeIf { it.isNotEmpty() }, dir)
        }
        if (assigned.isEmpty()) return emptyList()
        write(next, "assigned to its project", assigned.joinToString(","))?.let {
            System.err.println("[infantry] commands.json migration not written: $it")
            return emptyList()
        }
        assigned
    }

    /**
     * RUN-SCOPE-1: when a command has no folder (its project had none), reload so a folder registered since takes effect
     * without a restart. A no-op while every command is rooted. Returns the registry as it now stands.
     */
    fun refreshUnrooted(): CommandRegistry = synchronized(lock) {
        if (registry.commands.any { !it.rooted }) onDisk()?.let { registry = it }
        registry
    }

    private fun ownedElsewhere(id: String, owner: String) =
        "command '$id' belongs to project '$owner'; open that project's Run tab, or pick another id"

    /** The file as it stands now (a hand edit since start counts), or null when the daemon refuses it. */
    private fun onDisk(): CommandRegistry? = CommandRegistry.load(file, rootOf).takeIf { it.refusal == null }

    private fun refusedOnDisk() = "commands.json is refused as it stands (${CommandRegistry.load(file, rootOf).refusal}); fix or remove it first"

    private fun write(commands: List<SavedCommand>, verb: String, id: String): String? {
        val text = CommandRegistry.encodeFile(commands)
        // Belt and braces: the file about to be written must load back cleanly, or nothing is written.
        val reparsed = CommandRegistry.parseWith(text, rootOf)
        reparsed.refusal?.let { return it }
        try {
            file.absoluteFile.parentFile?.mkdirs()
            val tmp = File(file.absoluteFile.parentFile, "${file.name}.tmp")
            tmp.writeText(text)
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            return "commands.json could not be written (${e::class.simpleName})"
        }
        registry = reparsed
        println("[infantry] command '$id' $verb (${commands.size} saved)")
        return null
    }

    companion object {
        private val strict = Json { ignoreUnknownKeys = false }
        private val NO_GUARD: (String, () -> String?) -> String? = { _, change -> change() }
    }
}
