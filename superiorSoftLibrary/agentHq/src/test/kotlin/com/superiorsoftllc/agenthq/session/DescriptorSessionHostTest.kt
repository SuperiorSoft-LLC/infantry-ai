package com.superiorsoftllc.agenthq.session

import com.superiorsoftllc.agenthq.harness.descriptor.DescriptorHarness
import com.superiorsoftllc.agenthq.harness.descriptor.EchoCliFixture
import com.superiorsoftllc.agenthq.harness.descriptor.HarnessDescriptor
import com.superiorsoftllc.agenthq.harness.HarnessProvider
import com.superiorsoftllc.agenthq.runner.ScriptedRunner
import com.superiorsoftllc.agenthq.runner.StubScript
import com.superiorsoftllc.agenthq.session.host.LifecyclePolicy
import com.superiorsoftllc.agenthq.session.host.OneShotAdapter
import com.superiorsoftllc.agenthq.session.host.selectAdapter
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * BUG-M2-11e (plan section 3.2 / 6.4): a config-file (descriptor) harness has no persistent mode, so it goes through the
 * same SessionHost as every harness on the one-shot fallback, with keep-alive ON as well as OFF. No harness-specific code:
 * the descriptor provider is a plain [HarnessProvider] over a [DescriptorHarness], and the host picks its adapter.
 * Two prompts give ONE `* session started` chat line naming the descriptor's id, no per-turn plumbing in chat (it is in
 * Activity, where nothing shows in a console) and no raw `exit` frame, because no hosted process ended unasked.
 */
class DescriptorSessionHostTest {
    private val events: MutableList<SessionEvent> = Collections.synchronizedList(mutableListOf())

    private val script = StubScript(
        name = "echo-cli",
        stdoutLines = listOf(
            """{"kind":"hello","sid":"echo-session-1"}""",
            """{"kind":"say","text":"echo: hi"}""",
            """{"kind":"done","why":"stop"}""",
        ),
    )

    private fun descriptorProvider(): HarnessProvider {
        // The descriptor's argv names a source file the scripted runner never starts; only the lifecycle is under test.
        val entry = Json.parseToJsonElement(EchoCliFixture.descriptorJson("echo-cli", File("EchoCli.java")))
        return HarnessProvider(DescriptorHarness(HarnessDescriptor.parse(entry), ScriptedRunner(script, delayMs = 0)))
    }

    private fun manager(policy: LifecyclePolicy): SessionManager {
        val file = File.createTempFile("agenthq-desc-host", ".json").also { it.delete() }
        return SessionManager(
            descriptorProvider(), ScriptedRunner(script, delayMs = 0), JsonSessionStore(file),
            lifecyclePolicy = { policy },
        ).also { it.eventSink = { e -> events += e } }
    }

    private fun awaitFinished(count: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (synchronized(events) { events.count { it is SessionEvent.Exit } } < count) {
            if (System.currentTimeMillis() > deadline) fail("turn never finished: $events")
            Thread.sleep(10)
        }
    }

    private fun chat(): List<String> = synchronized(events) { events.filterIsInstance<SessionEvent.Stderr>().map { it.line } }
    private fun activity(): List<String> = synchronized(events) { events.filterIsInstance<SessionEvent.Activity>().map { it.detail } }

    @Test
    fun aDescriptorHarnessHasNoPersistentModeSoTheHostPicksOneShotEvenWithKeepAliveOn() {
        val provider = descriptorProvider()
        assertNull(provider.sessionAdapter(), "a descriptor declares no persistent mode")
        assertIs<OneShotAdapter>(selectAdapter(provider, LifecyclePolicy.DEFAULT), "keep-alive on: still the one-shot fallback")
        assertIs<OneShotAdapter>(selectAdapter(provider, LifecyclePolicy(0)), "keep-alive off: the one-shot path")
    }

    @Test
    fun twoPromptsOnADescriptorSession_withKeepAliveOn_goThroughTheHost_oneBootLine_noExitFrame() {
        assertOneBootLine(manager(LifecyclePolicy.DEFAULT))
    }

    @Test
    fun twoPromptsOnADescriptorSession_withKeepAliveOff_goThroughTheHost_oneBootLine_noExitFrame() {
        assertOneBootLine(manager(LifecyclePolicy(0)))
    }

    private fun assertOneBootLine(manager: SessionManager) {
        val first = manager.startNew("hello")
        awaitFinished(1)
        manager.resume(first.id, "again")
        awaitFinished(2)

        val chat = chat()
        val boots = chat.filter { it.contains("*") }
        assertEquals(1, boots.size, "one boot line for two one-shot turns: $chat")
        assertTrue(boots.single().startsWith("[agenthq] * session started (echo-cli"), "the line names the descriptor: $boots")
        assertTrue(chat.none { it.startsWith("[agenthq] starting ") || it.startsWith("[agenthq] process started") }, "$chat")

        val rawExits = synchronized(events) { events.filterIsInstance<SessionEvent.Exit>().filter { it.processExited && it.code != null } }
        assertEquals(emptyList(), rawExits, "a one-shot turn's end is `finished`, never a raw `exit` frame")

        val activity = activity()
        assertEquals(2, activity.count { it.startsWith("process started") }, "each turn's child is plumbing in Activity: $activity")
        assertTrue(activity.any { it == "resuming provider session echo-session-1" }, "the descriptor's session mapping resumed: $activity")
    }
}
