// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.perf

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * PERF-1: the streaming stub harness. The daemon runs it from a harness descriptor exactly as it runs a real CLI agent
 * (`java @<argfile> PerfStubHarness --records <dir> --lines N --pace-ms M`, prompt on stdin, stdout read as `jsonl`).
 *
 * It reports a session id, records the env it was given (so the profiler can prove INFANTRY_URL is the temp daemon),
 * streams [lines] text records of ~120 characters, writes `<session>.done`, and exits. No provider is ever called.
 */
object PerfStubHarness {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.toList().windowed(2, 2).associate { (k, v) -> k to v }
        val records = File(options.getValue("--records")).apply { mkdirs() }
        val lines = options["--lines"]?.toInt() ?: 2_000
        val paceMs = options["--pace-ms"]?.toLong() ?: 5L
        emit("session", "id" to "perf-${UUID.randomUUID()}")
        drainPrompt()
        val session = System.getenv("INFANTRY_SESSION_ID")?.takeIf { it.isNotBlank() } ?: "no-session-${ProcessHandle.current().pid()}"
        Properties().apply {
            setProperty("sessionId", session)
            setProperty("INFANTRY_URL", System.getenv("INFANTRY_URL").orEmpty())
            setProperty("cwd", File("").absoluteFile.path)
            setProperty("user.home", System.getProperty("user.home"))
        }.let { p -> File(records, "$session.env.properties").outputStream().use { p.store(it, "PERF-1 stub env") } }

        val filler = "the quick brown fox jumps over the lazy dog ".repeat(3)
        for (i in 1..lines) {
            emit("text", "text" to "perf line $i/$lines $filler\n")
            if (paceMs > 0) Thread.sleep(paceMs)
        }
        File(records, "$session.done").writeText(lines.toString())
        exitProcess(0)
    }

    /** Reads the prompt the daemon writes to stdin until EOF or 3 s, so the daemon's write never blocks. */
    private fun drainPrompt() {
        val buffer = ByteArrayOutputStream()
        thread(isDaemon = true) {
            runCatching {
                val chunk = ByteArray(8192)
                while (true) {
                    val n = System.`in`.read(chunk)
                    if (n < 0) break
                    buffer.write(chunk, 0, n)
                }
            }
        }.join(3_000)
    }

    private fun emit(kind: String, vararg fields: Pair<String, String>) {
        println(buildJsonObject {
            put("kind", kind)
            fields.forEach { (k, v) -> put(k, v) }
        })
        System.out.flush()
    }
}
