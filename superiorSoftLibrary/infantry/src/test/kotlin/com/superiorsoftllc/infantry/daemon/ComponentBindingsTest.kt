package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.daemon.api.orchestrator.LaunchVerdict
import com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorLink
import com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorReply
import com.superiorsoftllc.infantry.daemon.api.orchestrator.TicketRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ComponentBindingsTest {
    private class FakeLink : OrchestratorLink {
        override fun reviewReport(from: String, body: String): OrchestratorReply<String?> = OrchestratorReply.Ok(null)
        override fun ticketOf(ticketId: String): OrchestratorReply<TicketRef?> = OrchestratorReply.Ok(null)
        override fun starterOf(workerSessionId: String): OrchestratorReply<String?> = OrchestratorReply.Ok(null)
        override fun launchDecision(provider: String, unattended: Boolean, ticketId: String?): OrchestratorReply<LaunchVerdict> =
            OrchestratorReply.Ok(LaunchVerdict.Allow)
    }

    @Test fun `default is local for every component and builds the local one`() {
        val b = ComponentBindings({ null }, { null })
        b.verifyAll()
        CoreComponent.entries.forEach { assertEquals("local", b.providerId(it)) }
        val local = FakeLink()
        assertSame(local, b.resolve(CoreComponent.ORCHESTRATOR_LINK, OrchestratorLink::class.java) { local })
    }

    @Test fun `a swapped test provider is resolved instead of local`() {
        val fake = FakeLink()
        val p = ComponentProviders().also { it.offer("test", CoreComponent.ORCHESTRATOR_LINK, "fake", fake) }
        val b = ComponentBindings({ if (it == "core.orchestratorLink.provider") "fake" else null }, { p })
        b.verifyAll()
        assertSame(fake, b.resolve(CoreComponent.ORCHESTRATOR_LINK, OrchestratorLink::class.java) { error("local must not be built") })
    }

    @Test fun `an unknown provider id is refused with the setting named`() {
        val b = ComponentBindings({ if (it == "core.mailbox.provider") "nope" else null }, { ComponentProviders() })
        val e = assertFailsWith<UnknownComponentProviderException> { b.verifyAll() }
        assertTrue("core.mailbox.provider" in e.message!! && "'nope'" in e.message!!, e.message)
    }
}
