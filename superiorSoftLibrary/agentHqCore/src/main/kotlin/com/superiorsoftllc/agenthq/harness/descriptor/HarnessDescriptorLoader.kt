package com.superiorsoftllc.agenthq.harness.descriptor

import com.superiorsoftllc.agenthq.harness.HarnessRegistry
import com.superiorsoftllc.agenthq.harness.PluginOutcome
import com.superiorsoftllc.agenthq.runner.PipedProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException

/** Why a descriptor entry or file did not become a harness. A fixed vocabulary: the reason never quotes the file. */
enum class DescriptorNoteReason {
    /** The file could not be read. */
    UNREADABLE,

    /** The file is not JSON. */
    INVALID_JSON,

    /** The JSON is not a usable descriptor; [DescriptorNote.detail] says which rule it broke. */
    INVALID_DESCRIPTOR,

    /** An earlier descriptor already registered this id. */
    DUPLICATE_DESCRIPTOR_ID,

    /** A code plugin (ServiceLoader) already owns this id, and code wins. */
    CODE_PLUGIN_WINS,

    /** The registry declined the harness for a reason of its own. */
    REFUSED,
}

/**
 * One thing the loader did not do. [source] is the file NAME, [harnessId] is set only once the id passed validation
 * (so it is a plain lower-case id, not arbitrary text) and [detail] is a fixed sentence. Nothing in a note is read
 * from the descriptor's contents beyond that id.
 */
data class DescriptorNote(val source: String, val harnessId: String?, val reason: DescriptorNoteReason, val detail: String = "")

/** What [HarnessDescriptorLoader.loadInto] did: the ids it registered and everything it skipped, with why. */
data class DescriptorReport(val loaded: List<String>, val notes: List<DescriptorNote>)

/**
 * HARNESS-DESC-1 - registers the harnesses a directory of `*.json` descriptors declares, into the same
 * [HarnessRegistry] the ServiceLoader plugins went into. A file is one entry (an object) or several (an array).
 *
 * One bad file or entry never hides the others: each is skipped with a [DescriptorNote] and loading goes on. A
 * missing directory is no descriptors, not an error. On an id clash the CODE plugin wins and the descriptor is noted,
 * so a file can never replace a harness a plugin shipped. Files load in name order, so the first of two descriptors
 * with one id is the one that stays.
 */
class HarnessDescriptorLoader(
    private val dir: File,
    private val runner: () -> ProcessRunner = { PipedProcessRunner() },
) {
    fun loadInto(registry: HarnessRegistry): DescriptorReport {
        val loaded = ArrayList<String>()
        val notes = ArrayList<DescriptorNote>()
        if (!dir.isDirectory) return DescriptorReport(loaded, notes)
        val sharedRunner by lazy(runner)

        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }?.sortedBy { it.name }.orEmpty()
        for (file in files) {
            val source = file.name
            if (file.length() > MAX_BYTES) {
                notes += DescriptorNote(source, null, DescriptorNoteReason.INVALID_DESCRIPTOR, "the file is too large")
                continue
            }
            val root = try {
                json.parseToJsonElement(file.readText())
            } catch (e: IOException) {
                notes += DescriptorNote(source, null, DescriptorNoteReason.UNREADABLE)
                continue
            } catch (e: Exception) {
                // The parser's message quotes the offending text: it is dropped, only the reason is kept.
                notes += DescriptorNote(source, null, DescriptorNoteReason.INVALID_JSON)
                continue
            }
            val entries = when (root) {
                is JsonArray -> root.toList()
                is JsonObject -> listOf(root)
                else -> {
                    notes += DescriptorNote(source, null, DescriptorNoteReason.INVALID_DESCRIPTOR, "the file is neither an entry nor an array of entries")
                    continue
                }
            }
            for (entry in entries) {
                val descriptor = try {
                    HarnessDescriptor.parse(entry)
                } catch (e: InvalidDescriptor) {
                    notes += DescriptorNote(source, null, DescriptorNoteReason.INVALID_DESCRIPTOR, e.detail)
                    continue
                }
                val id = descriptor.id
                when {
                    id in loaded -> notes += DescriptorNote(source, id, DescriptorNoteReason.DUPLICATE_DESCRIPTOR_ID)
                    registry.resolve(id) != null -> notes += DescriptorNote(source, id, DescriptorNoteReason.CODE_PLUGIN_WINS)
                    else -> when (val outcome = registry.register(DescriptorHarnessPlugin(DescriptorHarness(descriptor, sharedRunner)), "descriptor:$source")) {
                        is PluginOutcome.Accepted -> loaded += id
                        is PluginOutcome.Refused -> notes += DescriptorNote(source, id, DescriptorNoteReason.REFUSED, outcome.reason.name)
                        is PluginOutcome.Failed -> notes += DescriptorNote(source, id, DescriptorNoteReason.REFUSED, "registration failed")
                    }
                }
            }
        }
        return DescriptorReport(loaded, notes)
    }

    private companion object {
        const val SUFFIX = ".json"
        const val MAX_BYTES = 256 * 1024L
        val json = Json
    }
}
