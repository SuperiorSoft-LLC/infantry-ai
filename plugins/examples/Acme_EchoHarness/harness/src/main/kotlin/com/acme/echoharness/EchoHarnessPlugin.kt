package com.acme.echoharness

import com.superiorsoftllc.agenthq.harness.api.ContextLimitCapability
import com.superiorsoftllc.agenthq.harness.api.ContextLimitSupport
import com.superiorsoftllc.agenthq.harness.api.EffortCapability
import com.superiorsoftllc.agenthq.harness.api.EffortSupport
import com.superiorsoftllc.agenthq.harness.api.HARNESS_API_VERSION
import com.superiorsoftllc.agenthq.harness.api.Harness
import com.superiorsoftllc.agenthq.harness.api.HarnessCapability
import com.superiorsoftllc.agenthq.harness.api.HarnessEvent
import com.superiorsoftllc.agenthq.harness.api.HarnessHost
import com.superiorsoftllc.agenthq.harness.api.HarnessPlugin
import com.superiorsoftllc.agenthq.harness.api.HarnessProcess
import com.superiorsoftllc.agenthq.harness.api.HarnessRegistrar
import com.superiorsoftllc.agenthq.harness.api.HarnessRequest
import com.superiorsoftllc.agenthq.harness.api.HarnessTranslator
import java.io.File

/**
 * HARNESS-PLUGIN-3: the smallest real harness plugin. Registers one harness, `echo`, whose agent process is [EchoCli] run in
 * the daemon's own Java with this jar as its class path. It declares one setting (`echo.prefix`) and reads it through its
 * [HarnessHost]; it calls nothing else of the daemon.
 */
class EchoHarnessPlugin : HarnessPlugin {
    override val pluginId = "Acme_EchoHarness"
    override val contractVersion = HARNESS_API_VERSION

    override fun register(registrar: HarnessRegistrar) {
        registrar.host.log("echo harness registering")
        registrar.register(EchoHarness(registrar.host))
    }
}

class EchoHarness(private val host: HarnessHost) : Harness {
    override val id = "echo"
    override val capabilities = setOf(HarnessCapability.SESSION_RESUME)

    /** Harness API 2.1 (CONTEXT-LIMIT-1): the plugin path for a context limit. Echo "applies" it by printing it back. */
    override val contextLimit = ContextLimitCapability(ContextLimitSupport.SUPPORTED, "echo prints the limit it was given (ECHO_CONTEXT_LIMIT)")

    /** Harness API 2.2 (EFFORT-1): echo "applies" the reasoning effort by printing it back (ECHO_EFFORT). */
    override val effort = EffortCapability(EffortSupport.SUPPORTED, "echo prints the effort level it was given (ECHO_EFFORT)")

    private val jar: File by lazy { File(EchoHarness::class.java.protectionDomain.codeSource.location.toURI()) }

    override fun process(request: HarnessRequest): HarnessProcess {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val env = host.setting("echo.prefix")?.let { mapOf("ECHO_PREFIX" to it) }.orEmpty() +
            request.contextLimit?.let { mapOf("ECHO_CONTEXT_LIMIT" to it.toString()) }.orEmpty() +
            request.effort?.let { mapOf("ECHO_EFFORT" to it) }.orEmpty()
        return HarnessProcess(listOf(java, "-cp", jar.path, EchoCli::class.java.name), env = env)
    }

    override fun newTranslator(): HarnessTranslator = EchoTranslator()
}

class EchoTranslator : HarnessTranslator {
    override fun translate(line: String): List<HarnessEvent> =
        if (line.startsWith("session: ")) listOf(HarnessEvent.SessionStarted(line.removePrefix("session: ").trim()))
        else listOf(HarnessEvent.TextDelta(line + "\n"))

    override fun terminal(exitCode: Int, stderrTail: String): HarnessEvent =
        if (exitCode == 0) HarnessEvent.Completion() else HarnessEvent.Error("echo exited $exitCode: ${stderrTail.take(200)}")
}
