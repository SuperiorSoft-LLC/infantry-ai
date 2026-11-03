// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.api.attention

/**
 * PLUGIN-MCP-1 (DaemonApi 1.36.0, additive): the owner-question service as a provided service (`services.find(OwnerAsks::class)`),
 * published by the plugin that owns the owner questions. A caller that is not itself an agent turn (the daemon acting on a plugin
 * request) raises the same question `infantry ask` does and hears when the owner resolves it.
 */
interface OwnerAsks {
    /** Raises a question for the owner on behalf of [sessionId] (the answer is also mailed to it); returns the question id. Throws [IllegalArgumentException] on a bad question or a rate limit. */
    fun ask(sessionId: String, question: String, options: List<Pair<String, String>>, recommended: String, reason: String): String

    /** Told once per resolution: [answer] = the chosen option label, null when the owner typed other text, cancelled or the question was orphaned. */
    fun onResolved(listener: (questionId: String, answer: String?) -> Unit): AutoCloseable
}
