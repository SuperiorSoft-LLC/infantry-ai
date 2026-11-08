// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin

import com.superiorsoftllc.infantry.plugin.api.CapabilityCallFrame
import com.superiorsoftllc.infantry.plugin.api.CapabilityWire
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/** PLUGIN-APPLY-2: a second call after a module-set swap still gets its answer (the host is not torn down by a swap). */
class ClientCapabilityHostSwapTest {
    @Test
    fun `calls before and after a module set change are both answered`() = runBlocking {
        val frames = MutableSharedFlow<Envelope>(extraBufferCapacity = 16)
        val sets = MutableStateFlow(emptyList<InfantryModule>())
        val results = ArrayList<String>()
        val two = CompletableDeferred<Unit>()
        val job = launch {
            ClientCapabilityHost.run(sets, "desktop", "t", frames,
                send = { e -> if (e.type == CapabilityWire.RESULT) { results.add(e.data); if (results.size == 2) two.complete(Unit) }; true },
                trySend = { true })
        }
        suspend fun call(id: String) { frames.subscriptionCount.value; frames.emit(Envelope(CapabilityWire.CALL, CapabilityWire.encode(CapabilityCallFrame(id, "plugins.apply")))) }
        while (frames.subscriptionCount.value == 0) kotlinx.coroutines.yield()
        call("a")
        sets.value = emptyList<InfantryModule>() + emptyList() // same content: a swap builds a new, equal list
        kotlinx.coroutines.yield()
        call("b")
        withTimeout(5000) { two.await() }
        assertEquals(2, results.size)
        job.cancel()
    }
}
