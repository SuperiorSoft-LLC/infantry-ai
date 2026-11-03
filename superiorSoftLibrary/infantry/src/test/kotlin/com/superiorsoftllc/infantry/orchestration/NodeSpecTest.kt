// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.orchestration

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class NodeSpecTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun promptActionKeepsAgentId() {
        // A prompt action runs as a one-node pod; its NodeSpec must carry the agent it was bound to.
        val node = NodeSpec(name = "draft", prompt = "draft the release notes", agentId = "role:backend")

        val wire = json.encodeToString(NodeSpec.serializer(), node)
        val back = json.decodeFromString(NodeSpec.serializer(), wire)

        assertEquals("role:backend", back.agentId)
        assertEquals(node, back)
    }

    @Test
    fun promptActionKeepsProviderAndModel() {
        // A prompt action may name the provider and model it runs on; both must survive the wire.
        val node = NodeSpec(
            name = "draft",
            prompt = "draft the release notes",
            agentId = "role:backend",
            provider = "claude",
            model = "claude-haiku-5-5",
        )

        val wire = json.encodeToString(NodeSpec.serializer(), node)
        val back = json.decodeFromString(NodeSpec.serializer(), wire)

        assertEquals("claude", back.provider)
        assertEquals("claude-haiku-5-5", back.model)
        assertEquals(node, back)
    }

    @Test
    fun podActionKeepsAgentId() {
        // A pod action is a stored PodSubmitRequest template; the agent each of its nodes is bound to must survive the wire.
        val request = PodSubmitRequest(
            goal = "ship the release",
            nodes = listOf(NodeSpec(name = "draft", prompt = "draft the release notes", agentId = "role:backend")),
        )

        val wire = json.encodeToString(PodSubmitRequest.serializer(), request)
        val back = json.decodeFromString(PodSubmitRequest.serializer(), wire)

        assertEquals("role:backend", back.nodes.single().agentId)
        assertEquals(request, back)
    }
}
