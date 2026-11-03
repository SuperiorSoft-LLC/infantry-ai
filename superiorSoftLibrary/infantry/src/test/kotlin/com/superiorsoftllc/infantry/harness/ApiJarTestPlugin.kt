package com.superiorsoftllc.infantry.harness

import com.superiorsoftllc.infantry.harness.api.HARNESS_API_VERSION
import com.superiorsoftllc.infantry.harness.api.Harness
import com.superiorsoftllc.infantry.harness.api.HarnessCapability
import com.superiorsoftllc.infantry.harness.api.HarnessEvent
import com.superiorsoftllc.infantry.harness.api.HarnessPlugin
import com.superiorsoftllc.infantry.harness.api.HarnessProcess
import com.superiorsoftllc.infantry.harness.api.HarnessRegistrar
import com.superiorsoftllc.infantry.harness.api.HarnessRequest
import com.superiorsoftllc.infantry.harness.api.HarnessTranslator

/**
 * HARNESS-PLUGIN-3 H3: a harness API 2.0 plugin [ApiHarnessPluginTest] packs into a jar at test time and loads below a
 * [HarnessApiClassLoader] (its parent hides every `ApiJarTestPlugin*` class). Not in the test META-INF/services. A prompt
 * of `boom` makes [Echo.process] throw and a line `boom` makes the translator throw (the isolation tests). It logs one
 * line through the host it was given, so a test sees the host reached it.
 */
class ApiJarTestPlugin : HarnessPlugin {
    override val pluginId = PLUGIN_ID
    override val contractVersion = HARNESS_API_VERSION

    override fun register(registrar: HarnessRegistrar) {
        registrar.host.log("registered with api ${registrar.host.apiVersion}")
        registrar.register(Echo())
    }

    class Echo : Harness {
        override val id = HARNESS_ID
        override val capabilities = setOf(HarnessCapability.MODEL_SELECTION)
        override fun process(request: HarnessRequest): HarnessProcess {
            if (request.prompt == "boom") throw IllegalStateException("echo process exploded")
            return HarnessProcess(listOf(System.getProperty("java.home") + "/bin/java", "--version"), env = mapOf("ECHO_MODEL" to request.model.orEmpty()))
        }
        override fun newTranslator(): HarnessTranslator = Translator()
    }

    class Translator : HarnessTranslator {
        override fun translate(line: String): List<HarnessEvent> {
            if (line == "boom") throw IllegalArgumentException("translator exploded")
            return listOf(HarnessEvent.TextDelta("api:$line"))
        }
        override fun terminal(exitCode: Int, stderrTail: String): HarnessEvent =
            if (exitCode == 0) HarnessEvent.Completion() else HarnessEvent.Error("exit $exitCode")
    }

    companion object {
        const val PLUGIN_ID = "Acme_ApiJarTest"
        const val HARNESS_ID = "api-jar-echo"
    }
}
