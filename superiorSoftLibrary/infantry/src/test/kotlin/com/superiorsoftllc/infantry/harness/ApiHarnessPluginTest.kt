// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.harness

import com.superiorsoftllc.infantry.harness.api.HARNESS_API_VERSION
import com.superiorsoftllc.infantry.harness.api.HarnessProcess
import com.superiorsoftllc.infantry.harness.HarnessProvider
import com.superiorsoftllc.infantry.harness.RunRequest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.superiorsoftllc.infantry.harness.api.Harness as ApiHarness
import com.superiorsoftllc.infantry.harness.api.HarnessCapability as ApiCapability
import com.superiorsoftllc.infantry.harness.api.HarnessEvent as ApiEvent
import com.superiorsoftllc.infantry.harness.api.HarnessPlugin as ApiPlugin
import com.superiorsoftllc.infantry.harness.api.HarnessRegistrar as ApiRegistrar
import com.superiorsoftllc.infantry.harness.api.HarnessRequest as ApiRequest
import com.superiorsoftllc.infantry.harness.api.HarnessTranslator as ApiTranslator

/** HARNESS-PLUGIN-3 H3: a harness API 2.0 plugin, from a jar below [HarnessApiClassLoader], through the 1.x registry seam. */
class ApiHarnessPluginTest {
    private val logs = CopyOnWriteArrayList<String>()
    private val failures = CopyOnWriteArrayList<PluginFailure>()
    private fun dir(): File = Files.createTempDirectory("harness-api").toFile().apply { deleteOnExit() }

    private fun host() = ModuleHarnessHost(
        ApiJarTestPlugin.PLUGIN_ID, dir(), HarnessHostDeclaration(), { true }, HarnessHostServices.NONE, { logs += it },
    )

    private fun source(jar: File) = JarHarnessPluginSource(
        { listOf(jar) },
        HarnessApiClassLoader(TestHarnessJars.hiding(ApiJarTestPlugin::class.java.name)),
        bridge = { ApiHarnessPluginBridge(it, host(), onFailure = { f -> failures += f }) },
    )

    private fun loaded(): HarnessRegistry {
        val registry = HarnessRegistry(onFailure = { failures += it })
        val report = registry.discover(source(TestHarnessJars.pack(File(dir(), "api.jar"), ApiJarTestPlugin::class.java)))
        assertEquals(listOf(ApiJarTestPlugin.HARNESS_ID), report.accepted.single().harnessIds, "report: $report")
        return registry
    }

    @Test fun `an API 2 plugin in a jar registers, gets its host and runs through the registry`() = runBlocking<Unit> {
        val registry = loaded()
        assertTrue(logs.any { it == "[harness ${ApiJarTestPlugin.PLUGIN_ID}] registered with api $HARNESS_API_VERSION" }, "logs: $logs")
        val harness = assertIs<ApiCliHarness>(registry.resolve(ApiJarTestPlugin.HARNESS_ID))
        assertEquals(setOf(HarnessCapability.MODEL_SELECTION), harness.capabilities)
        val events = registry.run(HarnessRequest("hi", ApiJarTestPlugin.HARNESS_ID, "proj")).toList()
        assertTrue(events.first().let { it is HarnessEvent.TextDelta && it.text.startsWith("api:") }, "events: $events")
        assertEquals(HarnessEvent.Completion(), events.last())
    }

    @Test fun `the plugin class loader sees the harness API and nothing else of the daemon`() {
        val harness = loaded().resolve(ApiJarTestPlugin.HARNESS_ID) as ApiCliHarness
        val loader = harness.api.javaClass.classLoader
        assertTrue(loader !== ApiJarTestPlugin::class.java.classLoader, "loaded from the jar")
        Class.forName(ApiPlugin::class.java.name, false, loader)
        val hidden = listOf(
            HarnessRegistry::class.java.name, HarnessPlugin::class.java.name, com.superiorsoftllc.infantry.runner.ProcessSpec::class.java.name,
            "com.superiorsoftllc.infantry.daemon.api.DaemonApi", "com.superiorsoftllc.infantry.session.SessionManager",
        )
        for (name in hidden) assertFailsWith<ClassNotFoundException>(name) { Class.forName(name, false, loader) }
        assertNull(loader.getResource("META-INF/services/${HarnessPlugin::class.java.name}"), "the built-ins' services file is not visible")
    }

    @Test fun `the session path drives it as a provider - argv, env and model come from the plugin`() {
        val provider = HarnessProvider(loaded().resolve(ApiJarTestPlugin.HARNESS_ID) as ApiCliHarness)
        val spec = provider.spec(RunRequest(prompt = "hi", model = "m1", workingDir = dir()))
        assertTrue(spec.command.first().replace('\\', '/').endsWith("/bin/java"), "spec: $spec")
        assertEquals("m1", spec.env["ECHO_MODEL"])
        assertNotNull(spec.workingDir)
    }

    @Test fun `a throwing plugin is isolated and attributed`() = runBlocking<Unit> {
        val registry = loaded()
        val harness = registry.resolve(ApiJarTestPlugin.HARNESS_ID) as ApiCliHarness
        val e = assertFailsWith<HarnessPluginCallException> { HarnessProvider(harness).spec(RunRequest(prompt = "boom")) }
        assertTrue("plugin '${ApiJarTestPlugin.PLUGIN_ID}'" in e.message!! && "echo process exploded" in e.message!!, e.message)
        val event = assertIs<HarnessEvent.Error>(harness.newTranslator().translate("boom").single())
        assertTrue("plugin '${ApiJarTestPlugin.PLUGIN_ID}'" in event.message && "translator exploded" in event.message, event.message)
        assertEquals(2, failures.count { it.plugin == ApiJarTestPlugin.PLUGIN_ID }, "both failures reported: $failures")
        // The registry and the harness itself are untouched: the next run works.
        assertEquals(listOf(ApiJarTestPlugin.HARNESS_ID), registry.harnessIds())
        assertIs<HarnessEvent.Completion>(registry.run(HarnessRequest("again", ApiJarTestPlugin.HARNESS_ID, "p")).toList().last())
    }

    private fun inline(version: String, id: String) = object : ApiPlugin {
        override val pluginId = id
        override val contractVersion = version
        override fun register(registrar: ApiRegistrar) = registrar.register(object : ApiHarness {
            override val id = "$id-h"
            override val capabilities = emptySet<ApiCapability>()
            override fun process(request: ApiRequest) = HarnessProcess(listOf("x"))
            override fun newTranslator() = object : ApiTranslator {
                override fun translate(line: String) = emptyList<ApiEvent>()
                override fun terminal(exitCode: Int, stderrTail: String): ApiEvent = ApiEvent.Completion()
            }
        })
    }

    @Test fun `the API version is semver - same major, minor not newer, anything else refused with a clear message`() {
        val registry = HarnessRegistry(onFailure = {})
        fun outcome(v: String, id: String) = registry.register(ApiHarnessPluginBridge(inline(v, id), host()))
        assertIs<PluginOutcome.Accepted>(outcome("2.0.0", "a"))
        assertIs<PluginOutcome.Accepted>(outcome("2.0.7", "b"))
        val major = assertIs<PluginOutcome.Refused>(outcome("3.0.0", "c"))
        assertEquals(RefusalReason.VERSION_MISMATCH, major.reason)
        assertTrue("implements harness API 3.0.0; this daemon implements $HARNESS_API_VERSION" in major.detail, major.detail)
        val minor = assertIs<PluginOutcome.Refused>(outcome("2.99.0", "d"))
        assertTrue("needs harness API 2.99.0" in minor.detail, minor.detail)
        val junk = assertIs<PluginOutcome.Refused>(outcome("1.6.0-ish", "e"))
        assertTrue("not a version" in junk.detail, junk.detail)
        // A 1.x plugin declaring 2.0.0 is still refused: the 1.x path stays exact-match.
        val old = registry.register(object : HarnessPlugin {
            override val pluginId = "old"
            override val contractVersion = HARNESS_API_VERSION
            override fun register(registrar: HarnessRegistrar) {}
        })
        assertIs<PluginOutcome.Refused>(old)
        assertEquals(listOf("a-h", "b-h"), registry.harnessIds())
    }

    @Test fun `every API capability has a daemon twin of the same name`() {
        val daemon = HarnessCapability.entries.map { it.name }.toSet()
        assertTrue(ApiCapability.entries.all { it.name in daemon })
    }
}
