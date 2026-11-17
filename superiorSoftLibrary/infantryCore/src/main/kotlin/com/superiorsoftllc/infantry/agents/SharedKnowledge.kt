// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.agents

import com.superiorsoftllc.infantry.daemon.api.knowledge.KnowledgeSections

/**
 * The shipped KNOWLEDGE.md (resource, read at runtime so an update reaches existing installs): the basics every role
 * needs, appended to every role instance's instructions at spawn. A profile or DB persona override cannot hide it.
 */
object SharedKnowledge {
    const val RESOURCE = "KNOWLEDGE.md"

    val text: String by lazy {
        SharedKnowledge::class.java.classLoader.getResourceAsStream(RESOURCE)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }?.replace("\r\n", "\n")?.trim().orEmpty()
    }

    /**
     * TOKEN-5: the knowledge for [role]. A `## ` section whose heading carries `<!-- skip: roleA,roleB -->` is left out
     * for those roles (data in KNOWLEDGE.md, not code); no role or an unlisted one gets everything. Markers never show.
     * PK-1: the parser is the SDK's [KnowledgeSections], shared with plugin knowledge files.
     */
    fun textFor(role: String?): String = KnowledgeSections.textFor(text, role)

    /** [persona] followed by the shared knowledge (once), minus the sections [role] skips. */
    fun withKnowledge(persona: String?, role: String? = null): String? {
        val base = persona?.takeIf { it.isNotBlank() }
        val knowledge = textFor(role)
        if (knowledge.isEmpty()) return base
        if (base != null && base.contains(knowledge)) return base
        return if (base == null) knowledge else "$base\n\n$knowledge"
    }

    /**
     * PK-1: [instructions] (persona + shared knowledge) followed by the plugin briefing [section] (once); a blank section adds
     * nothing. The section comes last so the core text a role relies on keeps its place.
     */
    fun withBriefing(instructions: String?, section: String?): String? {
        val base = instructions?.takeIf { it.isNotBlank() }
        val add = section?.trim()?.takeIf { it.isNotEmpty() } ?: return base
        if (base != null && base.contains(add)) return base
        return if (base == null) add else "$base\n\n$add"
    }
}
