package com.superiorsoftllc.agenthq.ui.model

/**
 * ORIGIN-SCRUB-1b (contract 1.91.0): a daemon older than 1.91.0 still sends the retired ticket types. The board shows
 * each under its current name, so a ticket reads the same whichever daemon served it. A current type, or anything
 * unknown, is shown as sent.
 */
private val RETIRED_TICKET_TYPES: Map<String, String> = mapOf(
    "feature" to "enhancement",
    "bug" to "defect",
    "refactor" to "restructure",
    "chore" to "upkeep",
    "docs" to "documentation",
    "style" to "cosmetic",
    "perf" to "performance",
    "test" to "testing",
    "security" to "hardening",
)

fun ticketTypeLabel(type: String): String = RETIRED_TICKET_TYPES[type.trim().lowercase()] ?: type
