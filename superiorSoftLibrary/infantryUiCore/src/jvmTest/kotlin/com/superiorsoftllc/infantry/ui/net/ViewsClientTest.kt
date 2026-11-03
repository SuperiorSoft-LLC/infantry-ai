// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.net

import com.superiorsoftllc.infantry.plugin.api.ViewOrigin
import com.superiorsoftllc.infantry.plugin.api.ViewState
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** DV-2: the views service against a fake daemon link (frames in, frames out). */
class ViewsClientTest {
    private val sent = mutableListOf<Envelope>()
    private var toolCall: Triple<String, JsonObject, JsonObject?>? = null
    private val client = ViewsClient(send = { synchronized(sent) { sent += it }; true }, tool = { n, a, o -> toolCall = Triple(n, a, o); Result.success("ok") })
    private val none = JsonObject(emptyMap())

    private fun sentOf(type: String) = synchronized(sent) { sent.filter { it.type == type } }
    private suspend fun await(cond: () -> Boolean) = withTimeout(5_000) { while (!cond()) delay(5) }
    private fun page(req: Envelope, rev: Long, text: String = "x") = Envelope("plugin_view_result",
        """{"module":"m","view":"v","params":{},"rev":$rev,"page":{"t":"$text"}}""", requestId = req.requestId)

    @Test fun listAnswers() = runBlocking {
        val r = launch {
            await { sentOf("plugin_views").isNotEmpty() }
            client.onFrame(Envelope("plugin_views_result", """{"views":[{"module":"m","id":"v","title":"V","place":"tab"}]}""", requestId = sentOf("plugin_views").first().requestId))
        }
        val views = client.list()
        r.join()
        assertEquals(listOf("m/v/V/tab"), views.map { "${it.module}/${it.id}/${it.title}/${it.place}" })
    }

    @Test fun openChangedOlderRevCancelAndReopen() = runBlocking {
        val states = mutableListOf<ViewState>()
        val collector = launch { client.open("m", "v", none).toList(states) }
        await { sentOf("plugin_view").size == 1 }
        assertTrue(sentOf("plugin_view").first().data.contains("\"subscribe\":true"))
        client.onFrame(page(sentOf("plugin_view")[0], 5))
        await { states.any { it is ViewState.Page } }
        assertEquals(ViewState.Loading, states.first())
        // changed -> re-fetch; an older rev answer is dropped
        client.onFrame(Envelope("plugin_view_changed", """{"module":"m","view":"v","rev":6}"""))
        await { sentOf("plugin_view").size == 2 }
        client.onFrame(page(sentOf("plugin_view")[1], 4, "old"))
        client.onFrame(Envelope("plugin_view_changed", """{"module":"other","view":"v"}"""))
        delay(100)
        assertEquals(2, sentOf("plugin_view").size, "a change of another module re-fetches nothing")
        assertEquals(1, states.count { it is ViewState.Page }, "older rev dropped")
        // reconnect: re-open asks again and accepts a restarted (lower) rev
        client.reopen()
        await { sentOf("plugin_view").size == 3 }
        client.onFrame(page(sentOf("plugin_view")[2], 1, "fresh"))
        await { states.count { it is ViewState.Page } == 2 }
        assertEquals(1L, (states.last() as ViewState.Page).rev)
        collector.cancel()
        collector.join()
        await { sentOf("plugin_view_close").size == 1 }
        val close = Json.parseToJsonElement(sentOf("plugin_view_close").first().data).jsonObject
        assertEquals("v", (close["view"] as JsonPrimitive).content)
    }

    @Test fun errorAnswerIsOneLine() = runBlocking {
        val states = mutableListOf<ViewState>()
        val c = launch { client.open("m", "v", none).toList(states) }
        await { sentOf("plugin_view").isNotEmpty() }
        client.onFrame(Envelope("plugin_view_result", """{"error":"plugin_view refused: x\nsecond"}""", requestId = sentOf("plugin_view").first().requestId))
        await { states.any { it is ViewState.Error } }
        assertEquals("plugin_view refused: x", (states.last() as ViewState.Error).line)
        c.cancel()
    }

    @Test fun actSendsOrigin() = runBlocking {
        val r = client.act(ViewOrigin("m", "v"), "m_tool", JsonObject(mapOf("a" to JsonPrimitive(1))))
        assertEquals("ok", r.getOrNull())
        assertEquals("m_tool", toolCall!!.first)
        assertEquals("""{"module":"m","view":"v"}""", toolCall!!.third.toString())
    }
}
