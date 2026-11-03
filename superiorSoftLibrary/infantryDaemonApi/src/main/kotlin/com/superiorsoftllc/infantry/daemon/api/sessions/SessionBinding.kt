// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.api.sessions

/*
 * AR-3a (DaemonApi additive minor): a session is an instance of an agent in a chat; what ties it to work is a binding that a
 * layer above the core attaches. Core knows only the opaque [id], a [kind] the attaching layer names, and plain-data [facts];
 * it never interprets them. First-turn context providers turn a binding into a text section of the session's first message.
 */
data class SessionBinding(
    /** Opaque id of the bound thing (core never parses it). */
    val id: String,
    /** What the attaching layer calls it (core never switches on it). */
    val kind: String,
    /** Plain data the provider reads back; no behaviour. */
    val facts: Map<String, String> = emptyMap(),
)

/**
 * A contributor to a session's first turn. Core keeps an ordered list of these and joins their non-blank sections (in
 * registration order, ahead of the message, separated by a `---` line). A provider that throws or returns blank contributes nothing.
 */
fun interface FirstTurnContextProvider {
    /** The text section for [sessionId] bound to [binding], or null/blank for none. */
    fun section(sessionId: String, binding: SessionBinding): String?
}
