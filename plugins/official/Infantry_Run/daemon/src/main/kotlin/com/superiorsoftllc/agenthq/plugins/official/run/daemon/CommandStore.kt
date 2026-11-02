package com.superiorsoftllc.agenthq.plugins.official.run.daemon

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** `command_delete` data (contract 1.94.0). */
@Serializable
internal data class CommandDeleteRequest(val id: String = "")

/**
 * RUN-CMD-1 (contract 1.94.0): the daemon's saved commands, editable from a client. [registry] is what
 * `command_list` and `run_start` read; [save] / [delete] rewrite `<AGENTHQ_HOME>/commands.json` and reload it.
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
    private val repo: File?,
    initial: CommandRegistry = CommandRegistry.load(file, repo),
) {
    @Volatile var registry: CommandRegistry = initial
        private set

    private val lock = Any()

    /** Upsert by id. Returns null when saved, else the refusal reason (no `command_save refused:` prefix). */
    fun save(data: String, guard: (String, () -> String?) -> String? = NO_GUARD): String? {
        val entry = try {
            strict.decodeFromString(CommandFileEntry.serializer(), data)
        } catch (e: Exception) {
            // A decode message can quote the offending text (the argv): the class name only.
            return "expects {\"id\",\"name\"?,\"argv\":[...],\"cwd\"?,\"graceMillis\"?,\"secrets\"?:[...]} (${e::class.simpleName})"
        }
        val root = CommandRegistry.canonicalRoot(repo)
            ?: return "AGENTHQ_REPO is not set or cannot be resolved, so no working directory can be checked"
        val saved = CommandRegistry.validate(entry, root, "command").getOrElse { return it.message }
        return synchronized(lock) {
            guard(saved.id) change@{
                val current = onDisk() ?: return@change refusedOnDisk()
                val next = current.commands.toMutableList()
                val at = next.indexOfFirst { it.id == saved.id }
                if (at >= 0) next[at] = saved else next += saved
                write(next, "saved", saved.id)
            }
        }
    }

    /** Remove by id. Returns null when deleted, else the refusal reason (no `command_delete refused:` prefix). */
    fun delete(data: String, guard: (String, () -> String?) -> String? = NO_GUARD): String? {
        val id = try {
            strict.decodeFromString(CommandDeleteRequest.serializer(), data).id
        } catch (e: Exception) {
            return "expects {\"id\":\"<id>\"} (${e::class.simpleName})"
        }
        if (id.isBlank()) return "expects {\"id\":\"<id>\"} with a non-blank id"
        return synchronized(lock) {
            guard(id) change@{
                val current = onDisk() ?: return@change refusedOnDisk()
                if (current.find(id) == null) return@change "no such command '$id'"
                write(current.commands.filterNot { it.id == id }, "deleted", id)
            }
        }
    }

    /** The file as it stands now (a hand edit since start counts), or null when the daemon refuses it. */
    private fun onDisk(): CommandRegistry? = CommandRegistry.load(file, repo).takeIf { it.refusal == null }

    private fun refusedOnDisk() = "commands.json is refused as it stands (${CommandRegistry.load(file, repo).refusal}); fix or remove it first"

    private fun write(commands: List<SavedCommand>, verb: String, id: String): String? {
        val text = CommandRegistry.encodeFile(commands)
        // Belt and braces: the file about to be written must load back cleanly, or nothing is written.
        val reparsed = CommandRegistry.parse(text, repo)
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
        println("[agentHq] command '$id' $verb (${commands.size} saved)")
        return null
    }

    companion object {
        private val strict = Json { ignoreUnknownKeys = false }
        private val NO_GUARD: (String, () -> String?) -> String? = { _, change -> change() }
    }
}
