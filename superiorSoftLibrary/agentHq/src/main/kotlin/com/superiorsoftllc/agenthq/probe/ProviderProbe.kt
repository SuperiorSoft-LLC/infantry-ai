package com.superiorsoftllc.agenthq.probe

import com.superiorsoftllc.agenthq.session.InfantryEnv

import com.superiorsoftllc.agenthq.provider.ProviderFactory
import com.superiorsoftllc.agenthq.provider.RunRequest
import com.superiorsoftllc.agenthq.runner.ProcessListener
import com.superiorsoftllc.agenthq.runner.RunnerFactory

/**
 * Phase 0/2 comparison harness: run ONE prompt through a provider + runner and print a compact
 * summary. Deliberately NOT part of the daemon -- it exists so `opencode` and `claude` can be
 * compared on identical terms (same [ProcessListener], same stdin-EOF delivery, same runner):
 *
 *   .\gradlew.bat :superiorSoftLibrary:agentHq:probeProvider
 *   (env) AGENTHQ_PROVIDER=opencode|claude  AGENTHQ_CLAUDE_MODEL=haiku  ...
 *
 * It reports exit code, line counts, the provider session id and elapsed time -- the Phase 0
 * dimensions (does pipes+stdin-EOF drive the CLI, what lands on stdout vs stderr, is a PTY needed).
 */
fun main(args: Array<String>) {
    val provider = ProviderFactory.fromEnv()
    val runner = RunnerFactory.fromFlag()
    val request = RunRequest(
        prompt = args.joinToString(" ").ifBlank { "Reply with exactly the single word: pong" },
        resumeSessionId = InfantryEnv.get("AGENTHQ_RESUME")?.takeIf { it.isNotBlank() },
    )
    val spec = provider.spec(request)

    println("[probe] provider=${provider.id} runner=${runner.id}")
    println("[probe] argv=${spec.command.joinToString(" ")}")

    var stdoutLines = 0
    var stderrLines = 0
    var firstStderr: String? = null
    var sessionId: String? = null
    val startedAt = System.currentTimeMillis()

    val listener = object : ProcessListener {
        override fun onStdout(line: String) {
            stdoutLines++
            provider.sessionIdFromLine(line)?.let { sessionId = it }
        }

        override fun onStderr(line: String) {
            stderrLines++
            if (firstStderr == null) firstStderr = line
        }

        override fun onExit(exitCode: Int) = Unit
    }

    val process = runner.start(spec, listener)
    provider.deliver(process, request)
    val exit = process.awaitExit()
    val elapsed = System.currentTimeMillis() - startedAt

    println(
        "[probe] exit=$exit stdoutLines=$stdoutLines stderrLines=$stderrLines " +
            "session=$sessionId elapsedMs=$elapsed",
    )
    firstStderr?.let { println("[probe] firstStderr=${it.take(160)}") }
}
