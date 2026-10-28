package com.superiorsoftllc.agenthq.commands

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What a client sees of a saved command (contract `commands` frame): id, name, cwd (1.33.0), and since 1.94.0
 * (RUN-CMD-1, owner decision) the argv and grace window too, so the Run tab's editor can show what it edits. The
 * bearer token is already the whole credential (CONTRACT section 5), so the argv is no secret from a client; it
 * still never reaches a LOG line, hence [toString]. 1.135.0 (SECRETS-2): [secrets], the keystore secrets whose fields
 * the run gets as environment variables (names only; a value never leaves the daemon).
 */
@Serializable
data class CommandSpec(
    val id: String,
    val name: String,
    val cwd: String? = null,
    val argv: List<String> = emptyList(),
    val graceMillis: Long = DEFAULT_GRACE_MILLIS,
    val secrets: List<String> = emptyList(),
) {
    override fun toString(): String = "CommandSpec(id=$id)"
}

/** One entry of `commands.json`. Daemon-side only: it carries the argv and is never serialised to a client. */
@Serializable
internal data class CommandFileEntry(
    val id: String = "",
    val name: String? = null,
    val argv: List<String> = emptyList(),
    val cwd: String? = null,
    @SerialName("graceMillis") val graceMillis: Long = DEFAULT_GRACE_MILLIS,
    val secrets: List<String> = emptyList(),
)

/** A validated command, as the run manager (T3) will execute it. [workingDir] is canonical and inside the repo. */
class SavedCommand internal constructor(
    val id: String,
    val name: String,
    val argv: List<String>,
    val cwd: String?,
    val workingDir: File,
    val graceMillis: Long,
    val secrets: List<String> = emptyList(),
) {
    fun toSpec(): CommandSpec = CommandSpec(id, name, cwd, argv, graceMillis, secrets)

    internal fun toFileEntry(): CommandFileEntry = CommandFileEntry(id, name, argv, cwd, graceMillis, secrets)

    // argv must never reach a log line by accident.
    override fun toString(): String = "SavedCommand(id=$id)"
}

const val DEFAULT_GRACE_MILLIS = 5_000L

/** The longest grace window a command may ask for (RUN-CMD-1): a stop never waits more than ten minutes to escalate. */
const val MAX_GRACE_MILLIS = 600_000L

/** SECRETS-2: the most secrets one command (or one run_start) may name; setting `limits.secrets.maxPerRun` (SEC-FIX-2a). */
val maxRunSecrets: Int
    get() = com.superiorsoftllc.agenthq.settings.LimitRegistry.let { it.cap(it.RUN_MAX_SECRETS.default, it.RUN_MAX_SECRETS) }

/**
 * The daemon's saved commands, loaded once from `<AGENTHQ_HOME>/commands.json` (design sections 2-3).
 *
 * All-or-nothing: one bad entry refuses the WHOLE file, [refusal] says why (never the argv), and the
 * registry is empty. A missing file is simply "no commands".
 */
class CommandRegistry private constructor(
    val commands: List<SavedCommand>,
    val refusal: String?,
) {
    fun find(id: String): SavedCommand? = commands.firstOrNull { it.id == id }

    /** The client-facing list: id, name, cwd, argv, graceMillis. */
    fun specs(): List<CommandSpec> = commands.map { it.toSpec() }

    fun specsJson(): String = wire.encodeToString(ListSerializer(CommandSpec.serializer()), specs())

    companion object {
        private val wire = Json { encodeDefaults = true }
        private val reader = Json { ignoreUnknownKeys = true }
        private val fileWriter = Json { encodeDefaults = true; prettyPrint = true }

        val EMPTY = CommandRegistry(emptyList(), null)

        /** Load [file] against [repo]. Logs a refusal reason to stderr; never throws. */
        fun load(file: File, repo: File?): CommandRegistry {
            if (!file.isFile) return EMPTY
            val result = parse(runCatching { file.readText() }.getOrElse {
                return refuse("commands.json unreadable (${it::class.simpleName})")
            }, repo)
            result.refusal?.let { System.err.println("[agentHq] commands.json refused, no commands loaded: $it") }
            return result
        }

        /** Parse and validate [text]. The reason never contains an argv element. */
        fun parse(text: String, repo: File?): CommandRegistry {
            val entries = try {
                reader.decodeFromString(ListSerializer(CommandFileEntry.serializer()), text)
            } catch (e: Exception) {
                // The message of a parse error can quote the offending text (argv): name the class only.
                return refuse("not a valid JSON array of commands (${e::class.simpleName})")
            }
            if (entries.isEmpty()) return EMPTY
            if (repo == null) return refuse("AGENTHQ_REPO is not set, so no working directory can be checked")
            val root = canonicalRoot(repo) ?: return refuse("AGENTHQ_REPO cannot be resolved")
            val seen = HashSet<String>()
            val out = ArrayList<SavedCommand>()
            entries.forEachIndexed { i, e ->
                val where = "entry #$i"
                if (e.id.isNotBlank() && !seen.add(e.id)) return refuse("$where repeats id '${e.id}'")
                out += validate(e, root, where).getOrElse { return refuse(it.message ?: "$where is invalid") }
            }
            return CommandRegistry(out, null)
        }

        /** [repo] canonicalised, or null when it is unset or cannot be resolved. */
        internal fun canonicalRoot(repo: File?): File? = repo?.let { runCatching { it.toPath().toRealPath().toFile() }.getOrNull() }

        /**
         * The ONE per-entry rule set, shared by [parse] (load) and [CommandStore] (command_save): non-blank id,
         * non-empty argv of non-blank elements, a bounded graceMillis, and a cwd canonicalised inside [root].
         * The failure's message names [where] and the id, never an argv element.
         */
        internal fun validate(e: CommandFileEntry, root: File, where: String): Result<SavedCommand> {
            fun no(why: String) = Result.failure<SavedCommand>(IllegalArgumentException(why))
            if (e.id.isBlank()) return no("$where has a blank id")
            if (e.argv.isEmpty() || e.argv.any { it.isBlank() }) return no("$where ('${e.id}') has an empty argv")
            if (e.graceMillis <= 0) return no("$where ('${e.id}') has a non-positive graceMillis")
            if (e.graceMillis > MAX_GRACE_MILLIS) return no("$where ('${e.id}') has a graceMillis over $MAX_GRACE_MILLIS")
            if (e.secrets.size > maxRunSecrets) return no("$where ('${e.id}') names more than $maxRunSecrets secrets")
            if (e.secrets.any { !com.superiorsoftllc.agenthq.keystore.Keystore.isName(it) }) return no("$where ('${e.id}') names an invalid secret")
            val cwd = e.cwd?.takeIf { it.isNotBlank() }
            val dir = resolveCwd(root, cwd) ?: return no("$where ('${e.id}') has a cwd outside the repo")
            return Result.success(SavedCommand(e.id, e.name?.takeIf { it.isNotBlank() } ?: e.id, e.argv.toList(), cwd, dir, e.graceMillis, e.secrets.distinct()))
        }

        /** The file text for [commands], in order: what `commands.json` holds after a save or a delete. */
        internal fun encodeFile(commands: List<SavedCommand>): String =
            fileWriter.encodeToString(ListSerializer(CommandFileEntry.serializer()), commands.map { it.toFileEntry() })

        /** [cwd] relative to [root] canonicalised inside it, or null when it is absolute, has `..`, or escapes. */
        internal fun resolveCwd(root: File, cwd: String?): File? {
            if (cwd == null) return root
            val segments = cwd.split('/', '\\')
            if (segments.any { it == ".." }) return null
            if (cwd.startsWith("/") || cwd.startsWith("\\") || File(cwd).isAbsolute || Regex("^[A-Za-z]:").containsMatchIn(cwd)) return null
            val resolved = runCatching { File(root, cwd).toPath().toRealPath().toFile() }.getOrNull() ?: return null
            return if (resolved.toPath().startsWith(root.toPath())) resolved else null
        }

        private fun refuse(reason: String) = CommandRegistry(emptyList(), reason)
    }
}
