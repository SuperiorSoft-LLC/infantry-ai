package com.superiorsoftllc.agenthq.harness.pi

import com.superiorsoftllc.agenthq.protocol.ProviderModels
import com.superiorsoftllc.agenthq.provider.HarnessModelCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * ROLE-FIRST S4: Pi's providers and models for the `models` frame.
 *
 * Source order (docs/AGENTHQ-ROLE-FIRST.md 5.2/5.3):
 *  1. `pi --list-models` -- authoritative (includes models reachable through logged-in providers). It prints a table
 *     `provider  model  context  max-out  thinking  images`; the first column is the provider (row group), and the
 *     id handed back is `provider/model`, the spelling Pi's own `--model` documents ("supports provider/id") and the
 *     only one that is unambiguous (the same model name is listed under several providers).
 *  2. When that fails (Pi not installed, timeout, non-zero exit, oversized output): Pi's per-provider cache
 *     `~/.pi/agent/models-store.json` (`{ <provider>: { models: [ { id } ] } }`), plus a NOTE row saying why.
 *     `auth.json` (secrets) is never read.
 *  3. Both fail: one noted empty row.
 *
 * Errors are surfaced as notes built from fixed reasons -- never the command's output or a file's content (the CP-1
 * leak rule). The result is cached for [ttlMs] so a burst of `models` requests spawns Pi once.
 */
class PiModelCatalog(
    private val listModels: () -> String = { runListModels() },
    private val storeFile: () -> File? = { defaultStoreFile() },
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : HarnessModelCatalog {

    private val lock = Any()
    private var cached: List<ProviderModels>? = null
    private var cachedAt = 0L

    override fun models(): List<ProviderModels> = synchronized(lock) {
        val now = clock()
        cached?.takeIf { now - cachedAt < ttlMs }?.let { return it }
        compute().also { cached = it; cachedAt = now }
    }

    private fun compute(): List<ProviderModels> {
        val listed = try {
            parseListModels(listModels())
        } catch (e: Exception) {
            return fallback("pi --list-models failed: ${reason(e)}")
        }
        if (listed.isEmpty()) return listOf(ProviderModels(ID, "", ID, emptyList(), "pi --list-models listed no models"))
        return rows(listed)
    }

    private fun fallback(why: String): List<ProviderModels> {
        val stored = try {
            val file = storeFile() ?: return noted("$why; no models-store.json")
            if (!file.isFile) return noted("$why; no models-store.json")
            parseModelsStore(file.readText())
        } catch (e: Exception) {
            return noted("$why; models-store.json unreadable")
        }
        if (stored.isEmpty()) return noted("$why; models-store.json lists no models")
        return rows(stored) + ProviderModels(ID, NOTES_GROUP, "notes", emptyList(), "$why; showing Pi's cached models-store.json")
    }

    private fun noted(why: String) = listOf(ProviderModels(ID, "", ID, emptyList(), "could not list models: $why"))

    private fun rows(byProvider: Map<String, List<String>>): List<ProviderModels> =
        byProvider.map { (provider, ids) -> ProviderModels(ID, provider, provider, ids) }

    companion object {
        private const val ID = PiHarness.HARNESS_ID
        const val NOTES_GROUP = "notes"
        const val DEFAULT_TTL_MS = 30_000L
        const val DEFAULT_TIMEOUT_MS = 20_000L
        const val DEFAULT_MAX_OUTPUT_BYTES = 1 shl 20 // 1 MiB; the real table is ~16 KB

        private val TOKEN = Regex("[A-Za-z0-9][A-Za-z0-9._:@+-]*")

        /** A model name may itself carry a `/` (a routing provider such as `commandcode` lists `deepseek/deepseek-v4-pro`). */
        private val MODEL = Regex("[A-Za-z0-9][A-Za-z0-9._:@+/-]*")
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * `pi --list-models` stdout -> `provider -> [provider/model]`, in printed order. Tolerant: rows before the
         * header (banners, warnings) are ignored, extra or missing trailing columns are fine, a row whose first
         * two cells are not plain tokens is skipped, and duplicates are dropped. No header = not the table = empty.
         */
        fun parseListModels(output: String): Map<String, List<String>> {
            val out = LinkedHashMap<String, LinkedHashSet<String>>()
            var inTable = false
            for (raw in output.lineSequence()) {
                val cells = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (cells.size < 2) continue
                if (!inTable) {
                    inTable = cells[0].equals("provider", ignoreCase = true) && cells[1].equals("model", ignoreCase = true)
                    continue
                }
                val (provider, model) = cells[0] to cells[1]
                if (!TOKEN.matches(provider) || !MODEL.matches(model)) continue
                out.getOrPut(provider) { LinkedHashSet() }.add("$provider/$model")
            }
            return out.mapValues { it.value.toList() }
        }

        /** `models-store.json` -> `provider -> [provider/id]`. Entries that are not the expected shape are skipped. */
        fun parseModelsStore(text: String): Map<String, List<String>> {
            val root = json.parseToJsonElement(text) as? JsonObject ?: return emptyMap()
            val out = LinkedHashMap<String, List<String>>()
            for ((provider, entry) in root) {
                if (!TOKEN.matches(provider)) continue
                val models = ((entry as? JsonObject)?.get("models") as? JsonArray) ?: continue
                val ids = models.mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                    .filter { MODEL.matches(it) }
                    .map { "$provider/$it" }
                    .distinct()
                if (ids.isNotEmpty()) out[provider] = ids
            }
            return out
        }

        fun defaultStoreFile(): File? =
            System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let { File(it, ".pi/agent/models-store.json") }

        /**
         * The home variables the probe child needs so it resolves [userHome] as its home: USERPROFILE on Windows,
         * HOME when it was inherited (Windows) or always (elsewhere). Only values that differ are returned, so a
         * production daemon, whose `user.home` is the real home, passes its environment through unchanged.
         */
        internal fun homeOverrides(inherited: Map<String, String>, userHome: String?, windows: Boolean): Map<String, String> {
            val home = userHome?.takeIf { it.isNotBlank() } ?: return emptyMap()
            val names = if (windows) listOfNotNull("USERPROFILE", "HOME".takeIf { it in inherited }) else listOf("HOME")
            return names.filter { inherited[it] != home }.associateWith { home }
        }

        /** The real listing: Pi's own launcher (see [PiCommand.resolveLauncher]) + `--list-models`, bounded. */
        fun runListModels(): String = runBounded(PiCommand.resolveLauncher() + "--list-models")

        /**
         * Runs [argv] as a child process (an argv list, never a shell), stdin closed, stderr discarded, and returns
         * its stdout. Refused, with the child (and its descendants) killed, when it outlives [timeoutMs], prints
         * more than [maxBytes], or the calling thread is interrupted. A non-zero exit is a failure.
         *
         * INSTALL-PIDCP-1: the child's home is the daemon's `user.home` (see [homeOverrides]). Pi loads every installed
         * extension even for `--list-models`, and extensions write under `os.homedir()` (pi-dcp creates `~/.pi-dcp`),
         * so an isolated daemon must not hand the child the inherited real home.
         */
        fun runBounded(argv: List<String>, timeoutMs: Long = DEFAULT_TIMEOUT_MS, maxBytes: Int = DEFAULT_MAX_OUTPUT_BYTES): String {
            val builder = ProcessBuilder(argv).redirectError(ProcessBuilder.Redirect.DISCARD)
            val env = builder.environment()
            env.putAll(homeOverrides(env, System.getProperty("user.home"), windows = File.separatorChar == '\\'))
            val process = builder.start()
            fun kill() {
                process.toHandle().descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
            }
            try {
                process.outputStream.close()
                val buffer = ByteArrayOutputStream()
                val overflow = java.util.concurrent.atomic.AtomicBoolean(false)
                val reader = Thread {
                    try {
                        val chunk = ByteArray(8192)
                        val input = process.inputStream
                        while (true) {
                            val n = input.read(chunk)
                            if (n < 0) break
                            if (buffer.size() + n > maxBytes) { overflow.set(true); kill(); break }
                            buffer.write(chunk, 0, n)
                        }
                    } catch (_: Exception) {
                        // Stream closed by kill(); the verdict comes from the flags below.
                    }
                }.apply { isDaemon = true; name = "pi-list-models-reader"; start() }

                val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!finished) {
                    kill()
                    throw IllegalStateException("timed out after ${timeoutMs}ms")
                }
                reader.join(2_000)
                if (overflow.get()) throw IllegalStateException("output exceeded $maxBytes bytes")
                val code = process.exitValue()
                if (code != 0) throw IllegalStateException("exited with code $code")
                return buffer.toString(Charsets.UTF_8)
            } catch (e: InterruptedException) {
                kill()
                Thread.currentThread().interrupt()
                throw IllegalStateException("cancelled")
            } finally {
                if (process.isAlive) kill()
            }
        }

        private fun reason(e: Exception): String = (e.message ?: e::class.simpleName.orEmpty()).take(200)
    }
}
