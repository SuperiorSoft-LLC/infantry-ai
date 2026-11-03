// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.agents

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

    private val SKIP = Regex("""<!--\s*skip:\s*([^>]*?)\s*-->""")

    /**
     * TOKEN-5: the knowledge for [role]. A `## ` section whose heading carries `<!-- skip: roleA,roleB -->` is left out
     * for those roles (data in KNOWLEDGE.md, not code); no role or an unlisted one gets everything. Markers never show.
     */
    fun textFor(role: String?): String {
        val r = role?.trim()?.lowercase()
        return text.split(Regex("(?m)^(?=## )")).filter { part ->
            val skip = SKIP.find(part.lineSequence().first())?.groupValues?.get(1)
            skip == null || r == null || skip.split(',').none { it.trim().lowercase() == r }
        }.joinToString("") { SKIP.replace(it, "").replace(" \n", "\n") }.trim()
    }

    /** [persona] followed by the shared knowledge (once), minus the sections [role] skips. */
    fun withKnowledge(persona: String?, role: String? = null): String? {
        val base = persona?.takeIf { it.isNotBlank() }
        val knowledge = textFor(role)
        if (knowledge.isEmpty()) return base
        if (base != null && base.contains(knowledge)) return base
        return if (base == null) knowledge else "$base\n\n$knowledge"
    }
}
