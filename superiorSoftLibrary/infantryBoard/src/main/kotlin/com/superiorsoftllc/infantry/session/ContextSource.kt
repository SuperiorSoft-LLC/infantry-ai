package com.superiorsoftllc.infantry.session

import kotlinx.serialization.Serializable

/**
 * Contract 1.6.0: one source the start composed into a session's prompt, and how many characters it
 * contributed. [source] is `instructions`, `ticket` or `linkedTickets`.
 */
@Serializable
data class ContextSource(
    val source: String,
    val chars: Int,
)
