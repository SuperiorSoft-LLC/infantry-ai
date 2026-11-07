// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugin.host

import com.superiorsoftllc.infantry.plugin.api.AudioCapture
import com.superiorsoftllc.infantry.plugin.api.CaptureEnd
import com.superiorsoftllc.infantry.plugin.api.CaptureHandle
import com.superiorsoftllc.infantry.plugin.api.CaptureOptions
import com.superiorsoftllc.infantry.plugin.api.Events
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.ModuleSetting
import com.superiorsoftllc.infantry.plugin.api.ModulesConfig
import com.superiorsoftllc.infantry.plugin.api.Notifications
import com.superiorsoftllc.infantry.plugin.api.PluginEvent
import com.superiorsoftllc.infantry.plugin.api.Services
import com.superiorsoftllc.infantry.plugin.api.SpeakOptions
import com.superiorsoftllc.infantry.plugin.api.SpeechOutput
import com.superiorsoftllc.infantry.plugin.api.Tier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private object NoMic : AudioCapture {
    override fun start(options: CaptureOptions, onChunk: (ByteArray) -> Unit, onEnd: (CaptureEnd) -> Unit): CaptureHandle = error("unused")
}

/** PLUGINS-1: services, routes and events are each gated by the module's declarations and the owner's say. */
class ServiceGateTest {
    private val manifest = ModuleManifest(
        "Acme_Hello", "1.0.0", author = "Acme", license = "NOASSERTION",
        services = listOf("notifications", "events", "daemon"), events = listOf(Events.TAB_OPENED), routes = listOf("GET /health", "POST /tickets"),
    )

    private fun status(tier: Tier) = ModuleStatus(manifest.name, tier, ModuleState.LOADED, manifest = manifest)

    @Test
    fun `a service must be declared, not denied, and for community allowed`() {
        val config = ModulesConfig(modules = mapOf("Acme_Hello" to ModuleSetting(allow = listOf("notifications"), deny = listOf("events"))))
        assertNull(ServiceGate.refusal("notifications", manifest, Tier.COMMUNITY, config))
        assertEquals("not declared in module.json", ServiceGate.refusal("storage", manifest, Tier.COMMUNITY, config))
        assertEquals("denied in modules.json", ServiceGate.refusal("events", manifest, Tier.CORE, config))
        assertEquals("a community module needs it allowed in modules.json", ServiceGate.refusal("daemon", manifest, Tier.COMMUNITY, config))
        assertNull(ServiceGate.refusal("daemon", manifest, Tier.CORE, config), "core has what it declares")
    }

    @Test
    fun `the registry hands out only gated services`() {
        val log = mutableListOf<String>()
        val registry = ServiceRegistry(ModulesConfig(modules = mapOf("Acme_Hello" to ModuleSetting(allow = listOf("notifications")))), log::add)
        registry.provide(Services.NOTIFICATIONS) { Notifications { _, _ -> } }
        registry.provideEvents(SimpleEventBus())
        val services = registry.forModule(status(Tier.COMMUNITY))
        assertNotNull(services.get(Services.NOTIFICATIONS))
        assertNull(services.get(Services.EVENTS))
        assertTrue(log.any { "service events refused" in it })
    }

    @Test
    fun `the microphone needs the declaration and the owner's grant for every tier but core`() {
        val mic = manifest.copy(services = manifest.services + Services.AUDIO_IN.id + Services.SPEECH_OUT.id)
        val none = ModulesConfig()
        val granted = ModulesConfig(modules = mapOf("Acme_Hello" to ModuleSetting(allow = listOf(Services.AUDIO_IN.id))))
        assertEquals("not declared in module.json", ServiceGate.refusal(Services.AUDIO_IN.id, manifest, Tier.OFFICIAL, granted))
        assertEquals("a sensitive service needs the owner's grant (allow in modules.json)", ServiceGate.refusal(Services.AUDIO_IN.id, mic, Tier.OFFICIAL, none))
        assertNull(ServiceGate.refusal(Services.AUDIO_IN.id, mic, Tier.OFFICIAL, granted))
        assertNull(ServiceGate.refusal(Services.AUDIO_IN.id, mic, Tier.COMMUNITY, granted))
        assertNull(ServiceGate.refusal(Services.AUDIO_IN.id, mic, Tier.CORE, none), "compiled-in core modules are the app itself")
        assertNull(ServiceGate.refusal(Services.SPEECH_OUT.id, mic, Tier.OFFICIAL, none), "speech out is not sensitive")
        assertTrue(ServiceGate.sensitive("audio.in"))
        assertFalse(ServiceGate.sensitive("speech.out"))

        val registry = ServiceRegistry(none)
        registry.provide(Services.AUDIO_IN) { NoMic }
        registry.provide(Services.SPEECH_OUT) { object : SpeechOutput {
            override fun speak(text: String, options: SpeakOptions) = true
            override fun stop() {}
            override fun voices() = emptyList<String>()
        } }
        val official = registry.forModule(ModuleStatus(mic.name, Tier.OFFICIAL, ModuleState.LOADED, manifest = mic))
        assertNull(official.get(Services.AUDIO_IN), "ungranted")
        assertNotNull(official.get(Services.SPEECH_OUT))
        val undeclared = ServiceRegistry(granted).also { it.provide(Services.AUDIO_IN) { error("never built") } }
            .forModule(ModuleStatus(manifest.name, Tier.OFFICIAL, ModuleState.LOADED, manifest = manifest))
        assertNull(undeclared.get(Services.AUDIO_IN), "undeclared")
        val grantedServices = ServiceRegistry(granted).also { r -> r.provide(Services.AUDIO_IN) { NoMic } }
            .forModule(ModuleStatus(mic.name, Tier.OFFICIAL, ModuleState.LOADED, manifest = mic))
        assertNotNull(grantedServices.get(Services.AUDIO_IN))
    }

    @Test
    fun `a module sees only the events it declared`() {
        val bus = SimpleEventBus()
        val gated = GatedEventBus(bus, "Acme_Hello", listOf(Events.TAB_OPENED))
        val got = mutableListOf<String>()
        gated.subscribe(Events.TAB_OPENED) { got += it.data.getValue("id") }
        gated.subscribe(Events.SESSION_SELECTED) { got += "never" }
        bus.publish(PluginEvent(Events.TAB_OPENED, mapOf("id" to "infantry.git")))
        bus.publish(PluginEvent(Events.SESSION_SELECTED))
        assertEquals(listOf("infantry.git"), got)
    }

    @Test
    fun `an observer that throws does not stop the others`() {
        val bus = SimpleEventBus()
        var reached = false
        bus.subscribe("x") { error("boom") }
        bus.subscribe("x") { reached = true }
        bus.publish(PluginEvent("x"))
        assertTrue(reached)
    }
}
