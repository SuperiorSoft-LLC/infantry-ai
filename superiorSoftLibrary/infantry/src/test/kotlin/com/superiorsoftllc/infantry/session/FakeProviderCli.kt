// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session

/**
 * A standalone child process used by [SessionProcessIntegrationTest].
 *
 * It stands in for a one-shot provider CLI so the integration test can exercise the REAL
 * [com.superiorsoftllc.infantry.runner.PipedProcessRunner] and real OS process facts (spawn, stdin
 * EOF, pid, process-tree kill) without a model or a network:
 *
 *  - it reads stdin to EOF (as `opencode run` / `claude -p` do);
 *  - it prints one JSON line carrying its session id, which is the id given by `--session` when
 *    resuming and a fresh one otherwise -- so "resume" is observable as the same id coming back;
 *  - it echoes the prompt, so the test can prove the prompt reached it;
 *  - if the prompt contains `SLEEP` it then blocks, so a kill has a live process to terminate.
 */
fun main(args: Array<String>) {
    val resumeId = args.toList().windowed(2).firstOrNull { it[0] == "--session" }?.get(1)
    val prompt = System.`in`.readBytes().toString(Charsets.UTF_8)
    val sessionId = resumeId ?: ("sess-" + java.util.UUID.randomUUID().toString().replace("-", "").take(8))
    val promptJson = prompt.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
    println("{\"session_id\":\"$sessionId\",\"type\":\"run\",\"prompt\":\"$promptJson\"}")
    System.out.flush()
    if (prompt.contains("SLEEP")) {
        Thread.sleep(120_000)
    }
    println("{\"type\":\"done\",\"session_id\":\"$sessionId\"}")
    System.out.flush()
}
