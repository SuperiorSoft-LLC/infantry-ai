package com.superiorsoftllc.agenthq.harness

import com.superiorsoftllc.agenthq.runner.ProcessListener
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/**
 * The part of a harness that is the same whatever the vendor: run one CLI child per request, write the prompt to
 * stdin and close it (these CLIs emit nothing until EOF), and hand every stdout line to the [HarnessTranslator]
 * THE MOMENT it is read. When the child exits, exactly one terminal event follows, chosen by the translator, or an
 * Error if the child could not be started at all. A failed run is therefore an EVENT, never an exception the
 * caller might not catch and never silence. Cancelling the collector kills the child tree.
 *
 * Every event a subclass emits goes through [translatorFactory]; [events] is final so a subclass cannot route
 * around it. What a subclass supplies is only what is genuinely vendor-specific: [id], [capabilities], the
 * [specFor] that builds the process, and the translator.
 *
 * [cliName] is only for the harness-authored error texts ("could not start <cliName>").
 */
abstract class CliHarness(
    private val runner: ProcessRunner,
    private val specFor: (HarnessRequest) -> ProcessSpec,
    private val translatorFactory: () -> HarnessTranslator,
    private val cliName: String,
) : Harness {
    /** WIRE-4: the process this harness would run for [request], so a host can drive it as an argv provider. */
    fun processSpec(request: HarnessRequest): ProcessSpec = specFor(request)

    /** WIRE-4: a fresh translator, so a host can read a stdout line the way [events] does. */
    fun newTranslator(): HarnessTranslator = translatorFactory()

    /**
     * BUG-M2-11b-1 (harness contract 1.4.0): this harness's persistent-mode protocol, for the daemon's session host.
     * Null (the default) = the harness has no persistent mode, and the host runs it one-shot.
     */
    open fun sessionAdapter(): com.superiorsoftllc.agenthq.session.host.HarnessSessionAdapter? = null

    /**
     * HARNESS-CARRY-1 (harness contract 1.6.0): how this harness's first run after a provider switch takes over the
     * conversation. Default: [ConversationCarry.REPLAY]. A harness with a native import overrides it.
     */
    open val conversationCarry: ConversationCarry get() = ConversationCarry.REPLAY

    final override fun events(request: HarnessRequest): Flow<HarnessEvent> = callbackFlow {
        val translator = translatorFactory()
        val stderrTail = ArrayDeque<String>()

        val listener = object : ProcessListener {
            override fun onStdout(line: String) {
                for (event in translator.translate(line)) trySend(event)
            }

            override fun onStderr(line: String) {
                synchronized(stderrTail) {
                    stderrTail.addLast(line)
                    while (stderrTail.size > STDERR_LINES) stderrTail.removeFirst()
                }
            }

            override fun onExit(exitCode: Int) {
                val tail = synchronized(stderrTail) { stderrTail.joinToString("\n") }.take(STDERR_CHARS)
                trySend(translator.terminal(exitCode, tail))
                channel.close()
            }
        }

        val process = try {
            runner.start(specFor(request), listener)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            trySend(HarnessEvent.Error("could not start $cliName: ${e.message ?: e::class.simpleName}"))
            channel.close()
            null
        }

        if (process != null) {
            try {
                process.writeInput(request.prompt)
                process.closeInput()
            } catch (e: Exception) {
                process.killTree()
                trySend(HarnessEvent.Error("could not send the prompt to $cliName: ${e.message ?: e::class.simpleName}"))
                channel.close()
            }
        }

        awaitClose { if (process != null && process.isAlive) process.killTree() }
    }.buffer(Channel.UNLIMITED) // trySend must never drop an event because the collector is slow

    private companion object {
        const val STDERR_LINES = 20
        const val STDERR_CHARS = 1000
    }
}
