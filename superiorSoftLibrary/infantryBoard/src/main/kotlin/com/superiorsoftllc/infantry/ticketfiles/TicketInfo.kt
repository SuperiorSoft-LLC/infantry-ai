package com.superiorsoftllc.infantry.ticketfiles

/** The ticket data the file index and the related-work resolver read; the board family adapts its rows to it. */
data class TicketInfo(
    val id: String,
    val title: String,
    val description: String,
    val status: String,
    val projectId: String?,
    val branch: String?,
    val base: String?,
)

/** A board link as the related-work resolver reads it. */
data class TicketLinkInfo(val ticketId: String, val relatedId: String)
